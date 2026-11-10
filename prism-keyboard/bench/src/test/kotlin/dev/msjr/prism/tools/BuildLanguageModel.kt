package dev.msjr.prism.tools

import dev.msjr.prism.text.Dictionary
import dev.msjr.prism.text.LanguageModel
import dev.msjr.prism.text.TextTools
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Builds `assets/dict/<lang>.lm` (word-pair model) and the held-out benchmark sentences
 * `bench/data/<lang>.txt` from a monolingual corpus (OPUS OpenSubtitles 2018, one line per subtitle).
 *
 *   cd bench && ./gradlew test --tests '*BuildLanguageModel*' -Pprism.corpus=<folder with fa.txt.gz, en.txt>
 *
 * The last 1% of the corpus is never used for training: benchmark sentences come only from there.
 */
class BuildLanguageModel {

    private val corpus = System.getProperty("prism.corpus").orEmpty()

    @Test fun persian() = build("fa")
    @Test fun english() = build("en")

    private fun build(lang: String) {
        assumeTrue("set -Pprism.corpus to build the language model", corpus.isNotEmpty())
        val src = listOf(File(corpus, "$lang.txt.gz"), File(corpus, "$lang.txt")).firstOrNull { it.exists() }
            ?: error("no $lang corpus in $corpus")
        val fa = lang == "fa"
        val dict = File("../app/src/main/assets/dict/$lang.dict").inputStream().use { Dictionary.fromStream(it, persian = fa) }
        val v = dict.size
        check(v < 65535) { "vocabulary too large for 16-bit ids" }
        val ids = HashMap<String, Int>(v * 2)
        for (k in dict.keys) ids[k] = dict.indexOf(k)
        fun key(w: String) = if (fa) TextTools.normalizeFa(w) else w.lowercase()

        val total = lines(src).use { r -> var n = 0L; while (r.readLine() != null) n++; n }
        val trainEnd = total - total / 100
        println("[$lang] ${total} lines, vocabulary $v; training on $trainEnd")

        // ---------------------------------------------------------------- benchmark sentences (held-out tail)
        val bench = ArrayList<String>()
        lines(src).use { r ->
            var i = 0L
            val stride = maxOf(1L, (total - trainEnd) / 6000)
            while (true) {
                val line = r.readLine() ?: break
                i++
                if (i <= trainEnd || (i - trainEnd) % stride != 0L) continue
                benchSentence(line, fa)?.let { s -> if (s !in bench) bench += s }
            }
        }
        val benchSet = HashSet(bench)

        // ---------------------------------------------------------------- counting
        val uni = LongArray(v)
        val ctx = LongArray(v + 1)
        val pairs = LongIntMap(1 shl 24)
        val bos = v
        var tokens = 0L
        lines(src).use { r ->
            var i = 0L
            val sb = StringBuilder()
            while (true) {
                val line = r.readLine() ?: break
                if (++i > trainEnd) break
                // a training line that is also a benchmark sentence would make the benchmark too easy
                if (benchSet.isNotEmpty()) benchSentence(line, fa)?.let { benchSet.remove(it) }
                var prev = bos
                var j = 0
                val n = line.length
                while (j <= n) {
                    val c = if (j < n) line[j] else ' '
                    if (j < n && TextTools.isWordChar(c)) { sb.append(c); j++; continue }
                    if (sb.isNotEmpty()) {
                        val id = ids[key(sb.toString())] ?: -1
                        sb.setLength(0)
                        if (id >= 0) {
                            uni[id]++; tokens++
                            if (prev >= 0) { pairs.add((prev.toLong() shl 16) or id.toLong(), 1); ctx[prev]++ }
                        }
                        prev = id
                    }
                    if (c == '.' || c == '!' || c == '?' || c == '؟' || c == '…') prev = bos
                    else if (c.isDigit()) prev = -1
                    j++
                }
            }
        }
        // benchmark sentences seen verbatim in training are dropped
        bench.retainAll { it in benchSet }
        println("[$lang] $tokens tokens, ${pairs.size} distinct pairs, ${bench.size} benchmark sentences")

        // ---------------------------------------------------------------- probabilities
        val uniTotal = uni.sum().toDouble()
        val lnUni = DoubleArray(v) { ln((uni[it] + 0.5) / (uniTotal + 0.5 * v)) }
        val uniQ = ByteArray(v) { q(-lnUni[it], LanguageModel.UNI_SCALE) }

        // group the pairs by context
        val packed = LongArray(pairs.size)
        var p = 0
        pairs.forEach { k, c -> packed[p++] = (k shl 31) or c.toLong() }
        packed.sort()

        val backoff = ByteArray(v + 1) { 0 }
        val offsets = IntArray(v + 2)
        val outNext = ArrayList<Short>(1 shl 20)
        val outProb = ArrayList<Byte>(1 shl 20)
        val d = 0.75
        var at = 0
        for (a in 0..v) {
            offsets[a] = outNext.size
            val start = at
            while (at < packed.size && (packed[at] ushr 47).toInt() == a) at++
            val n1 = at - start
            val ca = ctx[a].toDouble()
            if (n1 == 0 || ca < MIN_CONTEXT) continue
            val lambda = d * n1 / ca
            backoff[a] = q(-ln(lambda), LanguageModel.PAIR_SCALE)
            val cand = ArrayList<Pair<Int, Double>>()
            for (x in start until at) {
                val w = ((packed[x] ushr 31) and 0xffff).toInt()
                val c = (packed[x] and 0x7fffffffL).toInt()
                if (c < MIN_PAIR) continue
                val pw = (c - d) / ca + lambda * exp(lnUni[w])
                val lnp = ln(pw)
                // only pairs that say something beyond "any word, scaled by λ"
                if (lnp - (ln(lambda) + lnUni[w]) < MIN_GAIN) continue
                cand += w to lnp
            }
            cand.sortByDescending { it.second }
            for ((w, lnp) in cand.take(MAX_FOLLOWERS)) {
                outNext += w.toShort()
                outProb += q(-lnp, LanguageModel.PAIR_SCALE)
            }
        }
        offsets[v + 1] = outNext.size

        val out = File("../app/src/main/assets/dict/$lang.lm")
        DataOutputStream(out.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(LanguageModel.MAGIC); o.writeInt(LanguageModel.VERSION)
            o.writeInt(v); o.writeInt(LanguageModel.checksum(dict.keys))
            o.write(uniQ); o.write(backoff)
            for (x in offsets) o.writeInt(x)
            for (s in outNext) o.writeShort(s.toInt())
            for (b in outProb) o.writeByte(b.toInt())
        }
        println("[$lang] wrote ${out.length() / 1024} KB, ${outNext.size} pairs")

        val bf = File("data/$lang.txt")
        bf.parentFile.mkdirs()
        bf.writeText(bench.take(BENCH_SENTENCES).joinToString("\n", postfix = "\n"))
    }

    /** A clean sentence for the benchmark (normalized words joined by spaces), or null. */
    private fun benchSentence(line: String, fa: Boolean): String? {
        val words = ArrayList<String>()
        val sb = StringBuilder()
        for (c in "$line ") {
            when {
                TextTools.isWordChar(c) -> {
                    if (fa && !TextTools.isPersianLetter(c) && c != TextTools.ZWNJ) return null
                    if (!fa && !(c in 'a'..'z' || c in 'A'..'Z' || c == '\'')) return null
                    sb.append(c)
                }
                c.isDigit() -> return null
                else -> if (sb.isNotEmpty()) {
                    val w = if (fa) TextTools.normalizeFa(sb.toString()) else sb.toString().lowercase()
                    if (w.trim('\'').isNotEmpty()) words += w.trim('\'')
                    sb.setLength(0)
                }
            }
        }
        if (words.size !in 3..12) return null
        return words.joinToString(" ")
    }

    private fun lines(f: File): BufferedReader {
        val raw = f.inputStream().buffered(1 shl 20)
        val s = if (f.name.endsWith(".gz")) GZIPInputStream(raw, 1 shl 16) else raw
        return BufferedReader(InputStreamReader(s, Charsets.UTF_8), 1 shl 20)
    }

    private fun q(x: Double, scale: Float): Byte = (x * scale).roundToInt().coerceIn(0, 255).toByte()

    /** Long → Int counts with open addressing (no boxing: hundreds of millions of updates). */
    private class LongIntMap(cap: Int) {
        private var keys = LongArray(cap)
        private var vals = IntArray(cap)
        var size = 0; private set

        fun add(k: Long, d: Int) {
            if (size * 10 >= keys.size * 6) grow()
            val key = k + 1
            var i = mix(key) and (keys.size - 1)
            while (true) {
                val e = keys[i]
                if (e == key) { vals[i] += d; return }
                if (e == 0L) { keys[i] = key; vals[i] = d; size++; return }
                i = (i + 1) and (keys.size - 1)
            }
        }

        fun forEach(f: (Long, Int) -> Unit) { for (i in keys.indices) if (keys[i] != 0L) f(keys[i] - 1, vals[i]) }

        private fun grow() {
            val ok = keys; val ov = vals
            keys = LongArray(ok.size * 2); vals = IntArray(ok.size * 2); size = 0
            for (i in ok.indices) if (ok[i] != 0L) add(ok[i] - 1, ov[i])
        }

        private fun mix(x: Long): Int { var h = x * -0x61c8864680b583ebL; h = h xor (h ushr 29); return h.toInt() }
    }

    companion object {
        const val MIN_CONTEXT = 20.0
        const val MIN_PAIR = 3
        const val MIN_GAIN = 0.4
        const val MAX_FOLLOWERS = 64
        const val BENCH_SENTENCES = 1200
    }
}
