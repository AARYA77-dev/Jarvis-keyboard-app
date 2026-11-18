package com.jarvis.keyboard.suggest

import java.io.BufferedReader
import kotlin.math.abs

class Candidate(val word: String, val score: Double)

/**
 * Frequency dictionary sorted by accent-free key, so prefix lookups are a binary search plus a short scan.
 * File format, one entry per line: `key<TAB>word<TAB>count`, sorted by key.
 */
class WordDictionary private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val counts: IntArray
) {
    val size: Int get() = keys.size

    private val scratch = Array(MAX_LEN + 2) { IntArray(MAX_LEN + 2) }

    fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun hasKey(key: String): Boolean {
        val i = lowerBound(key)
        return i < keys.size && keys[i] == key
    }

    /** Most frequent words whose key starts with [prefix] (the prefix itself included). */
    fun completions(prefix: String, limit: Int): List<Candidate> {
        val out = ArrayList<Candidate>(limit + 1)
        var i = lowerBound(prefix)
        while (i < keys.size && keys[i].startsWith(prefix)) {
            val c = counts[i].toDouble()
            if (out.size < limit || c > out[out.size - 1].score) insert(out, Candidate(words[i], c), limit)
            i++
        }
        return out
    }

    /** Typo candidates: same first letter, edit distance 1 (short words) or 2 (long words), transpositions count as 1. */
    fun fuzzy(typed: String, limit: Int): List<Candidate> {
        val n = typed.length
        if (n < 3 || n > MAX_LEN) return emptyList()
        val maxDist = if (n <= 4) 1 else 2
        val first = typed[0]
        val out = ArrayList<Candidate>(limit + 1)
        var i = lowerBound(first.toString())
        while (i < keys.size && keys[i][0] == first) {
            val k = keys[i]
            if (abs(k.length - n) <= maxDist && !k.startsWith(typed)) {
                val d = distance(typed, k, maxDist)
                if (d <= maxDist) {
                    val score = counts[i] * (if (d == 1) 0.2 else 0.03)
                    if (out.size < limit || score > out[out.size - 1].score) insert(out, Candidate(words[i], score), limit)
                }
            }
            i++
        }
        return out
    }

    /** The [n] most frequent words overall (used as a last-resort fallback for next-word suggestions). */
    fun topWords(n: Int): List<String> {
        val idx = counts.indices.sortedByDescending { counts[it] }
        val out = ArrayList<String>(n)
        for (i in idx) {
            if (out.size >= n) break
            val w = words[i]
            if (w.length >= 2 || w == "a" || w == "i") out.add(w)
        }
        return out
    }

    private fun insert(list: ArrayList<Candidate>, c: Candidate, limit: Int) {
        var p = list.size
        while (p > 0 && list[p - 1].score < c.score) p--
        list.add(p, c)
        if (list.size > limit) list.removeAt(list.size - 1)
    }

    /** Optimal-string-alignment distance with early exit once it must exceed [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        val n = a.length
        val m = b.length
        if (n > MAX_LEN || m > MAX_LEN) return max + 1
        val d = scratch
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
        }
        return d[n][m]
    }

    companion object {
        private const val MAX_LEN = 32

        fun parse(reader: BufferedReader): WordDictionary {
            val k = ArrayList<String>(26000)
            val w = ArrayList<String>(26000)
            val c = ArrayList<Int>(26000)
            reader.useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    k.add(p[0])
                    w.add(p[1])
                    c.add(p[2].toIntOrNull() ?: 1)
                }
            }
            return WordDictionary(k.toTypedArray(), w.toTypedArray(), c.toIntArray())
        }
    }
}
