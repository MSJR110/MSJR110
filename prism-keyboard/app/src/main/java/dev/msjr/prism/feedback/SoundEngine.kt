package dev.msjr.prism.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Key click sounds, synthesised on the device (no sound files shipped).
 * Each sound has 3 slightly different variants so fast typing doesn't sound robotic.
 */
class SoundEngine(private val ctx: Context) {

    enum class Kind { KEY, DELETE, MOD }

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, IntArray>()
    private var pack = "off"
    private var volume = 0.55f
    private var respectSilent = true
    private var worker: Thread? = null

    fun configure(pack: String, volumePct: Int, respectSilent: Boolean) {
        this.volume = volumePct.coerceIn(0, 100) / 100f
        this.respectSilent = respectSilent
        if (pack == this.pack) return
        this.pack = pack
        release()
        if (pack == "off" || pack == "system") return
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
        pool = p
        val myPack = pack
        worker = Thread {
            try {
                val dir = File(ctx.cacheDir, "sounds").apply { mkdirs() }
                for (kind in Kind.values()) {
                    val arr = IntArray(3)
                    for (v in 0 until 3) {
                        val f = File(dir, "v1_${myPack}_${kind.name}_$v.wav")
                        if (!f.exists() || f.length() < 100) writeWav(f, synth(myPack, kind, v))
                        arr[v] = p.load(f.absolutePath, 1)
                    }
                    synchronized(ids) { if (this.pool === p) ids[kind] = arr }
                }
            } catch (_: Throwable) { }
        }.apply { priority = Thread.MIN_PRIORITY; start() }
    }

    private var ringerNormal = true
    private var ringerCheckedAt = 0L

    fun play(kind: Kind) {
        if (pack == "off" || volume <= 0f) return
        if (respectSilent) {
            // asking the system for the ringer mode on every key is a cross-process call; once a second is plenty
            val now = android.os.SystemClock.uptimeMillis()
            if (now - ringerCheckedAt > 1000) {
                ringerNormal = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
                ringerCheckedAt = now
            }
            if (!ringerNormal) return
        }
        if (pack == "system") {
            val fx = when (kind) {
                Kind.KEY -> AudioManager.FX_KEYPRESS_STANDARD
                Kind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Kind.MOD -> AudioManager.FX_KEYPRESS_SPACEBAR
            }
            audio.playSoundEffect(fx, volume)
            return
        }
        val p = pool ?: return
        val arr = synchronized(ids) { ids[kind] } ?: return
        val id = arr[Random.nextInt(arr.size)]
        if (id != 0) p.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        synchronized(ids) { ids.clear() }
        pool?.release()
        pool = null
    }

    // ------------------------------------------------------------------ synthesis

    private val sr = 48000

    private fun synth(pack: String, kind: Kind, variant: Int): FloatArray {
        val rnd = Random(pack.hashCode() * 31 + kind.ordinal * 7 + variant)
        val detune = 1f + (variant - 1) * 0.035f
        return when (pack) {
            "glass" -> glass(kind, detune, rnd)
            "mech" -> mech(kind, detune, rnd)
            else -> soft(kind, detune, rnd)
        }
    }

    /** Soft "tock" — rounded, woody, close to what people love on phones with great keyboards. */
    private fun soft(kind: Kind, dt: Float, rnd: Random): FloatArray {
        val n = (sr * 0.05).toInt()
        val out = FloatArray(n)
        val (f1, f2, fn) = when (kind) {
            Kind.KEY -> Triple(1900f, 820f, 3200f)
            Kind.DELETE -> Triple(1550f, 690f, 2700f)
            Kind.MOD -> Triple(1250f, 520f, 2300f)
        }
        val tau1 = if (kind == Kind.MOD) 0.0034f else 0.0026f
        val tau2 = if (kind == Kind.MOD) 0.0068f else 0.0050f
        addSine(out, f1 * dt, tau1, 0.55f)
        addSine(out, f2 * dt, tau2, 0.38f)
        addNoise(out, rnd, fn * dt, 1600f, 0.0009f, 0.9f)
        return finish(out, 0.0003f)
    }

    /** Crystalline "tink" with inharmonic partials. */
    private fun glass(kind: Kind, dt: Float, rnd: Random): FloatArray {
        val n = (sr * 0.09).toInt()
        val out = FloatArray(n)
        val base = when (kind) { Kind.KEY -> 2650f; Kind.DELETE -> 2250f; Kind.MOD -> 1850f } * dt
        addSine(out, base, 0.016f, 0.42f)
        addSine(out, base * 1.51f, 0.011f, 0.26f)
        addSine(out, base * 2.37f, 0.007f, 0.16f)
        addNoise(out, rnd, base * 1.8f, 2500f, 0.0005f, 0.35f)
        return finish(out, 0.0002f)
    }

    /** Mechanical switch: sharp click then a soft bottom-out. */
    private fun mech(kind: Kind, dt: Float, rnd: Random): FloatArray {
        val n = (sr * 0.06).toInt()
        val out = FloatArray(n)
        val click = when (kind) { Kind.KEY -> 4200f; Kind.DELETE -> 3600f; Kind.MOD -> 3000f } * dt
        addNoise(out, rnd, click, 2200f, 0.0006f, 1.0f)
        addSine(out, 2300f * dt, 0.003f, 0.35f)
        val thock = when (kind) { Kind.KEY -> 420f; Kind.DELETE -> 380f; Kind.MOD -> 300f } * dt
        addSine(out, thock, 0.010f, 0.35f, offset = (sr * 0.012).toInt())
        addNoise(out, rnd, 1800f * dt, 1500f, 0.0012f, 0.35f, offset = (sr * 0.012).toInt())
        return finish(out, 0.0002f)
    }

    private fun addSine(out: FloatArray, freq: Float, tau: Float, amp: Float, offset: Int = 0) {
        val w = 2.0 * PI * freq / sr
        for (i in offset until out.size) {
            val t = (i - offset).toFloat() / sr
            out[i] += (amp * exp(-t / tau) * sin(w * (i - offset))).toFloat()
        }
    }

    /** Band-passed noise burst through a 2-pole resonator. */
    private fun addNoise(out: FloatArray, rnd: Random, freq: Float, bw: Float, tau: Float, amp: Float, offset: Int = 0) {
        val r = exp(-PI * bw / sr)
        val a1 = -2.0 * r * kotlin.math.cos(2.0 * PI * freq / sr)
        val a2 = r * r
        var y1 = 0.0; var y2 = 0.0
        val g = (1 - r) * 2.0
        for (i in offset until out.size) {
            val t = (i - offset).toFloat() / sr
            val x = (rnd.nextFloat() * 2f - 1f) * exp(-t / tau)
            val y = g * x - a1 * y1 - a2 * y2
            y2 = y1; y1 = y
            out[i] += (amp * y * 4.0).toFloat()
        }
    }

    private fun finish(out: FloatArray, attack: Float): FloatArray {
        val atk = max(1, (attack * sr).toInt())
        for (i in 0 until minOf(atk, out.size)) out[i] *= i.toFloat() / atk
        // fade out last 15%
        val fade = (out.size * 0.15f).toInt()
        for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
        var peak = 0f
        for (v in out) peak = max(peak, kotlin.math.abs(v))
        if (peak > 0f) { val k = 0.85f / peak; for (i in out.indices) out[i] *= k }
        return out
    }

    private fun writeWav(f: File, data: FloatArray) {
        val bytes = data.size * 2
        val bb = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + bytes); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(bytes)
        for (v in data) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
    }
}
