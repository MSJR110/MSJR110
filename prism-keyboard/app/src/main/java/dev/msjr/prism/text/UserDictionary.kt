package dev.msjr.prism.text

import android.content.Context
import java.io.File

/**
 * Words and word-pairs YOU type, learned on the device only. Nothing ever leaves the phone.
 */
class UserDictionary(private val dir: File, private val lang: String) {
    private val words = HashMap<String, Int>()          // lowercase key -> count
    private val display = HashMap<String, String>()     // lowercase key -> how you wrote it
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0
    private val file get() = File(dir, "user_$lang.tsv")

    /** Same key the suggestion engine uses, so "می‌خوام" and "میخوام" count as one word. */
    private fun norm(w: String): String = if (lang == "fa") TextTools.normalizeFa(w).lowercase() else w.lowercase()

    private fun betterDisplay(old: String?, new: String): Boolean =
        old == null || (new.indexOf(TextTools.ZWNJ) >= 0 && old.indexOf(TextTools.ZWNJ) < 0)

    @Synchronized
    fun load() {
        words.clear(); display.clear(); bigrams.clear()
        val f = file
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val p = line.split('\t')
                when {
                    p.size == 3 && p[0] == "w" -> {
                        val k = norm(p[1])
                        words[k] = (words[k] ?: 0) + (p[2].toIntOrNull() ?: 1)
                        if (betterDisplay(display[k], p[1]) || (lang != "fa" && p[1] != k)) display[k] = p[1]
                    }
                    p.size == 4 && p[0] == "b" -> {
                        val m = bigrams.getOrPut(norm(p[1])) { HashMap() }
                        val k = norm(p[2])
                        m[k] = (m[k] ?: 0) + (p[3].toIntOrNull() ?: 1)
                    }
                }
            }
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun learn(word: String, prev: String?) {
        if (word.isEmpty() || word.length > 40) return
        val k = norm(word)
        if (k.isEmpty()) return
        if (words[k] == null) prefixCache.clear()
        words[k] = minOf((words[k] ?: 0) + 1, 100000)
        // keep the capitalisation the user actually uses for proper nouns, and the half-space spelling
        if (betterDisplay(display[k], word) || (lang != "fa" && word != k)) display[k] = word
        if (!prev.isNullOrEmpty()) {
            val m = bigrams.getOrPut(norm(prev)) { HashMap() }
            m[k] = minOf((m[k] ?: 0) + 1, 100000)
            if (m.size > 64) {
                // forget the rarest follower
                m.minByOrNull { it.value }?.key?.let { m.remove(it) }
            }
        }
        dirty++
    }

    @Synchronized
    fun forget(word: String) {
        val k = norm(word)
        words.remove(k); display.remove(k)
        for (m in bigrams.values) m.remove(k)
        dirty++
    }

    @Synchronized fun count(key: String): Int = words[key] ?: 0

    /** Have you typed any word starting with [prefix]? (Cached per prefix so taps stay fast.) */
    @Synchronized
    fun hasPrefix(prefix: String): Boolean {
        prefixCache[prefix]?.let { return it }
        var found = false
        for (k in words.keys) if (k.startsWith(prefix)) { found = true; break }
        if (prefixCache.size > 512) prefixCache.clear()
        prefixCache[prefix] = found
        return found
    }
    private val prefixCache = HashMap<String, Boolean>()
    @Synchronized fun displayOf(key: String): String = display[key] ?: key
    @Synchronized fun bigram(prev: String, key: String): Int = bigrams[prev]?.get(key) ?: 0

    @Synchronized
    fun followers(prev: String, limit: Int): List<Pair<String, Int>> =
        bigrams[prev]?.entries?.sortedByDescending { it.value }?.take(limit)?.map { displayOf(it.key) to it.value } ?: emptyList()

    @Synchronized
    fun wordsWithPrefix(prefix: String, limit: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((k, c) in words) if (k.startsWith(prefix) && k != prefix) out += displayOf(k) to c
        out.sortByDescending { it.second }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    @Synchronized
    fun allKeys(): List<Pair<String, Int>> = words.map { it.key to it.value }

    fun saveIfDirty(force: Boolean = false) {
        val snapshot: String
        synchronized(this) {
            if (dirty == 0 || (!force && dirty < 8)) return
            dirty = 0
            val sb = StringBuilder()
            // prune very large dictionaries, keep the most used
            val top = words.entries.sortedByDescending { it.value }.take(20000)
            for ((k, c) in top) sb.append("w\t").append(display[k] ?: k).append('\t').append(c).append('\n')
            for ((p, m) in bigrams) for ((w, c) in m) {
                if (c >= 1) sb.append("b\t").append(p).append('\t').append(w).append('\t').append(c).append('\n')
            }
            snapshot = sb.toString()
        }
        try {
            val tmp = File(dir, "user_$lang.tmp")
            tmp.writeText(snapshot)
            tmp.renameTo(file)
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun clear() {
        words.clear(); display.clear(); bigrams.clear(); dirty = 0
        file.delete()
    }

    companion object {
        fun clearAll(ctx: Context) {
            File(ctx.filesDir, "user_fa.tsv").delete()
            File(ctx.filesDir, "user_en.tsv").delete()
        }
    }
}
