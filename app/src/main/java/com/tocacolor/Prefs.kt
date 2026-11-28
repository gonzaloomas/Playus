package com.tocacolor

object Prefs {
    const val NAME = "config"

    const val MODE = "modo"
    const val MODE_COLOR = 0
    const val MODE_NUMBERS = 1
    const val MODE_PACKAGES = 2
    const val MODE_BINGO = 3

    // Modo bingo
    const val BINGO_CALIBRATED = "bingo_calibrado"
    const val BINGO_RETRY = "bingo_reintento"
    const val BINGO_RETRY_DEF = 700
    // Zona de bolas gbx1..gby2, zona de tus números gcx1..gcy2, centros gc0x..gc3y

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

    // Modo paquetes
    const val PKG_COVER = "pkg_cobertura"
    const val PKG_COVER_DEF = 25
    const val PKG_SWIPE = "pkg_deslizar"
    const val PKG_SWIPE_DEF = 300
    const val PKG_RETRY = "pkg_reintento"
    const val PKG_RETRY_DEF = 600
    const val PKG_CALIBRATED = "pkg_calibrado"
    // Zona por defecto (proporción de pantalla), medida en la captura del juego
    const val PKG_X1 = 0.435f
    const val PKG_Y1 = 0.572f
    const val PKG_X2 = 0.565f
    const val PKG_Y2 = 0.613f

    // Pantallita: esquinas (rx1, ry1) y (rx2, ry2). Botones: b0x, b0y ... b9x, b9y
}
