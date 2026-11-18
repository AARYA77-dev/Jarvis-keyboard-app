package com.jarvis.keyboard.suggest

import java.io.BufferedReader
import java.io.Writer

/**
 * What the keyboard has learned from the user: word counts and "previous word -> next word" counts.
 * Everything stays on the device. Pure Kotlin; persistence is plain text so it can be inspected or cleared.
 */
class UserModel {

    private class Entry(var count: Int, val key: String)

    private val words = HashMap<String, Entry>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    var dirty = false
        private set

    val wordCount: Int get() = words.size

    fun learn(word: String, prev: String?) {
        if (!valid(word)) return
        val e = words.getOrPut(word) { Entry(0, TextNorm.key(word)) }
        e.count++
        if (prev != null && valid(prev)) {
            val m = bigrams.getOrPut(prev) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
        }
        dirty = true
        if (words.size > MAX_WORDS) prune()
    }

    fun completions(prefixKey: String, limit: Int = 6): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((w, e) in words) if (e.count >= MIN_COUNT && e.key.startsWith(prefixKey)) out.add(w to e.count)
        out.sortByDescending { it.second }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    fun followers(prev: String): Map<String, Int>? = bigrams[prev]?.filterValues { it >= MIN_COUNT }

    fun bigramCount(prev: String, next: String): Int = bigrams[prev]?.get(next) ?: 0

    fun clear() {
        words.clear()
        bigrams.clear()
        dirty = true
    }

    fun write(out: Writer) {
        for ((w, e) in words) out.write("w\t$w\t${e.count}\n")
        for ((p, m) in bigrams) for ((n, c) in m) out.write("b\t$p\t$n\t$c\n")
        dirty = false
    }

    fun read(reader: BufferedReader) {
        reader.useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                when {
                    p.size == 3 && p[0] == "w" && valid(p[1]) ->
                        words[p[1]] = Entry(p[2].toIntOrNull() ?: 1, TextNorm.key(p[1]))
                    p.size == 4 && p[0] == "b" && valid(p[1]) && valid(p[2]) ->
                        bigrams.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: 1
                }
            }
        }
        dirty = false
    }

    private fun valid(w: String): Boolean =
        w.length in 1..24 &&
            w.all { it.isLetter() || TextNorm.isApostrophe(it) } &&
            w.any { it.isLetter() } &&
            (w.length > 1 || w == "i" || w == "a")

    private fun prune() {
        val keep = words.entries.sortedByDescending { it.value.count }.take(KEEP_WORDS).map { it.key }.toHashSet()
        words.keys.retainAll(keep)
        var total = 0
        for (m in bigrams.values) total += m.size
        if (total > MAX_BIGRAMS) {
            val it = bigrams.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                e.value.entries.removeAll { x -> x.value <= 1 || x.key !in words }
                if (e.value.isEmpty() || e.key !in words) it.remove()
            }
        }
    }

    private companion object {
        const val MIN_COUNT = 2          // a word must be typed twice before it is suggested
        const val MAX_WORDS = 4000
        const val KEEP_WORDS = 3200
        const val MAX_BIGRAMS = 9000
    }
}
