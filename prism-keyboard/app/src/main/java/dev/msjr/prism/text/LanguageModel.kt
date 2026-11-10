package dev.msjr.prism.text

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.CRC32

/**
 * A compact word-pair model ("after X, people usually write Y") built offline from a large text corpus
 * (see the BuildLanguageModel tool in the tests). Word ids are indices into the matching [Dictionary];
 * a checksum of the dictionary's keys guards against using a model built for another word list.
 *
 * Probabilities are interpolated absolute discounting:
 *   P(w | a) = max(c(a,w) − D, 0) / c(a) + λ(a)·P(w)
 * Only the most useful pairs are kept; any other word after `a` gets λ(a)·P(w).
 * Everything is stored as quantized negative natural logs in bytes.
 */
class LanguageModel private constructor(
    val vocabSize: Int,
    private val uni: ByteArray,       // −ln P(w) × UNI_SCALE
    private val backoff: ByteArray,   // −ln λ(a) × PAIR_SCALE, per context (vocabSize + 1 entries; last = sentence start)
    private val offsets: IntArray,    // contexts → [offsets[a], offsets[a+1]) in next/prob
    private val next: ShortArray,     // follower ids (unsigned), most likely first
    private val prob: ByteArray,      // −ln P(w | a) × PAIR_SCALE
) {
    /** Context id for "start of a sentence". */
    val bos: Int get() = vocabSize

    val pairCount: Int get() = next.size

    /** ln P(w) for a word id. */
    fun lnUni(id: Int): Float = -(uni[id].toInt() and 0xff) / UNI_SCALE

    fun followerCount(ctx: Int): Int = if (ctx in 0..vocabSize) offsets[ctx + 1] - offsets[ctx] else 0

    /** The i-th most likely follower of [ctx] (id) and its ln P(w | ctx). */
    fun followerId(ctx: Int, i: Int): Int = next[offsets[ctx] + i].toInt() and 0xffff
    fun followerLnP(ctx: Int, i: Int): Float = -(prob[offsets[ctx] + i].toInt() and 0xff) / PAIR_SCALE

    /** ln λ(ctx): how much of an unlisted word's own likelihood carries over after this context. */
    fun lnBackoff(ctx: Int): Float = -(backoff[ctx].toInt() and 0xff) / PAIR_SCALE

    /** ln P(w | ctx) − ln P(w): how much more (or less) likely the word is in this context. 0 = no knowledge. */
    fun bonus(ctx: Int, id: Int): Float {
        if (ctx !in 0..vocabSize || id !in 0 until vocabSize) return 0f
        val n = followerCount(ctx)
        if (n == 0) return 0f
        val base = offsets[ctx]
        val sid = id.toShort()
        for (i in 0 until n) if (next[base + i] == sid) return -(prob[base + i].toInt() and 0xff) / PAIR_SCALE - lnUni(id)
        return lnBackoff(ctx)
    }

    companion object {
        const val MAGIC = 0x50524c4d // "PRLM"
        const val VERSION = 1
        const val UNI_SCALE = 10f
        const val PAIR_SCALE = 16f

        fun checksum(keys: Array<String>): Int {
            val crc = CRC32()
            for (k in keys) { crc.update(k.toByteArray(Charsets.UTF_8)); crc.update(10) }
            return crc.value.toInt()
        }

        /** Null if the file doesn't match [dict] (built for another word list). */
        fun load(input: InputStream, dict: Dictionary): LanguageModel? {
            DataInputStream(BufferedInputStream(input, 1 shl 16)).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
                val v = d.readInt()
                val crc = d.readInt()
                if (v != dict.size || crc != checksum(dict.keys)) return null
                val uni = ByteArray(v).also { d.readFully(it) }
                val backoff = ByteArray(v + 1).also { d.readFully(it) }
                val offsets = IntArray(v + 2) { d.readInt() }
                val e = offsets[v + 1]
                val nb = ByteArray(e * 2).also { d.readFully(it) }
                val next = ShortArray(e) { (((nb[it * 2].toInt() and 0xff) shl 8) or (nb[it * 2 + 1].toInt() and 0xff)).toShort() }
                val prob = ByteArray(e).also { d.readFully(it) }
                return LanguageModel(v, uni, backoff, offsets, next, prob)
            }
        }
    }
}
