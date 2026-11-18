package com.jarvis.keyboard

import android.graphics.Color

class JarvisTheme(
    val id: String,
    val name: String,
    val accent: Int,      // neon outline colour
    val keyFill: Int,     // idle key body
    val keyFillHot: Int,  // pressed key body
    val text: Int,
    val hint: Int,
    val bgGlow: Int       // ambient illumination behind the keys
)

object ThemeManager {

    const val DEFAULT_ID = "jarvis"

    val themes: List<JarvisTheme> = listOf(
        JarvisTheme(
            "jarvis", "JARVIS Cyan",
            Color.rgb(0, 229, 255), Color.argb(215, 3, 14, 26), Color.argb(240, 0, 92, 130),
            Color.rgb(190, 245, 255), Color.rgb(90, 170, 200), Color.rgb(0, 120, 255)
        ),
        JarvisTheme(
            "stark", "Stark Gold",
            Color.rgb(255, 176, 32), Color.argb(215, 24, 10, 4), Color.argb(240, 140, 66, 0),
            Color.rgb(255, 232, 190), Color.rgb(200, 140, 60), Color.rgb(255, 90, 20)
        ),
        JarvisTheme(
            "ice", "Ice Blue",
            Color.rgb(120, 170, 255), Color.argb(215, 6, 10, 28), Color.argb(240, 40, 70, 170),
            Color.rgb(225, 235, 255), Color.rgb(120, 140, 200), Color.rgb(60, 90, 255)
        ),
        JarvisTheme(
            "matrix", "Matrix Green",
            Color.rgb(57, 255, 120), Color.argb(215, 2, 18, 8), Color.argb(240, 0, 120, 50),
            Color.rgb(200, 255, 215), Color.rgb(70, 170, 100), Color.rgb(0, 200, 80)
        )
    )

    fun byId(id: String): JarvisTheme = themes.firstOrNull { it.id == id } ?: themes[0]
}
