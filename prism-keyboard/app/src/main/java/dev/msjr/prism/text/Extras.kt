package dev.msjr.prism.text

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Small things a keyboard can simply know: the answer to a sum, the date, the time. */
object Extras {

    // ------------------------------------------------------------------ calculator

    /**
     * The maths at the end of [before] (the text up to the cursor, ending in "="), worked out:
     * "… 12*3=" → "36". Null if there is no sum there.
     */
    fun calc(before: String): String? {
        if (!before.endsWith("=")) return null
        var i = before.length - 1
        while (i > 0 && isMathChar(before[i - 1])) i--
        val raw = before.substring(i, before.length - 1).trim()
        if (raw.length < 3) return null
        val persian = raw.any { it in '۰'..'۹' }
        val expr = StringBuilder()
        for (c in raw) {
            when (c) {
                in '۰'..'۹' -> expr.append('0' + (c - '۰'))
                in '٠'..'٩' -> expr.append('0' + (c - '٠'))
                '×', 'x', 'X' -> expr.append('*')
                '÷' -> expr.append('/')
                '٫' -> expr.append('.')
                '−', '–' -> expr.append('-')
                '،', ',', '٬', ' ' -> {}
                else -> expr.append(c)
            }
        }
        // a lone number is not a sum
        if (expr.none { it in "+-*/^%" } || expr.none { it.isDigit() }) return null
        val v = try { Parser(expr.toString()).parse() } catch (_: Throwable) { return null }
        if (v.isNaN() || v.isInfinite()) return null
        val text = if (abs(v - Math.rint(v)) < 1e-9 && abs(v) < 1e15) String.format(Locale.US, "%d", Math.rint(v).toLong())
        else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (persian) TextTools.toPersianDigits(text).replace('.', '٫') else text
    }

    private fun isMathChar(c: Char) = c.isDigit() || c in "+-*/^%().٫×÷−– xX،,٬"

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException(); return v }
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = power(); v = if (op == '*') v * r else v / r }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return b.pow(power()) }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            var v: Double
            if (i < s.length && s[i] == '(') { i++; v = expr(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++ }
            else {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                v = s.substring(st, i).toDouble()
            }
            // "50%" = 0.5
            while (i < s.length && s[i] == '%') { i++; v /= 100.0 }
            return v
        }
    }

    // ------------------------------------------------------------------ dates

    private val J_MONTHS = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private val FA_DAYS = arrayOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")

    /** Gregorian → Jalali (Persian calendar): year, month 1..12, day. */
    fun toJalali(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm: Int; val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 } else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        return intArrayOf(jy, jm, jd)
    }

    /**
     * Something to offer right after [word]: «امروز» → the Persian date, «ساعت» → the time,
     * "today" → the date. Null for every other word.
     */
    fun forWord(word: String, persianDigits: Boolean, now: Calendar = Calendar.getInstance()): String? {
        fun digits(s: String) = if (persianDigits) TextTools.toPersianDigits(s) else s
        fun j() = toJalali(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        return when (TextTools.normalizeFa(word).lowercase()) {
            "امروز" -> j().let { FA_DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " " + digits("${it[2]}") + " " + J_MONTHS[it[1] - 1] }
            "تاریخ" -> j().let { digits(String.format(Locale.US, "%d/%02d/%02d", it[0], it[1], it[2])) }
            "امسال" -> digits("${j()[0]}")
            "ساعت" -> digits(String.format(Locale.US, "%d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
            "today", "date" -> String.format(Locale.US, "%tb %<te, %<tY", now)
            "time" -> String.format(Locale.US, "%d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            else -> null
        }
    }
}
