package dev.msjr.prism.text

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.graphics.PointF
import dev.msjr.prism.keyboard.FieldKind
import dev.msjr.prism.keyboard.Layouts
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Suggestion(val text: String, val kind: Kind, val score: Float = 0f) {
    enum class Kind { TYPED, WORD, CORRECTION, PREDICTION, LAYOUT_FIX, SHORTCUT, EMOJI }
}

/** What the strip shows: [left, center, right]; center is the best guess. */
class SuggestionResult(
    val query: String,
    val slots: Array<Suggestion?>,
    /** If not null, pressing space replaces the typed word with this. */
    val autoCorrect: String?,
    val typedValid: Boolean,
) {
    companion object {
        val EMPTY = SuggestionResult("", arrayOfNulls(3), null, true)
    }
}

/**
 * Completion + typo correction + personal learning + next-word prediction, all on-device.
 * Work runs on its own thread; results arrive on the main thread, stale ones are dropped.
 */
class SuggestionEngine(private val ctx: Context) {

    private val thread = HandlerThread("prism-suggest").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var seq = 0

    val userFa = UserDictionary(ctx.filesDir, "fa")
    val userEn = UserDictionary(ctx.filesDir, "en")
    private val core = SuggestionCore(userFa, userEn)

    var shortcuts: Map<String, String>
        get() = core.shortcuts
        set(v) { core.shortcuts = v }

    init {
        worker.post {
            userFa.load(); userEn.load()
            try { core.dictFa = Dictionary.load(ctx, "dict/fa.dict", persian = true) } catch (_: Throwable) { }
            try { core.dictEn = Dictionary.load(ctx, "dict/en.dict") } catch (_: Throwable) { }
        }
    }

    fun user(lang: String) = if (lang == "fa") userFa else userEn

    /** How strongly [prefix] starts a real word (0 = no known word starts like this). Fast; main-thread safe. */
    fun prefixStrength(prefix: String, lang: String): Int = core.prefixStrength(prefix, lang)

    /** See [SuggestionCore.prefixLogWeight]. Fast; main-thread safe. */
    fun prefixLogWeight(prefix: String, prev: String?, lang: String): Float = core.prefixLogWeight(prefix, prev, lang)

    fun learn(lang: String, word: String, prev: String?) {
        worker.post { user(lang).learn(word, prev) }
    }

    fun forget(lang: String, word: String) { worker.post { user(lang).forget(word) } }

    /** Forget everything learned (from the settings screen). */
    fun clearUser() { worker.post { userFa.clear(); userEn.clear() } }

    fun save(force: Boolean) {
        worker.post { userFa.saveIfDirty(force); userEn.saveIfDirty(force) }
    }

    fun shutdown() {
        save(true)
        thread.quitSafely()
    }

    /** Like [request] but never dropped as stale (used to finish a correction after space was already typed). */
    fun requestOnce(query: String, prev: String?, lang: String, callback: (SuggestionResult) -> Unit) {
        worker.post {
            val res = try { core.compute(query, prev, lang) } catch (_: Throwable) { SuggestionResult.EMPTY }
            main.post { callback(res) }
        }
    }

    fun request(query: String, prev: String?, lang: String, callback: (SuggestionResult) -> Unit) {
        val my = ++seq
        worker.post {
            if (my != seq) return@post
            val res = try { core.compute(query, prev, lang) } catch (_: Throwable) { SuggestionResult.EMPTY }
            main.post { if (my == seq) callback(res) }
        }
    }
}

/** The pure ranking logic (no threads, no Android framework services) — easy to test. */
class SuggestionCore(val userFa: UserDictionary, val userEn: UserDictionary) {
    @Volatile var dictFa: Dictionary? = null
    @Volatile var dictEn: Dictionary? = null
    @Volatile var shortcuts: Map<String, String> = emptyMap()
    private val proximity: Map<String, Map<Char, PointF>> = mapOf("fa" to centers("fa"), "en" to centers("en"))

    fun user(lang: String) = if (lang == "fa") userFa else userEn

    fun prefixStrength(prefix: String, lang: String): Int {
        val q = key(prefix, lang)
        if (q.isEmpty()) return 255
        if (user(lang).hasPrefix(q)) return 255
        val dict = (if (lang == "fa") dictFa else dictEn) ?: return 255 // not loaded yet: don't interfere
        var i = dict.lowerBound(q)
        var best = 0
        var n = 0
        while (i < dict.size && n < 400) {
            if (!dict.keys[i].startsWith(q)) break
            if (dict.freqs[i] > best) best = dict.freqs[i]
            i++; n++
        }
        return best
    }

    /**
     * How likely a word starts with [prefix], as a log weight (natural log; only differences matter).
     * Sums every dictionary word with that start (a frequency of ~28 is one "e"-fold), plus what you've
     * typed yourself and, if given, the words you usually type after [prevRaw].
     * NaN = no opinion (dictionary not loaded yet); -Infinity = nothing known starts like this.
     */
    fun prefixLogWeight(prefix: String, prevRaw: String?, lang: String): Float {
        val q = key(prefix, lang)
        val dict = (if (lang == "fa") dictFa else dictEn) ?: return Float.NaN
        var sum = 0.0
        var i = dict.lowerBound(q)
        var n = 0
        while (i < dict.size && n < 3000) {
            if (!dict.keys[i].startsWith(q)) break
            sum += exp(dict.freqs[i] / 28.0)
            i++; n++
        }
        val user = user(lang)
        val use = user.prefixUse(q)
        // your own words count more the more you use them: typed once (maybe a typo) is like a
        // so-so word, typed ~30 times like the most common words
        if (use > 0) sum += exp((95.0 + 28.0 * ln(1.0 + use)) / 28.0)
        if (!prevRaw.isNullOrEmpty()) {
            for ((w, c) in user.followers(key(prevRaw, lang), 12)) {
                if (key(w, lang).startsWith(q)) sum += exp((215.0 + 28.0 * ln(1.0 + c)) / 28.0)
            }
        }
        return if (sum <= 0.0) Float.NEGATIVE_INFINITY else ln(sum).toFloat()
    }

    // ------------------------------------------------------------------ core

    private fun key(w: String, lang: String) = if (lang == "fa") TextTools.normalizeFa(w) else w.lowercase()

    fun compute(query: String, prevRaw: String?, lang: String): SuggestionResult {
        val dict = if (lang == "fa") dictFa else dictEn
        val user = user(lang)
        val prev = prevRaw?.let { key(it, lang) }

        if (query.isEmpty()) {
            if (prev.isNullOrEmpty()) return SuggestionResult.EMPTY
            val f = user.followers(prev, 3)
            if (f.isEmpty()) return SuggestionResult.EMPTY
            val slots = arrayOfNulls<Suggestion>(3)
            val order = intArrayOf(1, 0, 2)
            f.forEachIndexed { i, (w, c) ->
                // a learned word typed without its half-space is shown spelled properly ("نمیشد" → "نمی‌شد")
                var shown = w
                if (dict != null && lang == "fa" && w.indexOf(TextTools.ZWNJ) < 0) {
                    val di = dict.indexOf(key(w, lang))
                    if (di >= 0 && dict.words[di].replace(TextTools.ZWNJ.toString(), "") == w) shown = dict.words[di]
                }
                slots[order[i]] = Suggestion(shown, Suggestion.Kind.PREDICTION, c.toFloat())
            }
            return SuggestionResult("", slots, null, true)
        }

        val q = key(query, lang)
        val cands = HashMap<String, Suggestion>()
        fun offer(word: String, kind: Suggestion.Kind, score: Float) {
            val k = key(word, lang)
            val old = cands[k]
            if (old == null || old.score < score) {
                // the same word with and without its half-space: show the correct spelling
                val text = if (old != null && old.text.indexOf(TextTools.ZWNJ) >= 0 && word.indexOf(TextTools.ZWNJ) < 0) old.text else word
                cands[k] = Suggestion(text, kind, score)
            } else if (word.indexOf(TextTools.ZWNJ) >= 0 && old.text.indexOf(TextTools.ZWNJ) < 0) {
                cands[k] = Suggestion(word, old.kind, old.score)
            }
        }

        val userCount = user.count(q)
        val dictIdx = dict?.indexOf(q) ?: -1
        val typedFreq = if (dict != null && dictIdx >= 0) dict.freqs[dictIdx] else 0
        // rare dictionary entries (often typos in the source corpus) are only "weakly" valid
        val strongValid = typedFreq >= 40 || userCount >= 2 || q.length == 1
        // anything you've typed yourself before counts as a real word
        val typedValid = strongValid || dictIdx >= 0 || userCount >= 1

        fun boost(k: String): Float {
            var b = 0f
            val uc = user.count(k)
            if (uc > 0) b += min(0.45f, (ln(1.0 + uc) * 0.12).toFloat())
            if (prev != null) {
                val bg = user.bigram(prev, k)
                if (bg > 0) b += min(0.5f, 0.18f + bg * 0.08f)
            }
            return b
        }

        // exact
        if (dict != null && dictIdx >= 0) offer(dict.words[dictIdx], Suggestion.Kind.WORD, dict.freqs[dictIdx] / 255f + boost(q) + (if (strongValid) 0.35f else 0f))
        if (userCount > 0) offer(user.displayOf(q), Suggestion.Kind.WORD, 0.6f + boost(q))

        // completions
        if (dict != null) {
            var i = dict.lowerBound(q)
            var scanned = 0
            while (i < dict.size && scanned < 4000) {
                val k = dict.keys[i]
                if (!k.startsWith(q)) break
                if (k.length > q.length) {
                    val s = dict.freqs[i] / 255f + boost(k) - 0.035f * (k.length - q.length) - 0.06f
                    offer(dict.words[i], Suggestion.Kind.WORD, s)
                }
                i++; scanned++
            }
        }
        for ((w, c) in user.wordsWithPrefix(q, 8)) {
            val k = w.lowercase()
            offer(w, Suggestion.Kind.WORD, 0.55f + boost(k) - 0.03f * (k.length - q.length) + min(0.2f, c * 0.02f))
        }

        // corrections
        var bestCorr: Suggestion? = null
        var bestCorrDist = 99f
        var bestCorrFreq = 0
        if (dict != null && q.length >= 2) {
            val prox = proximity[lang] ?: emptyMap()
            val q0 = q[0]; val q1 = q[1]
            val maxDist = when {
                q.length <= 3 -> 1.0f
                q.length <= 6 -> 1.6f
                else -> 2.2f
            }
            val lo = max(1, q.length - 1)
            val hi = q.length + (if (q.length >= 5) 2 else 1)
            for (len in lo..hi) {
                for (idx in dict.bucket(len)) {
                    val k = dict.keys[idx]
                    if (k == q) continue
                    // first letters are rarely wrong: prune early unless near, similar or swapped
                    val k0 = k[0]
                    if (k0 != q0 && k0 != q1 && subCost(q0, k0, prox, lang) > 0.5f) continue
                    val d = distance(q, k, prox, lang, maxDist)
                    if (d <= maxDist) {
                        val s = dict.freqs[idx] / 255f + boost(k) - 0.62f * d
                        offer(dict.words[idx], Suggestion.Kind.CORRECTION, s)
                        if (bestCorr == null || s > bestCorr.score) {
                            bestCorr = Suggestion(dict.words[idx], Suggestion.Kind.CORRECTION, s)
                            bestCorrDist = d
                            bestCorrFreq = dict.freqs[idx]
                        }
                    }
                }
            }
        }

        // typed the right letters on the wrong layout?
        var layoutFix: Suggestion? = null
        if (q.length >= 2) {
            if (lang == "en") {
                val fa = TextTools.enToFa(query)
                if (fa != null) {
                    val f = dictFa?.freqOf(fa) ?: 0
                    if (f >= 70 && (dictIdx < 0 || (dict?.freqs?.get(dictIdx) ?: 0) < f - 40)) {
                        layoutFix = Suggestion(fa, Suggestion.Kind.LAYOUT_FIX, 9f)
                    }
                }
            } else {
                val en = TextTools.faToEn(query)
                if (en != null) {
                    val f = dictEn?.freqOf(en) ?: 0
                    if (f >= 70 && (dictIdx < 0 || (dict?.freqs?.get(dictIdx) ?: 0) < f - 40)) {
                        layoutFix = Suggestion(if (en == "i") "I" else en, Suggestion.Kind.LAYOUT_FIX, 9f)
                    }
                }
            }
        }

        val shortcut = shortcuts[query.lowercase()]

        // rank
        val ranked = cands.values.sortedByDescending { it.score }.toMutableList()
        val cased = ranked.map { Suggestion(applyCase(query, it.text, lang), it.kind, it.score) }.toMutableList()

        var auto: String? = null
        val bc = bestCorr
        val weakTyped = typedValid && !strongValid
        val allowAuto = userCount == 0 && (!typedValid || (weakTyped && bestCorrFreq >= typedFreq + 80)) &&
            // Persian: only fix toward common words, never very short ones
            (lang != "fa" || (bestCorrFreq >= 50 && q.length >= 3))
        val autoLimit = if (lang == "fa") when {
            // Persian has many short real words and colloquial spellings: only fix clear slips
            q.length <= 3 -> 0.5f
            q.length <= 5 -> 0.95f
            else -> 1.4f
        } else when {
            q.length <= 3 -> 0.6f   // short words: only swaps, neighbour keys, missing apostrophes
            q.length <= 4 -> 1.0f
            else -> 1.6f
        }
        if (allowAuto && bc != null && bestCorrDist <= autoLimit) {
            // only if the correction is also the overall best guess
            val top = ranked.firstOrNull { !it.text.equals(query, ignoreCase = true) && !it.text.equals(q, ignoreCase = true) }
            if (top != null && top.text.equals(bc.text, ignoreCase = true)) {
                auto = applyCase(query, bc.text, lang)
            }
        }

        // a real word typed without its half-space ("میخوام") → the proper spelling ("می‌خوام")
        if (auto == null && lang == "fa" && dict != null && dictIdx >= 0) {
            val proper = dict.words[dictIdx]
            if (proper != query && proper.indexOf(TextTools.ZWNJ) >= 0 &&
                proper.replace(TextTools.ZWNJ.toString(), "") == query.replace(TextTools.ZWNJ.toString(), "")
            ) auto = proper
        }

        if (lang == "en" && query == "i") auto = "I"

        val slots = arrayOfNulls<Suggestion>(3)
        val typedSug = Suggestion(query, Suggestion.Kind.TYPED, 0f)
        val pool = ArrayList<Suggestion>()
        if (shortcut != null) pool += Suggestion(shortcut, Suggestion.Kind.SHORTCUT, 10f)
        if (layoutFix != null) pool += layoutFix
        for (s in cased) if (pool.none { it.text == s.text } && s.text != query) pool += s

        if (auto != null) {
            slots[1] = Suggestion(auto, Suggestion.Kind.CORRECTION, 1f)
            slots[0] = typedSug
            slots[2] = pool.firstOrNull { it.text != auto }
            if (shortcut != null || layoutFix != null) { slots[2] = slots[1]; slots[1] = pool[0] }
        } else {
            // center: shortcut / layout fix / typed word if valid / best guess
            val center = pool.firstOrNull { it.kind == Suggestion.Kind.SHORTCUT || it.kind == Suggestion.Kind.LAYOUT_FIX }
                ?: (if (strongValid) typedSug else pool.firstOrNull() ?: typedSug)
            slots[1] = center
            val rest = pool.filter { it !== center }
            if (center === typedSug) {
                slots[0] = rest.getOrNull(0); slots[2] = rest.getOrNull(1)
            } else {
                slots[0] = typedSug
                slots[2] = rest.getOrNull(0)
            }
        }
        return SuggestionResult(query, slots, auto, strongValid)
    }

    private fun applyCase(typed: String, word: String, lang: String): String {
        if (lang != "en" || typed.isEmpty() || word.isEmpty()) return word
        val allCaps = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
        if (allCaps) return word.uppercase()
        if (typed[0].isUpperCase()) return word.replaceFirstChar { it.uppercaseChar() }
        return word
    }

    // ------------------------------------------------------------------ weighted edit distance

    private var prevRow = FloatArray(40)
    private var curRow = FloatArray(40)
    private var prev2Row = FloatArray(40)

    private val faSimilar: Map<Char, String> = mapOf(
        'ز' to "ذضظ", 'ذ' to "زضظ", 'ض' to "زذظ", 'ظ' to "زذض",
        'س' to "صث", 'ص' to "سث", 'ث' to "سص",
        'ت' to "ط", 'ط' to "ت", 'ه' to "ح", 'ح' to "ه",
        'ق' to "غ", 'غ' to "ق", 'ا' to "عآ", 'ع' to "اء", 'آ' to "ا", 'ء' to "ع",
    )

    private fun subCost(a: Char, b: Char, prox: Map<Char, PointF>, lang: String): Float {
        if (a == b) return 0f
        if (lang == "fa") {
            val s = faSimilar[a]
            if (s != null && s.indexOf(b) >= 0) return 0.35f
        }
        val pa = prox[a]; val pb = prox[b]
        if (pa != null && pb != null) {
            val dx = pa.x - pb.x; val dy = pa.y - pb.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1.25f) return 0.45f
            if (d < 2.1f) return 0.8f
        }
        return 1f
    }

    private fun insDelCost(c: Char): Float = when (c) {
        '\'', '’', TextTools.ZWNJ -> 0.2f
        // long vowels are often skipped when typing Persian fast
        'ا', 'و', 'ی' -> 0.45f
        else -> 1f
    }

    /** Optimal-string-alignment distance with keyboard-aware costs and early exit. */
    private fun distance(a: String, b: String, prox: Map<Char, PointF>, lang: String, maxD: Float): Float {
        val n = a.length; val m = b.length
        if (m + 1 > prevRow.size) { prevRow = FloatArray(m + 8); curRow = FloatArray(m + 8); prev2Row = FloatArray(m + 8) }
        var p2 = prev2Row; var p = prevRow; var c = curRow
        p[0] = 0f
        for (j in 1..m) p[j] = p[j - 1] + insDelCost(b[j - 1])
        for (i in 1..n) {
            c[0] = p[0] + insDelCost(a[i - 1])
            var rowMin = c[0]
            for (j in 1..m) {
                // deleting a char you typed twice by accident, or adding a doubled letter you missed, is cheap
                val del = if (i > 1 && a[i - 1] == a[i - 2]) 0.35f else insDelCost(a[i - 1])
                val ins = if (j > 1 && b[j - 1] == b[j - 2]) 0.35f else insDelCost(b[j - 1])
                var v = min(p[j] + del, c[j - 1] + ins)
                v = min(v, p[j - 1] + subCost(a[i - 1], b[j - 1], prox, lang))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, p2[j - 2] + 0.6f)
                }
                // first letter mistakes are rarer
                if (i == 1 && j == 1 && a[0] != b[0]) v += 0.15f
                c[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxD) return 99f
            val t = p2; p2 = p; p = c; c = t
        }
        prev2Row = p2; prevRow = p; curRow = c
        return p[m]
    }

    // ------------------------------------------------------------------ key geometry

    private fun centers(lang: String): Map<Char, PointF> {
        val layout = Layouts.letters(lang, FieldKind.TEXT, numberRow = false, persianDigits = true)
        val out = HashMap<Char, PointF>()
        layout.rows.forEachIndexed { r, row ->
            val total = row.keys.sumOf { it.width.toDouble() }.toFloat() + row.indent * 2
            var x = (layout.units - total) / 2f + row.indent
            for (k in row.keys) {
                if (k.label.length == 1) out[k.label[0]] = PointF(x + k.width / 2f, r.toFloat())
                // letters that live on a key's long-press (ض on ص, غ on ع…) sit on that same key
                k.hint?.let { h -> if (h.length == 1 && h[0].isLetter()) out.putIfAbsent(h[0], PointF(x + k.width / 2f, r.toFloat())) }
                x += k.width
            }
        }
        return out
    }
}
