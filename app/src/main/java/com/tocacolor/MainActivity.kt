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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

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

        fun slider(parent: LinearLayout, name: String, key: String, def: Int, min: Int, max: Int, unit: String) {
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
            parent.addView(l)
            parent.addView(sb)
        }

        status = label("", 15f)
        root.addView(label("Playus", 26f))
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Activar servicio de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ---- Modo ----
        root.addView(label("Modo", 20f))
        val colorBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val numbersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val group = RadioGroup(this)
        val rbColor = RadioButton(this).apply { id = 100; text = "Color: tocar cuando cambia el color" }
        val rbNumbers = RadioButton(this).apply { id = 101; text = "Números: pulsar el número que pide" }
        group.addView(rbColor)
        group.addView(rbNumbers)
        root.addView(group)

        fun showMode(m: Int) {
            colorBox.visibility = if (m == Prefs.MODE_COLOR) android.view.View.VISIBLE else android.view.View.GONE
            numbersBox.visibility = if (m == Prefs.MODE_NUMBERS) android.view.View.VISIBLE else android.view.View.GONE
        }
        val currentMode = prefs.getInt(Prefs.MODE, Prefs.MODE_COLOR)
        group.check(if (currentMode == Prefs.MODE_NUMBERS) 101 else 100)
        showMode(currentMode)
        group.setOnCheckedChangeListener { _, id ->
            val m = if (id == 101) Prefs.MODE_NUMBERS else Prefs.MODE_COLOR
            prefs.edit().putInt(Prefs.MODE, m).apply()
            showMode(m)
        }

        // Ajustes modo color
        slider(colorBox, "Cambio mínimo de color (más bajo = más sensible)", Prefs.THRESHOLD, Prefs.THRESHOLD_DEF, 5, 150, "")
        slider(colorBox, "Parte de la pantalla que debe cambiar", Prefs.PERCENT, Prefs.PERCENT_DEF, 30, 100, " %")
        slider(colorBox, "Espera mínima entre toques", Prefs.COOLDOWN, Prefs.COOLDOWN_DEF, 50, 3000, " ms")

        // Ajustes modo números
        numbersBox.addView(label(
            "La primera vez, abre el juego y pulsa ⚙ en el panel flotante para marcar " +
                    "la pantallita y los botones del 0 al 9. Luego pulsa ▶.", 14f
        ))
        slider(numbersBox, "Pausa entre toques (si pide varios números)", Prefs.GAP, Prefs.GAP_DEF, 30, 500, " ms")

        root.addView(colorBox)
        root.addView(numbersBox)

        root.addView(Button(this).apply {
            text = "2. Empezar"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Parar"
            setOnClickListener {
                CaptureService.instance?.stopSelf()
                Toast.makeText(this@MainActivity, "Parado", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(label(
            "Al pulsar Empezar aparecerá un panel flotante: ⠿ para moverlo, ▶ empezar, ⏸ pausar, " +
                    "⚙ calibrar (modo números) y ✕ cerrar. Si cambias de modo, cierra con ✕ y vuelve a Empezar.",
            13f
        ))

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
            Toast.makeText(this, "Primero activa el servicio de accesibilidad de Playus", Toast.LENGTH_LONG).show()
            return
        }
        if (CaptureService.instance != null) {
            Toast.makeText(this, "Ya está funcionando. Usa el panel flotante.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Abre el juego y usa el panel flotante", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
