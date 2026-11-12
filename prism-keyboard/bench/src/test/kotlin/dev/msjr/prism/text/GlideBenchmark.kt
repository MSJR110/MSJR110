package dev.msjr.prism.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Random

/**
 * Glide typing, measured: the most common words are "drawn" by a simulated finger (aiming at each letter
 * with some scatter, cutting corners the way real fingers do) and decoded with the keyboard's own code.
 *
 *   cd bench && ./gradlew test --tests '*GlideBenchmark*'
 */
class GlideBenchmark {

    private fun dict(lang: String) =
        File("../app/src/main/assets/dict/$lang.dict").inputStream().use { Dictionary.fromStream(it, persian = lang == "fa") }

    /** A finger's path through [word]: x, y pairs in key units. */
    private fun draw(word: String, centers: Map<Char, Pt>, sigma: Float, rnd: Random): FloatArray? {
        val pts = ArrayList<FloatArray>()
        for (ch in word) {
            val c = centers[ch] ?: if (ch == '\'') continue else return null
            val p = floatArrayOf(c.x + rnd.nextGaussian().toFloat() * sigma, c.y + rnd.nextGaussian().toFloat() * sigma * 0.7f)
            if (pts.isNotEmpty() && centers[word[word.indexOf(ch)]] === c && pts.last().let { centersEqual(it, c) }) continue
            pts += p
        }
        if (pts.size < 2) return null
        // dense polyline, then two rounds of corner cutting
        var line = ArrayList<FloatArray>()
        for (i in 0 until pts.size - 1) for (s in 0 until 8) {
            val t = s / 8f
            line += floatArrayOf(pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t)
        }
        line += pts.last()
        repeat(3) {
            val out = ArrayList<FloatArray>()
            out += line.first()
            for (i in 1 until line.size - 1) out += floatArrayOf(
                (line[i - 1][0] + 2 * line[i][0] + line[i + 1][0]) / 4f, (line[i - 1][1] + 2 * line[i][1] + line[i + 1][1]) / 4f)
            out += line.last()
            line = out
        }
        val arr = FloatArray(line.size * 2)
        for ((i, p) in line.withIndex()) { arr[i * 2] = p[0]; arr[i * 2 + 1] = p[1] }
        return arr
    }

    private fun centersEqual(p: FloatArray, c: Pt) = false

    @Test
    fun accuracy() {
        val report = StringBuilder("Prism glide benchmark\n")
        for (lang in listOf("fa", "en")) {
            val d = dict(lang)
            val centers = KeyGeometry.centers(lang)
            val top = d.keys.indices.filter { d.keys[it].length >= 2 }.sortedByDescending { d.freqs[it] }.take(3000)
            for (sigma in listOf(0.15f, 0.25f, 0.35f)) {
                val rnd = Random(42)
                var n = 0; var first = 0; var top3 = 0; var none = 0
                val t0 = System.nanoTime()
                for (idx in top) {
                    val path = draw(d.keys[idx], centers, sigma, rnd) ?: continue
                    val res = GlideDecoder.decode(path, centers, 1.5f, d.keys, d.words, d.freqs, null)
                    n++
                    if (res.isEmpty()) none++
                    if (res.firstOrNull()?.key == d.keys[idx]) first++
                    if (res.take(3).any { it.key == d.keys[idx] }) top3++
                }
                val ms = (System.nanoTime() - t0) / 1e6 / n
                report.append(String.format("  [%s] scatter %.2f: first %5.1f%% | in top 3 %5.1f%% | nothing %4.1f%% | %.2f ms/word (%d words)\n",
                    lang, sigma, 100.0 * first / n, 100.0 * top3 / n, 100.0 * none / n, ms, n))
            }
        }
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/glide-benchmark.txt").writeText(report.toString())
    }

    @Test
    fun finglish() {
        val d = dict("fa")
        val cases = mapOf("salam" to "سلام", "khobi" to "خوبی", "chetori" to "چطوری", "mersi" to "مرسی", "mamnoon" to "ممنون",
            "emrooz" to "امروز", "doost" to "دوست", "kheili" to "خیلی", "man" to "من", "khoda" to "خدا", "ketab" to "کتاب")
        val sb = StringBuilder()
        var ok = 0
        for ((latin, want) in cases) {
            val got = Finglish.toPersian(latin, d, 2).map { TextTools.normalizeFa(it.first) }
            if (got.firstOrNull() == want) ok++
            sb.append("  $latin → $got (want $want)\n")
        }
        println("Finglish: $ok / ${cases.size}\n$sb")
        assertTrue("finglish too weak: $ok", ok >= cases.size - 3)
    }

    @Test
    fun extras() {
        assertEquals("36", Extras.calc("total 12*3="))
        assertEquals("2.5", Extras.calc("5/2="))
        assertEquals("۱۴", Extras.calc("۲+۳×۴="))
        assertEquals("30", Extras.calc("(10+5)*2="))
        assertEquals(null, Extras.calc("x="))
        assertEquals(listOf(1405, 8, 21), Extras.toJalali(2026, 11, 12).toList())
        assertEquals(listOf(1403, 1, 1), Extras.toJalali(2024, 3, 20).toList())
        assertEquals(listOf(1404, 12, 29), Extras.toJalali(2026, 3, 20).toList())
        val cal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 12, 9, 5) }
        assertEquals("پنجشنبه ۲۱ آبان", Extras.forWord("امروز", true, cal))
    }
}
