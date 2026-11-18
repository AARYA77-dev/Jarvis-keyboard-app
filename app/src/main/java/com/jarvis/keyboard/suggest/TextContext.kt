package com.jarvis.keyboard.suggest

/** Splits the text before the cursor into: the word being typed, the word before it, and sentence-start state. */
object TextContext {

    class Result(val fragment: String, val prev: String?, val sentenceStart: Boolean)

    private const val SENTENCE_END = ".!?…"
    private const val SOFT_BREAK = ",;:"

    /** [apostropheInWord] is true for English ("don't" is one word) and false for French-style elision ("l'homme"). */
    fun parse(before: CharSequence, apostropheInWord: Boolean): Result {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1], apostropheInWord)) i--
        val fragment = before.substring(i)

        var j = i
        var spaces = 0
        while (j > 0 && before[j - 1].isWhitespace() && before[j - 1] != '\n') {
            j--
            spaces++
        }
        if (j == 0) return Result(fragment, null, true)
        val c = before[j - 1]
        if (c == '\n') return Result(fragment, null, true)
        if (c in SENTENCE_END) return Result(fragment, null, spaces > 0 || fragment.isNotEmpty())
        if (c in SOFT_BREAK && spaces > 0) {
            j--
            while (j > 0 && before[j - 1] == ' ') j--
        } else if (spaces == 0) {
            return Result(fragment, null, false)       // glued to the previous char (elision, symbols...)
        }
        var k = j
        while (k > 0 && isWordChar(before[k - 1], true)) k--
        val prev = if (k < j) before.substring(k, j).lowercase() else null
        return Result(fragment, prev, false)
    }

    private fun isWordChar(c: Char, apostrophe: Boolean): Boolean =
        Character.isLetter(c) || (apostrophe && TextNorm.isApostrophe(c))
}
