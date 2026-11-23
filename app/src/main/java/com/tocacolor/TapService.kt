package com.tocacolor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Servicio de accesibilidad: toca la pantalla, muestra el panel flotante
 * (▶/⏸, calibrar, cerrar) y la pantalla de calibración.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var panel: View? = null
    private var playButton: TextView? = null
    private var calib: View? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeAll()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeAll()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Toques ----------

    fun tap(x: Float, y: Float) {
        main.post { dispatchTap(x, y) }
    }

    fun tapSequence(points: List<PointF>, gapMs: Long) {
        points.forEachIndexed { i, p ->
            main.postDelayed({ dispatchTap(p.x, p.y) }, i * gapMs)
        }
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 30))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------- Panel flotante ----------

    fun showPanel(mode: Int) {
        main.post {
            if (panel != null) return@post
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 16
            lp.y = resources.displayMetrics.heightPixels / 3

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(0xDD222222.toInt())
                    cornerRadius = 40f
                }
                setPadding(12, 6, 12, 6)
            }

            fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(22, 10, 22, 10)
                setOnClickListener { onClick() }
            }

            // Asa para arrastrar el panel
            val handle = button("⠿") {}
            var startX = 0f
            var startY = 0f
            var origX = 0
            var origY = 0
            handle.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.rawX; startY = e.rawY; origX = lp.x; origY = lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = origX + (e.rawX - startX).toInt()
                        lp.y = origY + (e.rawY - startY).toInt()
                        wm.updateViewLayout(root, lp)
                    }
                }
                true
            }
            root.addView(handle)

            val play = button("▶") { setActive(!CaptureService.active) }
            playButton = play
            root.addView(play)

            if (mode == Prefs.MODE_NUMBERS) {
                root.addView(button("⚙") { startCalibration() })
            }
            root.addView(button("✕") { CaptureService.instance?.stopSelf() })

            wm.addView(root, lp)
            panel = root
            refreshPlay()
        }
    }

    fun hidePanel() {
        main.post { removeAll() }
    }

    private fun removeAll() {
        panel?.let { runCatching { wm.removeView(it) } }
        calib?.let { runCatching { wm.removeView(it) } }
        panel = null
        calib = null
        playButton = null
    }

    private fun setActive(on: Boolean) {
        if (on && CaptureService.mode == Prefs.MODE_NUMBERS &&
            !getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean(Prefs.CALIBRATED, false)
        ) {
            Toast.makeText(this, "Primero calibra con ⚙", Toast.LENGTH_LONG).show()
            return
        }
        CaptureService.instance?.resetState()
        CaptureService.active = on
        refreshPlay()
    }

    private fun refreshPlay() {
        playButton?.apply {
            text = if (CaptureService.active) "⏸" else "▶"
            setTextColor(if (CaptureService.active) 0xFF66FF66.toInt() else Color.WHITE)
        }
    }

    // ---------- Calibración ----------

    private fun startCalibration() {
        if (calib != null) return
        setActive(false)

        val steps = listOf(
            "Toca la ESQUINA DE ARRIBA A LA IZQUIERDA de la pantallita donde salen los números (con un poco de margen)",
            "Ahora la ESQUINA DE ABAJO A LA DERECHA de la pantallita (con un poco de margen)"
        ) + (0..9).map { "Toca el botón $it" }
        val points = ArrayList<PointF>()

        val root = FrameLayout(this).apply { setBackgroundColor(0x33000000) }
        val msg = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD000000.toInt())
            setPadding(40, 28, 40, 28)
            gravity = Gravity.CENTER
        }
        val msgLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        root.addView(msg, msgLp)

        fun showStep() {
            msg.text = "Calibrando (${points.size + 1}/${steps.size})\n${steps[points.size]}"
        }
        showStep()

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                points.add(PointF(e.rawX, e.rawY))

                // Puntito donde has tocado
                val dot = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.RED)
                    }
                }
                val d = 30
                root.addView(dot, FrameLayout.LayoutParams(d, d).apply {
                    leftMargin = e.x.toInt() - d / 2
                    topMargin = e.y.toInt() - d / 2
                })

                if (points.size == steps.size) {
                    saveCalibration(points)
                    runCatching { wm.removeView(root) }
                    calib = null
                    Toast.makeText(this, "Calibrado ✅ Pulsa ▶ para empezar", Toast.LENGTH_LONG).show()
                } else {
                    // El mensaje se pone en la mitad contraria a donde acabas de tocar
                    msgLp.gravity = if (e.y > root.height / 2) Gravity.TOP else Gravity.BOTTOM
                    msg.layoutParams = msgLp
                    showStep()
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        calib = root
    }

    private fun saveCalibration(p: List<PointF>) {
        val e = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit()
        e.putFloat("rx1", minOf(p[0].x, p[1].x))
        e.putFloat("ry1", minOf(p[0].y, p[1].y))
        e.putFloat("rx2", maxOf(p[0].x, p[1].x))
        e.putFloat("ry2", maxOf(p[0].y, p[1].y))
        for (d in 0..9) {
            e.putFloat("b${d}x", p[2 + d].x)
            e.putFloat("b${d}y", p[2 + d].y)
        }
        e.putBoolean(Prefs.CALIBRATED, true)
        e.apply()
    }
}
