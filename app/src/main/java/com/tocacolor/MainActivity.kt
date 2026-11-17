package com.tocacolor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object Prefs {
    const val NAME = "config"
    const val THRESHOLD = "umbral"
    const val THRESHOLD_DEF = 40
    const val PERCENT = "porcentaje"
    const val PERCENT_DEF = 70
    const val COOLDOWN = "espera"
    const val COOLDOWN_DEF = 400
}

class MainActivity : Activity() {

    private val reqCapture = 1
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        fun label(s: String, size: Float = 16f) = TextView(this).apply {
            text = s
            textSize = size
            setPadding(0, 24, 0, 8)
        }

        fun slider(name: String, key: String, def: Int, min: Int, max: Int, unit: String) {
            val l = label("")
            val sb = SeekBar(this)
            sb.max = max - min
            sb.progress = prefs.getInt(key, def) - min
            l.text = "$name: ${sb.progress + min}$unit"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    l.text = "$name: ${p + min}$unit"
                    prefs.edit().putInt(key, p + min).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(l)
            root.addView(sb)
        }

        status = label("", 15f)
        root.addView(label("TocaColor", 26f))
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Activar servicio de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(label("Ajustes", 20f))
        slider("Cambio mínimo de color (más bajo = más sensible)", Prefs.THRESHOLD, Prefs.THRESHOLD_DEF, 5, 150, "")
        slider("Parte de la pantalla que debe cambiar", Prefs.PERCENT, Prefs.PERCENT_DEF, 30, 100, " %")
        slider("Espera mínima entre toques", Prefs.COOLDOWN, Prefs.COOLDOWN_DEF, 50, 3000, " ms")

        root.addView(Button(this).apply {
            text = "2. Empezar a vigilar"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Parar"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.STOP))
                Toast.makeText(this@MainActivity, "Parado", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TapService.instance != null)
            "✅ Servicio de accesibilidad activado"
        else
            "❌ Servicio de accesibilidad desactivado: pulsa el botón 1"
    }

    private fun start() {
        if (TapService.instance == null) {
            Toast.makeText(this, "Primero activa el servicio de accesibilidad de TocaColor", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Vigilando. Abre el juego.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
