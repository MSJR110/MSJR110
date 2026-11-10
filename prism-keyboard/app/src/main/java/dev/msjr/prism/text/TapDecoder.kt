package dev.msjr.prism.text

import kotlin.math.max

/**
 * Which letter a tap most likely meant: a soft "where did the thumb land" model (a bell curve around
 * each key) times "which letter makes a word". The hit key gets a head start and an unknown word never
 * outweighs a tap well inside a key, so deliberate spellings still come out exactly as tapped.
 * Plain math (no views) so the typing benchmark runs the exact same decision as the keyboard.
 */
object TapDecoder {
    // touch model: thumb scatter around a key's centre, in key sizes
    const val SIGMA_X = 0.40f
    const val SIGMA_Y = 0.38f
    /** candidate keys must be within this distance (in key sizes) of the touch */
    const val REACH = 1.25f
    /** head start for the key that was actually hit (in log units) */
    const val HIT_BONUS = 0.35f
    /** how much "this makes a word" counts against where the finger landed */
    @JvmField var LM_WEIGHT = 0.65f
    /** a letter that starts no known word is only this much (log units) worse than the best */
    const val LM_FLOOR = 3.5f

    /** Log-likelihood of a touch [dx], [dy] key sizes away from a key's centre. */
    fun spatial(dx: Float, dy: Float, hit: Boolean): Float =
        -0.5f * (dx * dx / (SIGMA_X * SIGMA_X) + dy * dy / (SIGMA_Y * SIGMA_Y)) + (if (hit) HIT_BONUS else 0f)

    /**
     * Index of the best candidate given each one's [spatial] score and [lm] log weight
     * (NaN = no opinion → index 0, the hit key; all -Infinity = nothing known → index 0).
     */
    fun pick(spatial: FloatArray, lm: FloatArray): Int {
        if (lm.size != spatial.size || spatial.size < 2) return 0
        var top = Float.NEGATIVE_INFINITY
        for (v in lm) { if (v.isNaN()) return 0; if (v > top) top = v }
        if (top == Float.NEGATIVE_INFINITY) return 0
        var best = 0; var bestScore = Float.NEGATIVE_INFINITY
        for (i in spatial.indices) {
            val score = spatial[i] + LM_WEIGHT * max(lm[i] - top, -LM_FLOOR)
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }
}
