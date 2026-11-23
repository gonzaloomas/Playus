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
        if (mode == Prefs.MODE_NUMBERS) {
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
        scale = if (mode == Prefs.MODE_NUMBERS) 1f else 0.25f
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
        }
    }

    private fun analyze(img: Image) {
        if (!active) {
            previous = null
            return
        }
        if (mode == Prefs.MODE_NUMBERS) analyzeNumbers(img) else analyzeColor(img)
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
                    if (task.isSuccessful) handleText(task.result?.text ?: "")
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
