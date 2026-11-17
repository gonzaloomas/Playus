package com.tocacolor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import kotlin.math.abs
import kotlin.math.max

/**
 * Captura la pantalla continuamente (a baja resolución para ir rápido),
 * compara cada imagen con la anterior y, si casi toda la pantalla ha
 * cambiado de color, toca en el centro.
 */
class CaptureService : Service() {

    companion object {
        const val STOP = "com.tocacolor.STOP"
        private const val GRID_X = 12
        private const val GRID_Y = 20
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var previous: IntArray? = null
    private var lastTap = 0L
    private var screenW = 0
    private var screenH = 0

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

        val t = HandlerThread("captura").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // Capturamos a 1/4 de resolución: suficiente para ver el color y mucho más rápido.
        val w = max(1, screenW / 4)
        val h = max(1, screenH / 4)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                analyze(img)
            } finally {
                img.close()
            }
        }, handler)

        display = mp.createVirtualDisplay(
            "TocaColor", w, h, max(1, dm.densityDpi / 4),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        return START_NOT_STICKY
    }

    private fun analyze(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride

        // Muestreamos una cuadrícula de puntos repartidos por toda la pantalla.
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

    /** Diferencia entre dos colores: el mayor salto entre rojo, verde y azul. */
    private fun distance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
        val dg = abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
        val db = abs((a and 0xff) - (b and 0xff))
        return max(dr, max(dg, db))
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tc", "TocaColor", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "tc")
            .setContentTitle("TocaColor está vigilando")
            .setContentText("Tocará la pantalla cuando cambie el color")
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
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        display = null
        reader = null
        projection = null
        super.onDestroy()
    }
}
