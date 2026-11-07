package dev.msjr.prism.text

import dev.msjr.prism.keyboard.Layouts

object TextTools {
    const val ZWNJ = '‌'

    fun isPersianLetter(c: Char): Boolean =
        (c in 'ء'..'ي') || c == 'پ' || c == 'چ' || c == 'ژ' ||
            c == 'ک' || c == 'گ' || c == 'ی' || c == 'آ' || c == 'ۀ'

    fun isDiacritic(c: Char): Boolean = c in 'ً'..'ٕ'

    /** Characters that continue a word (so suggestions keep working mid-word). */
    fun isWordChar(c: Char): Boolean =
        Character.isLetter(c) || c == '\'' || c == '’' || c == ZWNJ || isDiacritic(c)

    fun isPersianText(s: CharSequence): Boolean {
        for (c in s) if (isPersianLetter(c)) return true
        return false
    }

    /**
     * Lookup key for a Persian word: Arabic letter forms → Persian, no diacritics, no half-space.
     * ("می‌خوام", "میخوام" and "مي‌خوام" are all the same word.)
     */
    fun normalizeFa(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'ي', 'ى' -> sb.append('ی') // ي ى → ی
                'ك' -> sb.append('ک')           // ك → ک
                ZWNJ -> {}
                else -> if (!isDiacritic(c)) sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- half-space in dictionary words

    private const val NON_JOINING = "اآدذرزژوأإؤ"

    /** Words that look like "می + verb" or "noun + ها" but aren't. */
    private val zwnjExceptions = setOf(
        "میدان", "میمون", "میون", "میهمان", "میهمون", "میهمانی", "میان", "میانه", "میانی", "میانسال", "میگو",
        "میلر", "میرا", "میراندا", "میراندو", "مینا", "میجر", "مینگ", "میکس", "میلک", "میلت", "میونگ", "میلوا",
        "میزها", "میسر", "میتو", "میجا", "مینتون", "میکا", "میلا", "میلو", "میتا", "میکر", "میرنا", "میست",
        "میات", "میگز", "میرکت", "میلی", "میکی", "میله", "میلاد", "میلادی", "میزه", "میزم", "میسی", "میزی",
        "مینه", "میستی", "میونی", "میسه", "مینی", "میدنی",
        "تنها", "تنهایی", "تنهای", "آنها", "اینها", "منتها", "منهای", "بینهایت", "درنهایت", "شانگهای",
    )
    private val pluralSuffixes = listOf("هایشان", "هایتان", "هایمان", "هایم", "هایت", "هایش", "هایی", "های", "ها")

    /**
     * Put the half-space back into a dictionary word that lost it ("میخوام" → "می‌خوام",
     * "کتابها" → "کتاب‌ها"), using the dictionary itself as evidence: the verb must also exist
     * as "ب…" ("بخوام"), the noun must exist on its own ("کتاب").
     */
    fun restoreZwnj(w: String, freq: (String) -> Int): String {
        if (w.length < 3 || w in zwnjExceptions || w.indexOf(ZWNJ) >= 0) return w
        for (pre in arrayOf("نمی", "می")) {
            if (w.startsWith(pre) && w.length > pre.length + 1) {
                val rest = w.substring(pre.length)
                if (freq("ب$rest") >= 20 || (rest[0] == 'ا' && freq("بی" + rest.substring(1)) > 0)) {
                    return pre + ZWNJ + rest
                }
            }
        }
        for (suf in pluralSuffixes) {
            if (w.endsWith(suf) && w.length > suf.length + 1) {
                val stem = w.substring(0, w.length - suf.length)
                if (NON_JOINING.indexOf(stem.last()) >= 0) return w
                if (stem.length >= 2 && freq(stem) >= 30) return stem + ZWNJ + suf
                return w
            }
        }
        return w
    }

    // ---------------------------------------------------------------- smart half-space

    private val prefixes = setOf("می", "نمی")
    private val suffixes = setOf(
        "ها", "های", "هایی", "هایم", "هایت", "هایش", "هایمان", "هایتان", "هایشان",
        "تر", "ترین", "ام", "ای", "اید", "ایم", "اند",
    )
    /** Suffixes that are also common stand-alone words: only join them after words that need it. */
    private val strongSuffixes = setOf(
        "ها", "های", "هایی", "هایم", "هایت", "هایش", "هایمان", "هایتان", "هایشان", "ترین",
    )

    /** "می " + letter → "می‌" + letter. */
    fun isJoiningPrefix(word: String): Boolean = word in prefixes

    /**
     * Should the space between [prev] and [word] become a half-space?
     * Only for unambiguous plural / superlative suffixes.
     */
    fun shouldJoinSuffix(prev: String, word: String): Boolean {
        if (prev.isEmpty() || !isPersianLetter(prev.last())) return false
        if (word !in strongSuffixes) return false
        // "ها" after a single letter is usually a different thing
        return prev.length >= 2
    }

    // ---------------------------------------------------------------- wrong layout

    fun enToFa(s: String): String? {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val m = Layouts.EN_TO_FA[c] ?: Layouts.EN_TO_FA[c.lowercaseChar()] ?: return null
            sb.append(m)
        }
        return sb.toString()
    }

    fun faToEn(s: String): String? {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == ZWNJ) return null
            sb.append(Layouts.FA_TO_EN[c] ?: return null)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- digits

    fun toPersianDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('۰' + (c - '0')) else c)
        return sb.toString()
    }
}
