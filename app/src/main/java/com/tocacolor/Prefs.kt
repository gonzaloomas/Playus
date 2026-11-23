package com.tocacolor

object Prefs {
    const val NAME = "config"

    const val MODE = "modo"
    const val MODE_COLOR = 0
    const val MODE_NUMBERS = 1

    // Modo color
    const val THRESHOLD = "umbral"
    const val THRESHOLD_DEF = 40
    const val PERCENT = "porcentaje"
    const val PERCENT_DEF = 70
    const val COOLDOWN = "espera"
    const val COOLDOWN_DEF = 400

    // Modo números
    const val GAP = "pausa"
    const val GAP_DEF = 80
    const val CALIBRATED = "calibrado"
    // Pantallita: esquinas (rx1, ry1) y (rx2, ry2). Botones: b0x, b0y ... b9x, b9y
}
