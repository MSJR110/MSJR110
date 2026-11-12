package dev.msjr.prism.text

/**
 * Finglish → Persian ("salam" → «سلام», "chetori" → «چطوری»): every Latin letter may stand for a few
 * Persian ones (or for a short vowel that is not written at all), so all the readings are walked
 * together with the Persian word list, and only real words survive. The most common one wins.
 */
object Finglish {
    private val digraphs: Map<String, List<String>> = mapOf(
        "kh" to listOf("خ"), "sh" to listOf("ش"), "ch" to listOf("چ"), "zh" to listOf("ژ"),
        "gh" to listOf("ق", "غ"), "ph" to listOf("ف"), "oo" to listOf("و"), "ou" to listOf("و"),
        "ee" to listOf("ی"), "aa" to listOf("ا", "آ"),
    )
    private val consonants: Map<Char, List<String>> = mapOf(
        'b' to listOf("ب"), 'p' to listOf("پ"), 't' to listOf("ت", "ط"), 's' to listOf("س", "ص", "ث"),
        'j' to listOf("ج"), 'h' to listOf("ه", "ح"), 'd' to listOf("د"), 'z' to listOf("ز", "ذ", "ض", "ظ"),
        'r' to listOf("ر"), 'f' to listOf("ف"), 'k' to listOf("ک"), 'g' to listOf("گ"), 'l' to listOf("ل"),
        'm' to listOf("م"), 'n' to listOf("ن"), 'v' to listOf("و"), 'w' to listOf("و"), 'y' to listOf("ی"),
        'q' to listOf("ق", "غ"), 'x' to listOf("خ", "کس"), 'c' to listOf("ک", "س"), '\'' to listOf("ع", "ئ", ""),
    )

    private fun vowel(c: Char, first: Boolean, last: Boolean): List<String>? = when (c) {
        'a' -> if (first) listOf("ا", "آ", "ع") else if (last) listOf("ا", "ه", "") else listOf("", "ا", "ع")
        'e' -> if (first) listOf("ا", "ای", "ع") else if (last) listOf("ه", "", "ی") else listOf("", "ی", "ع")
        'i' -> if (first) listOf("ای") else listOf("ی")
        'o' -> if (first) listOf("ا", "او", "ع") else listOf("", "و")
        'u' -> if (first) listOf("او") else listOf("و")
        else -> null
    }

    /** Persian words [latin] can be read as, most common first (display form, frequency). */
    fun toPersian(latin: String, dict: Dictionary, limit: Int = 2): List<Pair<String, Int>> {
        val s = latin.lowercase()
        if (s.length < 2 || s.length > 18 || s.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val found = HashMap<Int, Int>() // dictionary index → frequency
        var budget = 6000
        val sb = StringBuilder()

        fun hasPrefix(p: String): Boolean {
            if (p.isEmpty()) return true
            val i = dict.lowerBound(p)
            return i < dict.size && dict.keys[i].startsWith(p)
        }

        fun walk(i: Int) {
            if (budget-- <= 0) return
            if (i >= s.length) {
                val idx = dict.indexOf(sb.toString())
                if (idx >= 0) found[idx] = dict.freqs[idx]
                return
            }
            fun take(n: Int, options: List<String>) {
                for (o in options) {
                    val len = sb.length
                    sb.append(o)
                    if (o.isEmpty() || hasPrefix(sb.toString())) walk(i + n)
                    sb.setLength(len)
                }
            }
            if (i + 1 < s.length) {
                digraphs[s.substring(i, i + 2)]?.let { take(2, it) }
                // a doubled consonant is one letter in Persian
                if (s[i] == s[i + 1]) consonants[s[i]]?.let { take(2, it) }
            }
            val c = s[i]
            val v = vowel(c, i == 0, i == s.length - 1)
            if (v != null) take(1, v) else consonants[c]?.let { take(1, it) }
        }
        walk(0)
        return found.entries.sortedByDescending { it.value }.take(limit).map { dict.words[it.key] to it.value }
    }
}
