package dev.msjr.prism.text

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Suggestion(val text: String, val kind: Kind, val score: Float = 0f) {
    enum class Kind { TYPED, WORD, CORRECTION, PREDICTION, LAYOUT_FIX, SHORTCUT, EMOJI, FINGLISH, EXTRA }
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
            // word-pair models: only usable with the exact word list they were built for
            core.dictFa?.let { d -> try { core.lmFa = ctx.assets.open("dict/fa.lm").use { LanguageModel.load(it, d) } } catch (_: Throwable) { } }
            core.dictEn?.let { d -> try { core.lmEn = ctx.assets.open("dict/en.lm").use { LanguageModel.load(it, d) } } catch (_: Throwable) { } }
            try { core.emojiFa = ctx.assets.open("emoji/fa.tsv").use { EmojiIndex.load(it, persian = true) } } catch (_: Throwable) { }
            try { core.emojiEn = ctx.assets.open("emoji/en.tsv").use { EmojiIndex.load(it, persian = false) } } catch (_: Throwable) { }
        }
    }

    fun user(lang: String) = if (lang == "fa") userFa else userEn

    /** How strongly [prefix] starts a real word (0 = no known word starts like this). Fast; main-thread safe. */
    fun prefixStrength(prefix: String, lang: String): Int = core.prefixStrength(prefix, lang)

    /** Emoji matching [query] (Persian or English by its letters); empty until the index has loaded. */
    fun searchEmoji(query: String, limit: Int = 60): List<String> {
        val index = (if (TextTools.isPersianText(query)) core.emojiFa else core.emojiEn) ?: return emptyList()
        return index.search(query, limit)
    }

    var finglish: Boolean
        get() = core.finglish
        set(v) { core.finglish = v }

    /** Which word a finger drew across the keys (see [GlideDecoder]); the answer arrives on the main thread. */
    fun glide(path: FloatArray, centers: Map<Char, Pt>, aspect: Float, prev: String?, lang: String, callback: (List<GlideDecoder.Candidate>) -> Unit) {
        worker.post {
            val res = try { core.glide(path, centers, aspect, prev, lang) } catch (_: Throwable) { emptyList() }
            main.post { callback(res) }
        }
    }

    /** The same, answered at once on the calling thread (a few milliseconds). */
    fun glideNow(path: FloatArray, centers: Map<Char, Pt>, aspect: Float, prev: String?, lang: String): List<GlideDecoder.Candidate> =
        core.glide(path, centers, aspect, prev, lang)

    /** See [SuggestionCore.letterWeights]. Fast; main-thread safe. */
    fun letterWeights(word: String, prev: String?, cands: List<String>): FloatArray? = core.letterWeights(word, prev, cands)

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

    fun request(query: String, prev: String?, lang: String, capitalize: Boolean = false, callback: (SuggestionResult) -> Unit) {
        val my = ++seq
        worker.post {
            if (my != seq) return@post
            val res = try { core.compute(query, prev, lang, capitalize) } catch (_: Throwable) { SuggestionResult.EMPTY }
            main.post { if (my == seq) callback(res) }
        }
    }
}

/** The pure ranking logic (no threads, no Android framework services) — easy to test. */
class SuggestionCore(val userFa: UserDictionary, val userEn: UserDictionary) {
    @Volatile var dictFa: Dictionary? = null
    @Volatile var dictEn: Dictionary? = null
    @Volatile var lmFa: LanguageModel? = null
    @Volatile var lmEn: LanguageModel? = null
    @Volatile var emojiFa: EmojiIndex? = null
    @Volatile var emojiEn: EmojiIndex? = null
    @Volatile var shortcuts: Map<String, String> = emptyMap()
    @Volatile var finglish = true
    private val proximity: Map<String, Map<Char, Pt>> = mapOf("fa" to KeyGeometry.centers("fa"), "en" to KeyGeometry.centers("en"))

    fun user(lang: String) = if (lang == "fa") userFa else userEn

    /**
     * For the letters a tap could have meant: how well each continues the word being typed
     * ("سلا" + م → "سلام" beats "سلا" + ن), as log weights. Null = no opinion, type what was hit.
     */
    fun letterWeights(word: String, prev: String?, cands: List<String>): FloatArray? {
        if (cands.isEmpty() || word.length > 24) return null
        val l = if (TextTools.isPersianText(word + cands[0])) "fa" else "en"
        val out = FloatArray(cands.size) { prefixLogWeight(word + cands[it], prev, l) }
        // the first letter of a word says less about what's coming: let the finger count for more
        if (word.isEmpty()) for (i in out.indices) out[i] *= 0.6f
        return out
    }

    /** The words a glide across the keys most likely drew, best first. */
    fun glide(path: FloatArray, centers: Map<Char, Pt>, aspect: Float, prevRaw: String?, lang: String): List<GlideDecoder.Candidate> {
        val dict = (if (lang == "fa") dictFa else dictEn) ?: return emptyList()
        val lm = lm(lang)
        val ctx = if (lm != null) context(lm, dict, prevRaw, lang) else -1
        val user = user(lang)
        val prev = prevRaw?.let { key(it, lang) }
        val mine = user.allKeys().filter { it.second >= 2 || dict.indexOf(it.first) >= 0 }.map { Triple(it.first, user.displayOf(it.first), it.second) }
        return GlideDecoder.decode(path, centers, aspect, dict.keys, dict.words, dict.freqs,
            bonus = { idx ->
                var b = 0f
                if (lm != null && ctx >= 0) b += lm.bonus(ctx, idx).coerceIn(-LM_CLAMP_DOWN, LM_CLAMP_UP)
                val k = dict.keys[idx]
                val uc = user.count(k)
                if (uc > 0) b += ln(1f + uc) * 0.5f
                if (prev != null) { val bg = user.bigram(prev, k); if (bg > 0) b += 1f + ln(1f + bg) }
                b
            }, user = mine)
    }

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
        // after this word, words usually come in a different mix: each one weighs P(w | prev) / P(w) more
        val lm = lm(lang)
        val ctx = if (lm != null) context(lm, dict, prevRaw, lang) else -1
        if (lm != null && ctx >= 0 && sum > 0.0) {
            val b0 = lm.lnBackoff(ctx) * LM_TAP
            sum *= exp(b0.toDouble())
            for (f in 0 until lm.followerCount(ctx)) {
                val id = lm.followerId(ctx, f)
                if (!dict.keys[id].startsWith(q)) continue
                val b = ((lm.followerLnP(ctx, f) - lm.lnUni(id)) * LM_TAP).toDouble()
                sum += exp(dict.freqs[id] / 28.0) * (exp(b) - exp(b0.toDouble()))
            }
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

    private fun lm(lang: String) = if (lang == "fa") lmFa else lmEn

    /** The model's context id for the word before: sentence start when there is none, -1 if unknown. */
    private fun context(lm: LanguageModel, dict: Dictionary, prevRaw: String?, lang: String): Int =
        if (prevRaw.isNullOrEmpty()) lm.bos else dict.indexOf(key(prevRaw, lang))

    /**
     * Next-word predictions (nothing typed yet): what usually follows [prev] in general, mixed with
     * what *you* usually write after it.
     */
    private fun predict(prev: String?, prevRaw: String?, lang: String, dict: Dictionary?, user: UserDictionary, capitalize: Boolean): SuggestionResult {
        val lm = lm(lang)
        val ctx = if (lm != null && dict != null) context(lm, dict, prevRaw, lang) else -1
        val scores = HashMap<String, Float>()
        val shown = HashMap<String, String>()
        if (lm != null && dict != null && ctx >= 0) {
            for (f in 0 until min(lm.followerCount(ctx), 16)) {
                val id = lm.followerId(ctx, f)
                val k = dict.keys[id]
                scores[k] = lm.followerLnP(ctx, f) + USER_PREDICT * ln(1f + user.count(k)) * 0.25f
                shown[k] = dict.words[id]
            }
        }
        if (!prev.isNullOrEmpty()) {
            for ((w, c) in user.followers(prev, 6)) {
                val k = key(w, lang)
                // what you write after this word beats the general model quickly
                val base = scores[k] ?: run {
                    val id = dict?.indexOf(k) ?: -1
                    if (lm != null && ctx >= 0 && id >= 0) lm.lnBackoff(ctx) + lm.lnUni(id) else -9f
                }
                scores[k] = base + USER_PREDICT * ln(1f + c)
                // a learned word typed without its half-space is shown spelled properly ("نمیشد" → "نمی‌شد")
                var s = w
                if (dict != null && lang == "fa" && w.indexOf(TextTools.ZWNJ) < 0) {
                    val di = dict.indexOf(k)
                    if (di >= 0 && dict.words[di].replace(TextTools.ZWNJ.toString(), "") == w) s = dict.words[di]
                }
                shown.putIfAbsent(k, s)
            }
        }
        val emoji = emojiFor(prevRaw)
        if (scores.isEmpty() && emoji == null) return SuggestionResult.EMPTY
        val best = scores.entries.sortedByDescending { it.value }.take(3)
        val slots = arrayOfNulls<Suggestion>(3)
        val order = intArrayOf(1, 0, 2)
        best.forEachIndexed { i, e ->
            var text = shown[e.key] ?: e.key
            if (capitalize && lang == "en") text = text.replaceFirstChar { it.uppercaseChar() }
            slots[order[i]] = Suggestion(text, Suggestion.Kind.PREDICTION, e.value)
        }
        // "پیتزا " → 🍕 in the strip (in place of the third guess)
        if (emoji != null) slots[if (slots[1] == null) 1 else 2] = Suggestion(emoji, Suggestion.Kind.EMOJI, 0f)
        return SuggestionResult("", slots, null, true)
    }

    /**
     * For a Persian word missing from the word list: how common its stem is, if it is a known word plus
     * a standard ending — ezafe after a silent h ("مسئله" + ی), a possessive ("کتاب" + ش, "خونه" + مون),
     * or the spoken form that drops the h ("ادامه" → "ادامش"). 0 if it isn't.
     */
    private fun stemFreq(q: String, dict: Dictionary): Int {
        var best = 0
        for (suf in FA_ENDINGS) {
            if (q.length < suf.length + 2 || !q.endsWith(suf)) continue
            val stem = q.substring(0, q.length - suf.length)
            // a bare "ی" is also a typo magnet: only accept it as ezafe after a silent h
            if (suf == "ی" && !stem.endsWith("ه")) continue
            best = max(best, dict.freqOf(stem))
            if (suf != "ی" && suf != "ای") best = max(best, dict.freqOf(stem + "ه"))
        }
        return best
    }

    /** An emoji for the word just typed ("قلب" → ❤️), in its own language; not for everyday words. */
    private fun emojiFor(word: String?): String? {
        if (word.isNullOrEmpty()) return null
        val fa = TextTools.isPersianText(word)
        val index = (if (fa) emojiFa else emojiEn) ?: return null
        val dict = if (fa) dictFa else dictEn
        if (dict != null && dict.freqOf(key(word, if (fa) "fa" else "en")) >= EMOJI_MAX_WORD_FREQ) return null
        return index.forWord(word)
    }

    /** [capitalize]: the next word starts a sentence (only used for predictions, before any letter). */
    fun compute(query: String, prevRaw: String?, lang: String, capitalize: Boolean = false): SuggestionResult {
        val dict = if (lang == "fa") dictFa else dictEn
        val user = user(lang)
        val prev = prevRaw?.let { key(it, lang) }
        val lm = lm(lang)
        val ctx = if (lm != null && dict != null) context(lm, dict, prevRaw, lang) else -1

        if (query.isEmpty()) return predict(prev, prevRaw, lang, dict, user, capitalize)

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
        val strongValid = typedFreq >= 40 || userCount >= 2 || q.length == 1 ||
            // a common Persian word plus an ending ("مسئله‌ی", "خونه‌ش", "ادامش") is a real word too
            (lang == "fa" && dictIdx < 0 && dict != null && FA_STEM_MIN_FREQ < 256 && stemFreq(q, dict) >= FA_STEM_MIN_FREQ)
        // anything you've typed yourself before counts as a real word
        val typedValid = strongValid || dictIdx >= 0 || userCount >= 1

        /** How much likelier the word is right after the previous one (dictionary index; score units). */
        fun context(idx: Int): Float {
            if (lm == null || ctx < 0 || idx < 0) return 0f
            return LM_RANK * lm.bonus(ctx, idx).coerceIn(-LM_CLAMP_DOWN, LM_CLAMP_UP)
        }

        fun boost(k: String, idx: Int = -2): Float {
            var b = 0f
            val uc = user.count(k)
            if (uc > 0) b += min(0.45f, (ln(1.0 + uc) * 0.12).toFloat())
            if (prev != null) {
                val bg = user.bigram(prev, k)
                if (bg > 0) b += min(0.5f, 0.18f + bg * 0.08f)
            }
            if (lm != null && ctx >= 0) b += context(if (idx == -2) (dict?.indexOf(k) ?: -1) else idx)
            return b
        }

        // exact
        if (dict != null && dictIdx >= 0) offer(dict.words[dictIdx], Suggestion.Kind.WORD, dict.freqs[dictIdx] / 255f + boost(q, dictIdx) + (if (strongValid) 0.35f else 0f))
        if (userCount > 0) offer(user.displayOf(q), Suggestion.Kind.WORD, 0.6f + boost(q))

        // completions
        if (dict != null) {
            var i = dict.lowerBound(q)
            var scanned = 0
            while (i < dict.size && scanned < 4000) {
                val k = dict.keys[i]
                if (!k.startsWith(q)) break
                if (k.length > q.length) {
                    val s = dict.freqs[i] / 255f + boost(k, i) - 0.035f * (k.length - q.length) - 0.06f
                    offer(dict.words[i], Suggestion.Kind.WORD, s)
                }
                i++; scanned++
            }
            // words that usually come next, even if the scan above stopped before reaching them
            if (lm != null && ctx >= 0) {
                for (f in 0 until lm.followerCount(ctx)) {
                    val id = lm.followerId(ctx, f)
                    val k = dict.keys[id]
                    if (k.length > q.length && k.startsWith(q)) {
                        offer(dict.words[id], Suggestion.Kind.WORD, dict.freqs[id] / 255f + boost(k, id) - 0.035f * (k.length - q.length) - 0.06f)
                    }
                }
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
                        val s = dict.freqs[idx] / 255f + boost(k, idx) - 0.62f * d
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
            (lang != "fa" || (bestCorrFreq >= FA_AUTO_MIN_FREQ && q.length >= FA_AUTO_MIN_LEN))
        val autoLimit = if (lang == "fa") when {
            // Persian has many short real words and colloquial spellings: only fix clear slips
            q.length <= 3 -> FA_AUTO_SHORT
            q.length <= 5 -> FA_AUTO_MID
            else -> FA_AUTO_LONG
        } else when {
            q.length <= 3 -> 0.6f   // short words: only swaps, neighbour keys, missing apostrophes
            q.length <= 4 -> 1.0f
            else -> 1.6f
        }
        // only if the correction is also the overall best guess
        val top = ranked.firstOrNull { !it.text.equals(query, ignoreCase = true) && !it.text.equals(q, ignoreCase = true) }
        val bcTop = bc != null && top != null && top.text.equals(bc.text, ignoreCase = true)
        if (allowAuto && bc != null && bestCorrDist <= autoLimit && bcTop) {
            auto = applyCase(query, bc.text, lang)
        }
        // a real word that doesn't fit here, one slip away from a word that clearly does
        // ("never bake it" → "never make it"): only with the word-pair model, and only by a wide margin
        if (auto == null && bc != null && bcTop && lm != null && ctx >= 0 && userCount == 0 && dictIdx >= 0 &&
            q.length >= REAL_WORD_MIN_LEN && bestCorrDist <= REAL_WORD_DIST && bestCorrFreq >= REAL_WORD_MIN_FREQ
        ) {
            val typedScore = cands[q]?.score ?: 0f
            if (bc.score - typedScore >= REAL_WORD_MARGIN) auto = applyCase(query, bc.text, lang)
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
        // Persian written in Latin letters ("salam") → offer the real thing; never over a common English word
        if (finglish && lang == "en" && q.length >= 3 && layoutFix == null && shortcut == null && userCount == 0 && typedFreq < FINGLISH_MAX_EN_FREQ) {
            val fa = dictFa?.let { d -> Finglish.toPersian(q, d, 1).firstOrNull() }
            if (fa != null && fa.second >= FINGLISH_MIN_FA_FREQ) slots[2] = Suggestion(fa.first, Suggestion.Kind.FINGLISH, 0f)
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

    private fun subCost(a: Char, b: Char, prox: Map<Char, Pt>, lang: String): Float {
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
    private fun distance(a: String, b: String, prox: Map<Char, Pt>, lang: String, maxD: Float): Float {
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

    companion object {
        // how the word-pair model counts (tuned with the typing benchmark in /bench)
        /** score units per nat of "likelier after the previous word" (a dictionary e-fold is 28/255) */
        @JvmField var LM_RANK = 0.08f
        @JvmField var LM_CLAMP_DOWN = 3f
        @JvmField var LM_CLAMP_UP = 6f
        /** how much the model reshapes "which letter makes a word" for tap decoding (1 = fully) */
        @JvmField var LM_TAP = 1f
        /** nats per e-fold of how often you wrote a word after the previous one, for predictions */
        @JvmField var USER_PREDICT = 1.2f

        // Persian autocorrect for words that aren't words: how far (weighted edits) a fix may reach
        @JvmField var FA_AUTO_SHORT = 0.65f
        @JvmField var FA_AUTO_MID = 1.15f
        @JvmField var FA_AUTO_LONG = 1.6f
        @JvmField var FA_AUTO_MIN_FREQ = 50
        @JvmField var FA_AUTO_MIN_LEN = 3

        // fixing a real word that doesn't fit the sentence (needs the word-pair model)
        @JvmField var REAL_WORD_MARGIN = 99f
        @JvmField var REAL_WORD_DIST = 0.5f
        @JvmField var REAL_WORD_MIN_LEN = 3
        @JvmField var REAL_WORD_MIN_FREQ = 60

        /** Finglish: only offered for Persian words at least this common, and not over English words this common */
        const val FINGLISH_MIN_FA_FREQ = 80
        const val FINGLISH_MAX_EN_FREQ = 170

        /** words at least this common (the, من, سلام…) never get an emoji offered after them */
        const val EMOJI_MAX_WORD_FREQ = 200

        /** a Persian stem this common, plus a standard ending, makes a real word (256 = off) */
        @JvmField var FA_STEM_MIN_FREQ = 60
        private val FA_ENDINGS = listOf("ی", "ای", "ش", "م", "ت", "مون", "تون", "شون", "مان", "تان", "شان")
    }
}
