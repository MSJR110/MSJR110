package dev.msjr.prism.text

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * A compact frequency word list. Words are kept sorted by their lowercase key for fast prefix lookups,
 * and bucketed by length for fuzzy (typo) matching.
 */
class Dictionary private constructor(
    val keys: Array<String>,     // lowercase, sorted
    val words: Array<String>,    // display form
    val freqs: IntArray,         // 1..255
) {
    private val byLength: Array<IntArray>

    init {
        val maxLen = 32
        val counts = IntArray(maxLen + 1)
        for (k in keys) counts[minOf(k.length, maxLen)]++
        val buckets = Array(maxLen + 1) { IntArray(counts[it]) }
        val fill = IntArray(maxLen + 1)
        for (i in keys.indices) {
            val l = minOf(keys[i].length, maxLen)
            buckets[l][fill[l]++] = i
        }
        byLength = buckets
    }

    val size: Int get() = keys.size

    fun indexOf(key: String): Int {
        val i = keys.binarySearch(key)
        return if (i >= 0) i else -1
    }

    fun freqOf(key: String): Int {
        val i = indexOf(key)
        return if (i >= 0) freqs[i] else 0
    }

    /** First index whose key >= prefix. */
    fun lowerBound(prefix: String): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun bucket(len: Int): IntArray = if (len in byLength.indices) byLength[len] else IntArray(0)

    companion object {
        fun load(ctx: Context, asset: String): Dictionary = fromStream(ctx.assets.open(asset))

        fun fromStream(input: java.io.InputStream): Dictionary {
            val ks = ArrayList<String>(48000)
            val ws = ArrayList<String>(48000)
            val fs = ArrayList<Int>(48000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val w = line.substring(0, tab)
                    val f = line.substring(tab + 1).toIntOrNull() ?: 1
                    ws += w; ks += w.lowercase(); fs += f
                }
            }
            val order = ks.indices.sortedWith { a, b -> ks[a].compareTo(ks[b]) }
            val keys = Array(order.size) { ks[order[it]] }
            val words = Array(order.size) { ws[order[it]] }
            val freqs = IntArray(order.size) { fs[order[it]] }
            return Dictionary(keys, words, freqs)
        }
    }
}
