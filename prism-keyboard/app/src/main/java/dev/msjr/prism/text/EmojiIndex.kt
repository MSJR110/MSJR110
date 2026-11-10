package dev.msjr.prism.text

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Find emoji by name or keyword ("خنده" → 😂 🤣 😆…, "pizza" → 🍕), in Persian or English.
 * Data: Unicode CLDR emoji annotations, packed by the BuildEmojiIndex tool into
 * `assets/emoji/<lang>.tsv` — one line per emoji: emoji, name, keywords (|-separated), rank; most used first.
 */
class EmojiIndex private constructor(private val entries: List<Entry>, private val persian: Boolean) {

    private class Entry(val emoji: String, val name: String, val keywords: List<String>, val rank: Int)

    /** keyword → how many emoji share it (a keyword on many emoji says little about any one of them) */
    private val share = HashMap<String, Int>().also { m -> for (e in entries) for (k in e.keywords) m[k] = (m[k] ?: 0) + 1 }

    val size: Int get() = entries.size

    private fun norm(s: String): String {
        val t = s.trim().lowercase()
        return if (persian) TextTools.normalizeFa(t) else t
    }

    /** Emoji whose name or keywords match [query] (each word of it starting a word of a keyword), best first. */
    fun search(query: String, limit: Int = 40): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val parts = q.split(' ').filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Int, Int>>() // score, index
        for ((i, e) in entries.withIndex()) {
            var total = 0
            for (p in parts) {
                var best = match(e.name, p, name = true)
                for (k in e.keywords) { val s = match(k, p, name = false); if (s > best) best = s }
                if (best == 0) { total = 0; break }
                total += best
            }
            if (total > 0) scored += total to i
        }
        // higher score first; among equals, the more common emoji (earlier in the list) first
        scored.sortWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { it.second })
        return scored.take(limit).map { entries[it.second].emoji }
    }

    private fun match(text: String, p: String, name: Boolean): Int = when {
        // an exact name or keyword counts the same: then the more used emoji wins ("قلب" → ❤️ before 🫀)
        text == p -> 95
        text.startsWith(p) -> if (name) 55 else 50
        text.contains(" $p") -> 30
        else -> 0
    }

    /**
     * An emoji that clearly stands for this one word ("پیتزا" → 🍕), to offer right after it is typed;
     * null for words that are too general or not emoji words at all.
     */
    fun forWord(word: String): String? {
        val w = norm(word)
        if (w.length < (if (persian) 2 else 3)) return null
        // entries are most used first: the first good match wins
        for (e in entries) {
            if (e.name == w) return e.emoji
            for (k in e.keywords) if (k == w && (e.rank < POPULAR_RANK || (share[k] ?: 0) <= MAX_SHARE)) return e.emoji
        }
        return null
    }

    companion object {
        /** a keyword shared by more emoji than this is too general to offer one of them for it */
        const val MAX_SHARE = 6
        /** emoji ranked above this are among the most used anywhere: a shared keyword still picks them */
        const val POPULAR_RANK = 150

        fun load(input: InputStream, persian: Boolean): EmojiIndex {
            val list = ArrayList<Entry>(4000)
            val idx = EmojiIndex(emptyList(), persian) // only for norm()
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    list += Entry(p[0], idx.norm(p[1]), p[2].split('|').map { idx.norm(it) }.filter { it.isNotEmpty() }, p.getOrNull(3)?.toIntOrNull() ?: 9999)
                }
            }
            return EmojiIndex(list, persian)
        }
    }
}
