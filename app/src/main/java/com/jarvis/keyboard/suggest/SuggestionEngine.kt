package com.jarvis.keyboard.suggest

import java.io.BufferedReader
import java.util.Locale

/**
 * Word completion, typo correction and next-word prediction.
 *  - completions come from a bundled frequency dictionary (accent-insensitive prefix match)
 *  - typos come from a bounded edit-distance search
 *  - next words come from bundled bigrams plus what the user has typed ([UserModel])
 */
class SuggestionEngine {

    @Volatile private var dict: WordDictionary? = null
    @Volatile private var builtin: Map<String, List<Candidate>> = emptyMap()
    @Volatile private var topWords: List<String> = emptyList()
    @Volatile var language: String = "en"
        private set

    val user = UserModel()

    val isReady: Boolean get() = dict != null

    /** Parsing takes tens of milliseconds: call from a background thread. */
    fun load(code: String, dictionary: BufferedReader, bigrams: BufferedReader?) {
        val d = WordDictionary.parse(dictionary)
        val b = HashMap<String, List<Candidate>>()
        bigrams?.useLines { lines ->
            for (line in lines) {
                val t = line.indexOf('\t')
                if (t <= 0) continue
                val list = ArrayList<Candidate>()
                for (part in line.substring(t + 1).split(',')) {
                    val c = part.lastIndexOf(':')
                    if (c <= 0) continue
                    list.add(Candidate(part.substring(0, c), part.substring(c + 1).toDoubleOrNull() ?: 1.0))
                }
                b[line.substring(0, t)] = list
            }
        }
        topWords = d.topWords(24)
        builtin = b
        dict = d
        language = code
    }

    /**
     * @param prev word before the one being typed (lower-case) or null
     * @param fragment the word being typed so far; empty means "predict the next word"
     * @param sentenceStart true when the next word starts a sentence
     * @param capitalizeNext true when the editor wants a capital letter next (auto-capitalisation)
     */
    fun suggest(prev: String?, fragment: String, sentenceStart: Boolean, capitalizeNext: Boolean, limit: Int = 3): List<String> {
        if (fragment.isEmpty()) return nextWords(prev, sentenceStart, capitalizeNext, limit)
        val key = TextNorm.key(fragment)
        if (key.isEmpty()) return emptyList()
        val d = dict
        val scores = LinkedHashMap<String, Double>()

        if (d != null) for (c in d.completions(key, 12)) scores[c.word] = c.score
        for ((w, count) in user.completions(key)) scores[w] = (scores[w] ?: 0.0) + count * 2_000_000.0

        if (d != null && (scores.size <= limit || !d.hasKey(key))) {
            for (c in d.fuzzy(key, 6)) if (c.word !in scores) scores[c.word] = c.score
        }

        if (prev != null) {
            val ctx = contextScores(prev)
            for (e in scores.entries) if (ctx.containsKey(e.key)) e.setValue(e.value * 5.0)
        }

        return scores.entries.sortedByDescending { it.value }
            .take(limit)
            .map { fixCase(it.key, fragment) }
    }

    fun learn(word: String, prev: String?) {
        user.learn(word.lowercase(Locale.ROOT), prev?.lowercase(Locale.ROOT))
    }

    // ---------------------------------------------------------------- internals

    private fun contextScores(prev: String): Map<String, Double> {
        val m = HashMap<String, Double>()
        builtin[prev]?.forEach { m[it.word] = it.score }
        user.followers(prev)?.forEach { (w, c) -> m[w] = (m[w] ?: 0.0) + c * 1_000_000_000.0 }
        return m
    }

    private fun nextWords(prev: String?, sentenceStart: Boolean, capitalizeNext: Boolean, limit: Int): List<String> {
        val out = ArrayList<String>(limit)
        if (prev != null) {
            val ranked = contextScores(prev).entries.sortedByDescending { it.value }
            for (e in ranked) {
                if (out.size >= limit) break
                out.add(e.key)
            }
        }
        if (out.size < limit) {
            val fill = if (sentenceStart && prev == null) STARTERS[language] ?: topWords else topWords
            for (w in fill) {
                if (out.size >= limit) break
                if (w != prev && out.none { it.equals(w, ignoreCase = true) }) out.add(w)
            }
        }
        val cap = capitalizeNext || sentenceStart
        return out.map { fixCase(it, null, cap) }
    }

    private fun fixCase(word: String, typed: String?, forceCapital: Boolean = false): String {
        var out = word
        if (typed != null && typed.isNotEmpty()) {
            val letters = typed.filter { it.isLetter() }
            out = when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase(Locale.ROOT)
                typed[0].isUpperCase() -> TextNorm.capitalizeFirst(word)
                else -> word
            }
        }
        if (forceCapital) out = TextNorm.capitalizeFirst(out)
        if (language == "en" && (out == "i" || out.startsWith("i'"))) out = TextNorm.capitalizeFirst(out)
        return out
    }

    private companion object {
        val STARTERS = mapOf(
            "en" to listOf("I", "The", "Thanks"),
            "de" to listOf("Ich", "Das", "Danke"),
            "fr" to listOf("Je", "Merci", "Bonjour"),
            "es" to listOf("Hola", "Gracias", "Buenos")
        )
    }
}
