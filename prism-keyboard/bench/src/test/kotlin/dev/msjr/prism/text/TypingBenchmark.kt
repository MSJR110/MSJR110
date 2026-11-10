package dev.msjr.prism.text

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Typing simulator: types real held-out sentences (bench/data) with a noisy thumb and measures
 * what a person would see. Everything runs through the same code as the keyboard: the tap decoder,
 * the suggestion ranking and autocorrect.
 *
 *   cd bench && ./gradlew test --tests '*TypingBenchmark.benchmark' [-Pprism.bench=dev|test,fast,debug]
 *
 * Numbers per language, for the engine as it was ("before") and as it is ("now"):
 *  - next word: the word you're about to type is already in the strip before its first letter
 *  - saved keys: share of key presses saved by tapping suggestions as soon as the word shows up
 *  - taps σ: words typed with thumb scatter σ (in key sizes). raw = letters as decoded,
 *    final = after autocorrect on space; fixed / broken = autocorrect helped / hurt;
 *    in strip = still wrong, but the right word is one tap away
 *
 * The report is printed and written to build/reports/typing-benchmark.txt.
 * `sweep` (TypingBenchmark.sweep) tries tuning values on the dev half and prints a table.
 */
class TypingBenchmark {

    private val opts = System.getProperty("prism.bench").orEmpty().split(',').map { it.trim() }.toSet()
    private val part: String = listOf("dev", "test").firstOrNull { it in opts } ?: "all"
    private val debug = "debug" in opts
    private val fast = "fast" in opts
    private val examples = StringBuilder()
    private val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(12)

    private class Lang(val lang: String, val sentences: List<List<String>>, val dict: Dictionary, val lm: LanguageModel?)

    private fun load(lang: String, part: String, limit: Int = Int.MAX_VALUE): Lang? {
        val bf = File("data/$lang.txt")
        if (!bf.exists()) return null
        var sentences = bf.readLines().filter { it.isNotBlank() }.map { it.split(' ') }
        val half = sentences.size / 2
        sentences = when (part) { "dev" -> sentences.take(half); "test" -> sentences.drop(half); else -> sentences }
        sentences = sentences.take(limit)
        val dict = File("../app/src/main/assets/dict/$lang.dict").inputStream().use { Dictionary.fromStream(it, persian = lang == "fa") }
        val lmFile = File("../app/src/main/assets/dict/$lang.lm")
        val lm = if (lmFile.exists()) lmFile.inputStream().use { LanguageModel.load(it, dict) } else null
        return Lang(lang, sentences, dict, lm)
    }

    @Test
    fun benchmark() {
        assumeTrue("sweep only", "sweep" !in opts)
        val report = StringBuilder()
        report.append("Prism typing benchmark ($part sentences)\n")
        for (lang in listOf("fa", "en")) {
            val l = load(lang, part, if (fast) 250 else Int.MAX_VALUE)
            assumeTrue("no benchmark sentences for $lang", l != null)
            l!!
            report.append("\n[$lang] ${l.sentences.size} sentences, ${l.sentences.sumOf { it.size }} words\n")
            // "before" = the engine as it was before the word-pair model and the Persian tuning
            for (before in listOf(true, false)) {
                if (before) beforeTuning() else resetTuning()
                val model = if (before) null else l.lm
                val name = if (before) "before" else if (model == null) "now (no model)" else "now"
                val k = keystrokes(l, model)
                report.append(String.format("  %-14s | next word %5.1f%% | saved keys %5.1f%%\n", name, k.nextWord, k.saved))
                for (sigma in if (fast) listOf(0.35) else listOf(0.25, 0.35)) {
                    val t = taps(l, model, sigma, debug && !before)
                    report.append(String.format("  %-14s | taps σ=%.2f: raw %5.1f%% → final %5.1f%%  (fixed %4.1f%%, broken %4.1f%%, in strip %4.1f%%)\n",
                        "", sigma, t.raw, t.final, t.fixed, t.broken, t.inStrip))
                }
            }
            resetTuning()
        }
        if (debug) report.append("\n").append(examples)
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/typing-benchmark.txt").writeText(report.toString())
    }

    /** Tries tuning values on the dev half: one row per setting (taps only, both noise levels). */
    @Test
    fun sweep() {
        assumeTrue("run with -Pprism.bench=sweep", "sweep" in opts)
        val langs = listOf("fa", "en").filter { it in opts || ("fa" !in opts && "en" !in opts) }.mapNotNull { load(it, "dev", 400) }
        val settings: List<Pair<String, () -> Unit>> = sweepSettings()
        val out = StringBuilder()
        for ((name, apply) in settings) {
            resetTuning(); apply()
            val row = StringBuilder(String.format("%-34s", name))
            for (l in langs) for (sigma in listOf(0.25, 0.35)) {
                val t = taps(l, l.lm, sigma, false)
                row.append(String.format(" | %s σ%.2f raw %5.1f final %5.1f brk %4.1f", l.lang, sigma, t.raw, t.final, t.broken))
            }
            println(row); out.append(row).append('\n')
        }
        File("build/reports").mkdirs()
        File("build/reports/typing-sweep.txt").writeText(out.toString())
    }

    private fun resetTuning() {
        SuggestionCore.LM_RANK = 0.08f; SuggestionCore.LM_TAP = 1f; TapDecoder.LM_WEIGHT = 0.65f
        SuggestionCore.FA_AUTO_SHORT = 0.65f; SuggestionCore.FA_AUTO_MID = 1.15f; SuggestionCore.FA_AUTO_LONG = 1.6f
        SuggestionCore.FA_AUTO_MIN_FREQ = 50; SuggestionCore.FA_AUTO_MIN_LEN = 3
        SuggestionCore.REAL_WORD_MARGIN = 99f; SuggestionCore.REAL_WORD_DIST = 0.5f
        SuggestionCore.REAL_WORD_MIN_LEN = 3; SuggestionCore.REAL_WORD_MIN_FREQ = 60
        SuggestionCore.FA_STEM_MIN_FREQ = 60
    }

    /** The settings the engine had before the word-pair model (run without the model). */
    private fun beforeTuning() {
        resetTuning()
        SuggestionCore.FA_AUTO_SHORT = 0.5f; SuggestionCore.FA_AUTO_MID = 0.95f; SuggestionCore.FA_AUTO_LONG = 1.4f
        SuggestionCore.FA_STEM_MIN_FREQ = 256
    }

    /** Edit this list to explore. */
    private fun sweepSettings(): List<Pair<String, () -> Unit>> {
        val s = ArrayList<Pair<String, () -> Unit>>()
        s += "current" to {}
        for (f in listOf(40, 60, 90, 120)) s += "fa stem+ending min freq $f" to { SuggestionCore.FA_STEM_MIN_FREQ = f }
        return s
    }

    // ------------------------------------------------------------------ helpers

    private fun newCore(l: Lang, lm: LanguageModel?): SuggestionCore {
        val dir = Files.createTempDirectory("prism-bench").toFile()
        val core = SuggestionCore(UserDictionary(dir, "fa"), UserDictionary(dir, "en"))
        if (l.lang == "fa") { core.dictFa = l.dict; core.lmFa = lm } else { core.dictEn = l.dict; core.lmEn = lm }
        return core
    }

    private fun key(w: String, lang: String) = if (lang == "fa") TextTools.normalizeFa(w) else w.lowercase()

    private fun inStrip(r: SuggestionResult, target: String, lang: String) =
        r.slots.any { it != null && key(it.text, lang) == target }

    /** Runs [work] over chunks of sentences in parallel, each chunk with its own engine. */
    private fun <T> parallel(l: Lang, lm: LanguageModel?, work: (SuggestionCore, List<List<String>>, Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val size = (l.sentences.size + threads - 1) / threads
            val jobs = l.sentences.chunked(maxOf(1, size)).mapIndexed { i, chunk -> Callable { work(newCore(l, lm), chunk, i) } }
            return pool.invokeAll(jobs).map { it.get() }
        } finally { pool.shutdown() }
    }

    // ------------------------------------------------------------------ keystroke savings

    private class Keys(val nextWord: Double, val saved: Double)

    private fun keystrokes(l: Lang, lm: LanguageModel?): Keys {
        // chars, cost, next-word hits, words, calls, nanos
        val parts = parallel(l, lm) { core, sentences, _ ->
            val a = LongArray(6)
            val t0 = System.nanoTime()
            for (s in sentences) {
                var prev: String? = null
                for (w in s) {
                    a[3]++
                    a[0] += (w.length + 1).toLong()
                    var c = w.length + 1
                    for (i in 0 until w.length) {
                        val r = core.compute(w.substring(0, i), prev, l.lang); a[4]++
                        if (inStrip(r, w, l.lang)) { c = i + 1; if (i == 0) a[2]++; break }
                    }
                    a[1] += c.toLong()
                    prev = w
                }
            }
            a[5] = System.nanoTime() - t0
            a
        }
        val s = LongArray(6); for (p in parts) for (i in s.indices) s[i] += p[i]
        return Keys(100.0 * s[2] / s[3], 100.0 * (1 - s[1].toDouble() / s[0]))
    }

    // ------------------------------------------------------------------ noisy tapping

    private class Taps(val raw: Double, val final: Double, val fixed: Double, val broken: Double, val inStrip: Double)

    private fun taps(l: Lang, lm: LanguageModel?, sigma: Double, collect: Boolean): Taps {
        val lang = l.lang
        val letters = KeyGeometry.letters(lang)
        val byChar = letters.associateBy { it.ch }
        val primary = letters.filter { !it.secondary }
        val lastRow = primary.maxOf { it.center.y }
        val parts = parallel(l, lm) { core, sentences, chunk ->
            val rng = Random(7L + chunk)
            val a = IntArray(6) // n, raw, final, fixed, broken, strip
            val ex = StringBuilder()
            for (s in sentences) {
                var prev: String? = null
                for (w in s) {
                    val typed = StringBuilder()
                    for (ch in w) {
                        val kl = byChar[ch]
                        if (kl == null || kl.secondary) { typed.append(ch); continue }
                        val tx = (kl.center.x + rng.nextGaussian() * sigma).toFloat()
                        val ty = (kl.center.y + rng.nextGaussian() * sigma * 0.9).toFloat()
                        val row = ty.roundToInt().coerceIn(0, lastRow.toInt()).toFloat()
                        val inRow = primary.filter { it.center.y == row }
                        // the letter row with shift and delete at its ends: past the letters is a lost tap
                        val hit = inRow.firstOrNull { abs(tx - it.center.x) <= it.width / 2 }
                            ?: (if (row == lastRow || ty > lastRow + 0.5f) null else inRow.minByOrNull { abs(tx - it.center.x) })
                            ?: continue
                        val cands = ArrayList<KeyGeometry.Letter>()
                        cands += hit
                        for (o in primary) {
                            if (o === hit) continue
                            val dx = tx - o.center.x; val dy = ty - o.center.y
                            if (dx * dx + dy * dy <= TapDecoder.REACH * TapDecoder.REACH) cands += o
                        }
                        val spatial = FloatArray(cands.size) { TapDecoder.spatial(tx - cands[it].center.x, ty - cands[it].center.y, it == 0) }
                        val weights = core.letterWeights(typed.toString(), prev, cands.map { it.ch.toString() })
                        typed.append(cands[if (weights == null) 0 else TapDecoder.pick(spatial, weights)].ch)
                    }
                    val t = typed.toString()
                    val r = core.compute(t, prev, lang)
                    val out = r.autoCorrect ?: t
                    val rawOk = key(t, lang) == w
                    val finOk = key(out, lang) == w
                    a[0]++
                    if (rawOk) a[1]++
                    if (finOk) a[2]++
                    if (!rawOk && finOk) a[3]++
                    if (rawOk && !finOk) a[4]++
                    if (!finOk && inStrip(r, w, lang)) a[5]++
                    if (collect) {
                        if (rawOk && !finOk) ex.append("BROKE $prev | $w: typed $t -> $out\n")
                        if (!rawOk && !finOk) ex.append("MISS  $prev | $w: typed $t -> $out  [${r.slots.joinToString { it?.text ?: "-" }}]\n")
                    }
                    prev = key(out, lang)
                }
            }
            a to ex.toString()
        }
        val s = IntArray(6); for ((p, _) in parts) for (i in s.indices) s[i] += p[i]
        if (collect) examples.append("[$lang σ=$sigma]\n").append(parts.joinToString("") { it.second }.lines().take(150).joinToString("\n")).append('\n')
        fun pct(x: Int) = 100.0 * x / s[0]
        return Taps(pct(s[1]), pct(s[2]), pct(s[3]), pct(s[4]), pct(s[5]))
    }
}
