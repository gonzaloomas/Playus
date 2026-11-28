package com.tocacolor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

/**
 * Captura la pantalla y, según el modo:
 *  - Color: toca en el centro cuando casi toda la pantalla cambia de color.
 *  - Números: lee los números de la pantallita y pulsa esos botones.
 */
class CaptureService : Service() {

    companion object {
        const val STOP = "com.tocacolor.STOP"
        private const val GRID_X = 12
        private const val GRID_Y = 20
        private const val MAX_DIGITS = 12

        @Volatile var instance: CaptureService? = null
        @Volatile var active = false
        @Volatile var mode = Prefs.MODE_COLOR
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var screenW = 0
    private var screenH = 0
    private var scale = 1f // píxeles de captura por píxel de pantalla

    // Modo color
    private var previous: IntArray? = null
    private var lastTap = 0L

    // Modo números
    private var recognizer: TextRecognizer? = null
    private var ocrBusy = false
    private var pendingCrop: Bitmap? = null
    private var lastCropPixels: IntArray? = null
    private var lastSeen = ""

    // Modo paquetes
    private var currentPkg = ' '
    private var lastSwipe = 0L
    private val hsv = FloatArray(3)

    // Modo bingo
    private var bingoSplitY = 0f      // en la imagen compuesta: arriba bolas, abajo tus números
    private var bingoCardOffsetY = 0  // dónde empieza la zona de tus números en la imagen compuesta
    private var bingoCardLeft = 0f    // esquina de la zona de tus números en la pantalla
    private var bingoCardTop = 0f
    private val tappedNum = IntArray(4) { -1 }
    private val tappedAt = LongArray(4)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        instance = this
        active = false
        mode = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getInt(Prefs.MODE, Prefs.MODE_COLOR)
        if (mode == Prefs.MODE_NUMBERS || mode == Prefs.MODE_BINGO) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val t = HandlerThread("captura").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, h)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // Color: 1/4 de resolución basta. Números: resolución completa para leer bien.
        scale = when (mode) {
            Prefs.MODE_NUMBERS, Prefs.MODE_BINGO -> 1f
            Prefs.MODE_PACKAGES -> 0.5f
            else -> 0.25f
        }
        val w = max(1, (screenW * scale).toInt())
        val hgt = max(1, (screenH * scale).toInt())
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                analyze(img)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, h)

        display = mp.createVirtualDisplay(
            "Playus", w, hgt, max(1, (dm.densityDpi * scale).toInt()),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )

        TapService.instance?.showPanel(mode)
        return START_NOT_STICKY
    }

    /** Se llama al pulsar ▶ o ⏸: empieza de cero. */
    fun resetState() {
        handler?.post {
            previous = null
            lastSeen = ""
            lastCropPixels = null
            currentPkg = ' '
            lastSwipe = 0L
            for (i in 0..3) { tappedNum[i] = -1; tappedAt[i] = 0L }
        }
    }

    private fun analyze(img: Image) {
        if (!active) {
            previous = null
            return
        }
        when (mode) {
            Prefs.MODE_NUMBERS -> analyzeNumbers(img)
            Prefs.MODE_PACKAGES -> analyzePackages(img)
            Prefs.MODE_BINGO -> analyzeBingo(img)
            else -> analyzeColor(img)
        }
    }

    // ================= MODO COLOR =================

    private fun analyzeColor(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride

        val current = IntArray(GRID_X * GRID_Y)
        var k = 0
        for (j in 0 until GRID_Y) {
            val y = ((j + 0.5) * img.height / GRID_Y).toInt()
            for (i in 0 until GRID_X) {
                val x = ((i + 0.5) * img.width / GRID_X).toInt()
                val o = y * rs + x * ps
                val red = buf.get(o).toInt() and 0xff
                val green = buf.get(o + 1).toInt() and 0xff
                val blue = buf.get(o + 2).toInt() and 0xff
                current[k++] = (red shl 16) or (green shl 8) or blue
            }
        }

        val old = previous
        previous = current
        if (old == null) return

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val threshold = prefs.getInt(Prefs.THRESHOLD, Prefs.THRESHOLD_DEF)
        val percent = prefs.getInt(Prefs.PERCENT, Prefs.PERCENT_DEF)
        val cooldown = prefs.getInt(Prefs.COOLDOWN, Prefs.COOLDOWN_DEF)

        var changed = 0
        for (n in current.indices) {
            if (distance(current[n], old[n]) > threshold) changed++
        }

        val now = SystemClock.uptimeMillis()
        if (changed * 100 >= current.size * percent && now - lastTap >= cooldown) {
            lastTap = now
            TapService.instance?.tap(screenW / 2f, screenH / 2f)
        }
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
        val dg = abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
        val db = abs((a and 0xff) - (b and 0xff))
        return max(dr, max(dg, db))
    }

    // ================= MODO PAQUETES =================

    private fun analyzePackages(img: Image) {
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val zx1: Float; val zy1: Float; val zx2: Float; val zy2: Float
        if (p.getBoolean(Prefs.PKG_CALIBRATED, false)) {
            zx1 = p.getFloat("px1", 0f); zy1 = p.getFloat("py1", 0f)
            zx2 = p.getFloat("px2", 0f); zy2 = p.getFloat("py2", 0f)
        } else {
            zx1 = Prefs.PKG_X1 * screenW; zy1 = Prefs.PKG_Y1 * screenH
            zx2 = Prefs.PKG_X2 * screenW; zy2 = Prefs.PKG_Y2 * screenH
        }

        val left = (zx1 * scale).toInt().coerceIn(0, img.width - 1)
        val top = (zy1 * scale).toInt().coerceIn(0, img.height - 1)
        val right = (zx2 * scale).toInt().coerceIn(left + 1, img.width)
        val bottom = (zy2 * scale).toInt().coerceIn(top + 1, img.height)

        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride

        var red = 0; var yellow = 0; var blue = 0; var total = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val o = y * rs + x * ps
                val r = buf.get(o).toInt() and 0xff
                val g = buf.get(o + 1).toInt() and 0xff
                val b = buf.get(o + 2).toInt() and 0xff
                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] >= 0.45f && hsv[2] >= 0.45f) {
                    val h = hsv[0]
                    when {
                        h < 22f || h >= 340f -> red++
                        h in 35f..62f -> yellow++
                        h in 185f..225f -> blue++
                    }
                }
                total++
                x += 2
            }
            y += 2
        }
        if (total == 0) return

        val cover = p.getInt(Prefs.PKG_COVER, Prefs.PKG_COVER_DEF)
        val best = maxOf(red, yellow, blue)
        val color = when {
            best * 100 < total * cover -> ' '
            best == red -> 'R'
            best == yellow -> 'Y'
            else -> 'B'
        }

        TapService.instance?.showDetection(
            when (color) {
                'R' -> 0xFFF06A4F.toInt()
                'Y' -> 0xFFFFCF40.toInt()
                'B' -> 0xFF45A8F0.toInt()
                else -> 0xFF888888.toInt()
            }
        )

        if (color == ' ') {
            currentPkg = ' '
            return
        }

        val now = SystemClock.uptimeMillis()
        val retry = p.getInt(Prefs.PKG_RETRY, Prefs.PKG_RETRY_DEF)
        // Paquete nuevo, o el mismo sigue ahí tras un rato (el deslizamiento no funcionó)
        if (color != currentPkg || now - lastSwipe >= retry) {
            currentPkg = color
            lastSwipe = now
            val len = p.getInt(Prefs.PKG_SWIPE, Prefs.PKG_SWIPE_DEF).toFloat()
            val cx = (zx1 + zx2) / 2f
            val cy = (zy1 + zy2) / 2f
            when (color) {
                'R' -> TapService.instance?.swipe(cx, cy, -len, 0f)
                'Y' -> TapService.instance?.swipe(cx, cy, 0f, -len)
                'B' -> TapService.instance?.swipe(cx, cy, len, 0f)
            }
        }
    }

    // ================= MODO BINGO =================

    private fun analyzeBingo(img: Image) {
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        if (!p.getBoolean(Prefs.BINGO_CALIBRATED, false)) return

        fun rect(prefix: String): IntArray {
            val l = (p.getFloat("${prefix}x1", 0f) * scale).toInt().coerceIn(0, img.width - 1)
            val t = (p.getFloat("${prefix}y1", 0f) * scale).toInt().coerceIn(0, img.height - 1)
            val r = (p.getFloat("${prefix}x2", 0f) * scale).toInt().coerceIn(l + 1, img.width)
            val b = (p.getFloat("${prefix}y2", 0f) * scale).toInt().coerceIn(t + 1, img.height)
            return intArrayOf(l, t, r - l, b - t)
        }
        val ball = rect("gb")
        val card = rect("gc")
        val gap = 40
        val w = maxOf(ball[2], card[2])
        val h = ball[3] + gap + card[3]

        // Imagen compuesta: zona de bolas arriba, zona de tus números abajo (fondo gris en medio)
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        fun copy(r: IntArray, dstY: Int) {
            for (y in 0 until r[3]) {
                var o = (r[1] + y) * rs + r[0] * ps
                val row = (dstY + y) * w
                for (x in 0 until r[2]) {
                    val red = buf.get(o).toInt() and 0xff
                    val green = buf.get(o + 1).toInt() and 0xff
                    val blue = buf.get(o + 2).toInt() and 0xff
                    px[row + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                    o += ps
                }
            }
        }
        copy(ball, 0)
        copy(card, ball[3] + gap)

        if (lastCropPixels?.contentEquals(px) == true) return
        lastCropPixels = px

        bingoSplitY = ball[3] + gap / 2f
        bingoCardOffsetY = ball[3] + gap
        bingoCardLeft = card[0] / scale
        bingoCardTop = card[1] / scale

        pendingCrop = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        runOcrIfIdle()
    }

    private fun handleBingo(text: Text) {
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val cx = FloatArray(4) { p.getFloat("gc${it}x", 0f) }
        val cy = FloatArray(4) { p.getFloat("gc${it}y", 0f) }

        // Distancia máxima para asignar un número leído a una de tus 4 casillas
        var minPair = Float.MAX_VALUE
        for (i in 0..3) for (j in i + 1..3) {
            val d = kotlin.math.hypot(cx[i] - cx[j], cy[i] - cy[j])
            if (d < minPair) minPair = d
        }
        val maxDist = minPair * 0.6f

        val balls = HashSet<Int>()
        val cards = IntArray(4) { -1 }
        val cardDist = FloatArray(4) { Float.MAX_VALUE }

        for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
            val box = el.boundingBox ?: continue
            var digits = el.text.filter { it.isDigit() }
            if (digits.isEmpty() && el.text.length <= 2) digits = toDigits(el.text)
            if (digits.isEmpty() || digits.length > 3) continue
            val n = digits.toInt()
            val y = box.exactCenterY()
            if (y < bingoSplitY) {
                balls.add(n)
            } else {
                val sx = bingoCardLeft + box.exactCenterX() / scale
                val sy = bingoCardTop + (y - bingoCardOffsetY) / scale
                for (i in 0..3) {
                    val d = kotlin.math.hypot(sx - cx[i], sy - cy[i])
                    if (d < maxDist && d < cardDist[i]) {
                        cardDist[i] = d
                        cards[i] = n
                    }
                }
            }
        }

        TapService.instance?.showInfo(cards.joinToString(" ") { if (it < 0) "?" else it.toString() })
        if (!active) return

        val now = SystemClock.uptimeMillis()
        val retry = p.getInt(Prefs.BINGO_RETRY, Prefs.BINGO_RETRY_DEF)
        for (i in 0..3) {
            val n = cards[i]
            if (n < 0 || n !in balls) continue
            // No volver a pulsar la misma casilla con el mismo número enseguida
            if (tappedNum[i] == n && now - tappedAt[i] < retry) continue
            tappedNum[i] = n
            tappedAt[i] = now
            TapService.instance?.tap(cx[i], cy[i])
        }
    }

    // ================= MODO NÚMEROS =================

    private fun analyzeNumbers(img: Image) {
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        if (!p.getBoolean(Prefs.CALIBRATED, false)) return

        val left = (p.getFloat("rx1", 0f) * scale).toInt().coerceIn(0, img.width - 1)
        val top = (p.getFloat("ry1", 0f) * scale).toInt().coerceIn(0, img.height - 1)
        val right = (p.getFloat("rx2", 0f) * scale).toInt().coerceIn(left + 1, img.width)
        val bottom = (p.getFloat("ry2", 0f) * scale).toInt().coerceIn(top + 1, img.height)
        val cw = right - left
        val ch = bottom - top
        if (cw < 8 || ch < 8) return

        // Copiamos solo la zona de la pantallita
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val px = IntArray(cw * ch)
        for (y in 0 until ch) {
            var o = (top + y) * rs + left * ps
            val row = y * cw
            for (x in 0 until cw) {
                val red = buf.get(o).toInt() and 0xff
                val green = buf.get(o + 1).toInt() and 0xff
                val blue = buf.get(o + 2).toInt() and 0xff
                px[row + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                o += ps
            }
        }

        // Si la pantallita no ha cambiado, no hace falta volver a leerla
        if (lastCropPixels?.contentEquals(px) == true) return
        lastCropPixels = px

        pendingCrop = Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
        runOcrIfIdle()
    }

    private fun runOcrIfIdle() {
        if (ocrBusy) return
        val bmp = pendingCrop ?: return
        val rec = recognizer ?: return
        val h = handler ?: return
        pendingCrop = null
        ocrBusy = true
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnCompleteListener { task ->
                h.post {
                    ocrBusy = false
                    if (task.isSuccessful) {
                        val result = task.result
                        if (mode == Prefs.MODE_BINGO) {
                            if (result != null) handleBingo(result)
                        } else {
                            handleText(result?.text ?: "")
                        }
                    }
                    // Si mientras leíamos llegó una imagen nueva, la leemos ahora
                    runOcrIfIdle()
                }
            }
    }

    private fun handleText(raw: String) {
        val digits = toDigits(raw)
        if (digits.isEmpty()) {
            lastSeen = ""
            return
        }
        if (digits == lastSeen) return

        // Si la secuencia crece (p. ej. "47" -> "472"), solo pulsamos lo nuevo
        val toTap = if (lastSeen.isNotEmpty() && digits.startsWith(lastSeen))
            digits.substring(lastSeen.length) else digits
        lastSeen = digits
        if (!active || toTap.isEmpty()) return

        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val gap = p.getInt(Prefs.GAP, Prefs.GAP_DEF).toLong()
        val points = toTap.take(MAX_DIGITS).map { c ->
            val d = c - '0'
            PointF(p.getFloat("b${d}x", 0f), p.getFloat("b${d}y", 0f))
        }
        TapService.instance?.tapSequence(points, gap)
    }

    /** Convierte el texto leído en solo cifras, corrigiendo letras que se confunden con números. */
    private fun toDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val d = when (c) {
                in '0'..'9' -> c
                'O', 'o', 'D', 'Q' -> '0'
                'l', 'I', 'i', '|', '!', 'L' -> '1'
                'Z', 'z' -> '2'
                'S', 's' -> '5'
                'G', 'b' -> '6'
                'T' -> '7'
                'B' -> '8'
                'g', 'q' -> '9'
                else -> null
            }
            if (d != null) sb.append(d)
        }
        return sb.toString()
    }

    // ================= Servicio =================

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tc", "Playus", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "tc")
            .setContentTitle("Playus está funcionando")
            .setContentText("Usa el panel flotante: ▶ empezar, ⏸ pausar")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        active = false
        instance = null
        TapService.instance?.hidePanel()
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer?.close()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        recognizer = null
        super.onDestroy()
    }
}
