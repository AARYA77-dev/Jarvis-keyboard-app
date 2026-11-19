package com.jarvis.keyboard

enum class KeyType {
    CHAR, SPACE, SHIFT, BACKSPACE, ENTER,
    MODE_NUM, MODE_SYM, LANG, SETTINGS, CURSOR_LEFT, CURSOR_RIGHT,
    SELECT_ALL, COPY, CUT, PASTE, DEL_WORD, HIDE, EMOJI
}

enum class Layer { LETTERS, NUMBERS, SYMBOLS }

enum class ShiftState { OFF, ON, LOCK }

/** FAN = the four typing rows, DIAL = a slot on the rotating tool ring. */
enum class Zone { FAN, DIAL }

/** Static description of one key before it is given a place on the radial grid. */
class KeyDef(
    val type: KeyType,
    val label: String,
    val output: String = label,
    val hint: String? = null,
    val weight: Float = 1f
)

class Language(val id: String, val code: String, val rows: List<String>)

/** Mutable keyboard state shared between the service (writer) and the view/renderer (readers). */
class KeyboardState {
    var layer: Layer = Layer.LETTERS
    var shift: ShiftState = ShiftState.OFF
    var language: Language = KeyboardLayouts.languages[0]
}

object KeyboardLayouts {

    val languages: List<Language> = listOf(
        Language("en", "EN", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        Language("de", "DE", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
        Language("fr", "FR", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),
        Language("es", "ES", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"))
    )

    private val numberRows = listOf("1234567890", "@#\$%&-+()", "*\"':;!?")
    private val symbolRows = listOf("~`|•√π÷×¶∆", "£¢€¥^°={}", "\\©®™[]<>")

    /**
     * The tools on the rotating dial, in ring order. Slots 0-2 are visible at rest; dragging the dial
     * brings the others into view. The ring is divided evenly between however many tools there are.
     */
    val dialTools: List<KeyDef> = listOf(
        KeyDef(KeyType.LANG, "LANG"),
        KeyDef(KeyType.MODE_NUM, "123"),
        KeyDef(KeyType.EMOJI, "EMOJI"),
        KeyDef(KeyType.MODE_SYM, "#+="),
        KeyDef(KeyType.CURSOR_LEFT, "<"),
        KeyDef(KeyType.CURSOR_RIGHT, ">"),
        KeyDef(KeyType.SELECT_ALL, "ALL"),
        KeyDef(KeyType.COPY, "COPY"),
        KeyDef(KeyType.CUT, "CUT"),
        KeyDef(KeyType.PASTE, "PASTE"),
        KeyDef(KeyType.DEL_WORD, "DELW"),
        KeyDef(KeyType.HIDE, "HIDE"),
        KeyDef(KeyType.SETTINGS, "SET")
    )

    fun languageIndex(id: String): Int = languages.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /**
     * Rows of the radial fan, outermost row first.
     *  rows 0-1: letters (or digits / symbols)
     *  row 2:    [shift, or #+= / 123 on the number pages] letters [backspace]
     *  row 3:    ,  SPACE  .  [enter]
     * Letters get their number-row / punctuation twin as a long-press hint at the same position.
     */
    fun fanRows(language: Language, layer: Layer): List<List<KeyDef>> {
        val rows = when (layer) {
            Layer.LETTERS -> language.rows
            Layer.NUMBERS -> numberRows
            Layer.SYMBOLS -> symbolRows
        }
        val out = ArrayList<List<KeyDef>>(4)
        for (r in rows.indices) {
            val row = rows[r]
            val defs = ArrayList<KeyDef>(row.length + 2)
            for (c in row.indices) {
                val s = row[c].toString()
                val hint = if (layer == Layer.LETTERS) numberRows.getOrNull(r)?.getOrNull(c)?.toString() else null
                defs.add(KeyDef(KeyType.CHAR, s, s, hint))
            }
            if (r == 2) {
                // Left end: shift (letters) or the symbol-page toggle (number pages). Right end: backspace.
                val left = if (layer == Layer.LETTERS) {
                    KeyDef(KeyType.SHIFT, "SHIFT", "", null, 1.5f)
                } else {
                    KeyDef(KeyType.MODE_SYM, if (layer == Layer.SYMBOLS) "123" else "#+=", "", null, 1.5f)
                }
                defs.add(0, left)
                defs.add(KeyDef(KeyType.BACKSPACE, "DEL", "", null, 1.5f))
            }
            out.add(defs)
        }
        out.add(
            listOf(
                KeyDef(KeyType.CHAR, ",", ",", null, 1.15f),
                KeyDef(KeyType.SPACE, "", " ", null, 3.7f),
                KeyDef(KeyType.CHAR, ".", ".", null, 1.15f),
                KeyDef(KeyType.ENTER, "ENTER", "", null, 1.6f)
            )
        )
        return out
    }
}
