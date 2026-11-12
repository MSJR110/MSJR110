package dev.msjr.prism.text

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Glide typing: which word was drawn across the keys.
 *
 * Every dictionary word has an ideal path — straight lines through its letters. A finger's path is
 * compared with the ideal path of each word that starts near where the finger went down and ends near
 * where it lifted: both are cut into the same number of evenly spaced points and the average gap between
 * them is "how far off" the drawing is. That is weighed against how common the word is (and how well it
 * follows the previous word), the same way taps are decoded.
 *
 * Plain math in key units (x: key widths, y: rows), so the benchmark runs the very same code.
 */
object GlideDecoder {
    class Candidate(val key: String, val word: String, val score: Float, val dist: Float)

    private const val N = 36
    /** how far (key sizes) the finger may start / end from a word's first / last letter */
    const val END_REACH = 1.35f
    /** thumb wobble while drawing, in key sizes */
    @JvmField var SIGMA = 0.36f
    /** how much "a common word" counts against "a close drawing" */
    @JvmField var FREQ_WEIGHT = 0.55f
    /** an average gap above this (key sizes) is no word at all */
    @JvmField var MAX_DIST = 0.95f

    private class Work {
        val gx = FloatArray(N); val gy = FloatArray(N)
        val wx = FloatArray(N); val wy = FloatArray(N)
        var px = FloatArray(48); var py = FloatArray(48)
    }
    private val work = ThreadLocal.withInitial { Work() }

    /**
     * @param path x, y pairs in key units
     * @param aspect row height / key width: a row is taller than a key is wide, so a miss of "half a row"
     *   is a bigger slip than "half a key"
     * @param words (key, display word, weight ≈ dictionary frequency 1..255, extra log bonus) to consider
     */
    fun decode(
        path: FloatArray, centers: Map<Char, Pt>, aspect: Float,
        dictKeys: Array<String>, dictWords: Array<String>, dictFreqs: IntArray,
        bonus: ((Int) -> Float)?,
        user: List<Triple<String, String, Int>> = emptyList(),
        limit: Int = 4,
    ): List<Candidate> {
        val n = path.size / 2
        if (n < 2) return emptyList()
        val w = work.get()
        val len = resample(path, n, aspect, w.gx, w.gy)
        if (len < 0.5f) return emptyList()
        val sx = path[0]; val sy = path[1] * aspect
        val ex = path[n * 2 - 2]; val ey = path[n * 2 - 1] * aspect
        // letters the path could start and end on
        val startOk = HashSet<Char>(); val endOk = HashSet<Char>()
        for ((ch, c) in centers) {
            if (hyp(c.x - sx, c.y * aspect - sy) <= END_REACH) startOk += ch
            if (hyp(c.x - ex, c.y * aspect - ey) <= END_REACH) endOk += ch
        }
        if (startOk.isEmpty() || endOk.isEmpty()) return emptyList()

        val out = ArrayList<Candidate>()
        fun consider(key: String, word: String, freq: Int, idx: Int) {
            if (key.length < 2) return
            if (key[0] !in startOk) return
            var last = key.length - 1
            while (last > 0 && key[last] == '\'') last--
            if (key[last] !in endOk) return
            // the word's ideal path: through its letters, a doubled letter counted once
            if (key.length > w.px.size) { w.px = FloatArray(key.length + 16); w.py = FloatArray(key.length + 16) }
            var m = 0
            var ideal = 0f
            for (ch in key) {
                val c = centers[ch] ?: if (ch == '\'' || ch == '’') continue else return
                val x = c.x; val y = c.y * aspect
                if (m > 0 && w.px[m - 1] == x && w.py[m - 1] == y) continue
                if (m > 0) ideal += hyp(x - w.px[m - 1], y - w.py[m - 1])
                w.px[m] = x; w.py[m] = y; m++
            }
            if (m < 2) return
            // fingers cut corners (shorter) and overshoot (longer), but not by this much
            if (len < ideal * 0.5f - 0.6f || len > ideal * 1.7f + 1.2f) return
            resamplePoly(w.px, w.py, m, ideal, w.wx, w.wy)
            var sum = 0f
            for (i in 0 until N) sum += hyp(w.gx[i] - w.wx[i], w.gy[i] - w.wy[i])
            // where the finger went down and lifted says the most
            val ends = hyp(w.gx[0] - w.wx[0], w.gy[0] - w.wy[0]) + hyp(w.gx[N - 1] - w.wx[N - 1], w.gy[N - 1] - w.wy[N - 1])
            val dist = (sum / N) * 0.75f + (ends / 2f) * 0.25f
            if (dist > MAX_DIST) return
            val extra = if (idx >= 0) bonus?.invoke(idx) ?: 0f else 0f
            val score = -0.5f * (dist * dist) / (SIGMA * SIGMA) + FREQ_WEIGHT * (freq / 28f + extra)
            out += Candidate(key, word, score, dist)
        }

        for (i in dictKeys.indices) consider(dictKeys[i], dictWords[i], dictFreqs[i], i)
        for ((key, word, count) in user) {
            // your own words: typed once is a so-so word, typed often is among the most common
            consider(key, word, (95f + 28f * ln(1f + count)).toInt().coerceAtMost(255), -1)
        }
        out.sortByDescending { it.score }
        // the same word may come from both lists
        val seen = HashSet<String>()
        return out.filter { seen.add(it.key) }.take(limit)
    }

    private fun hyp(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    /** Cut the finger's path into N evenly spaced points; returns its length. */
    private fun resample(path: FloatArray, n: Int, aspect: Float, ox: FloatArray, oy: FloatArray): Float {
        var total = 0f
        for (i in 1 until n) total += hyp(path[i * 2] - path[i * 2 - 2], (path[i * 2 + 1] - path[i * 2 - 1]) * aspect)
        if (total <= 0f) return 0f
        val step = total / (N - 1)
        ox[0] = path[0]; oy[0] = path[1] * aspect
        var k = 1
        var acc = 0f
        var i = 1
        var ax = path[0]; var ay = path[1] * aspect
        while (i < n && k < N) {
            val bx = path[i * 2]; val by = path[i * 2 + 1] * aspect
            val d = hyp(bx - ax, by - ay)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                ax += (bx - ax) * t; ay += (by - ay) * t
                ox[k] = ax; oy[k] = ay; k++
                acc = 0f
            } else { acc += d; ax = bx; ay = by; i++ }
        }
        while (k < N) { ox[k] = path[n * 2 - 2]; oy[k] = path[n * 2 - 1] * aspect; k++ }
        return total
    }

    private fun resamplePoly(px: FloatArray, py: FloatArray, m: Int, total: Float, ox: FloatArray, oy: FloatArray) {
        val step = total / (N - 1)
        ox[0] = px[0]; oy[0] = py[0]
        var k = 1
        var acc = 0f
        var i = 1
        var ax = px[0]; var ay = py[0]
        while (i < m && k < N) {
            val bx = px[i]; val by = py[i]
            val d = hyp(bx - ax, by - ay)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                ax += (bx - ax) * t; ay += (by - ay) * t
                ox[k] = ax; oy[k] = ay; k++
                acc = 0f
            } else { acc += d; ax = bx; ay = by; i++ }
        }
        while (k < N) { ox[k] = px[m - 1]; oy[k] = py[m - 1]; k++ }
    }

}
