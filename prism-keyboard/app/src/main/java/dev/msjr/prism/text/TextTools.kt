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

    /** Arabic → Persian letter forms so dictionary lookups match. */
    fun normalizeFa(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'ي', 'ى' -> sb.append('ی') // ي ى → ی
                'ك' -> sb.append('ک')           // ك → ک
                else -> if (!isDiacritic(c)) sb.append(c)
            }
        }
        return sb.toString()
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
