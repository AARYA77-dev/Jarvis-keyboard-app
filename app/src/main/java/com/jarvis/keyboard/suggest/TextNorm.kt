package com.jarvis.keyboard.suggest

import java.text.Normalizer
import java.util.Locale

/** Text helpers shared by the dictionary, the learner and the context parser. Pure Kotlin (no Android). */
object TextNorm {

    /** Lower-case, accent-stripped, apostrophe-free form used for matching ("Café" -> "cafe", "don't" -> "dont"). */
    fun key(s: String): String {
        val lower = s.lowercase(Locale.ROOT).replace("ß", "ss")
        val d = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch == '\'' || ch == '’') continue
            sb.append(ch)
        }
        return sb.toString()
    }

    fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’'

    fun capitalizeFirst(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)
}
