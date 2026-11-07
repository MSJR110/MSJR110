package dev.msjr.prism.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
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
 *
 * Like the iPhone there are three different sounds: letters, delete, and the "modifier" keys
 * (space, return, shift, page switches). Each has 3 barely different variants so fast typing
 * doesn't sound like a machine gun.
 *
 * Clicks go through [ClickPlayer]'s low-latency stream; SoundPool is only the fallback for phones
 * that don't offer one.
 */
class SoundEngine(private val ctx: Context) {

    enum class Kind { KEY, DELETE, MOD }

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pack = "off"
    private var volume = 0.55f
    private var respectSilent = true
    private var generation = 0

    // low-latency path
    @Volatile private var player: ClickPlayer? = null
    @Volatile private var clips: Map<Kind, Array<FloatArray>>? = null

    // fallback path
    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<Kind, IntArray>()

    // the ringer mode is pushed to us instead of asking the system on every key
    @Volatile private var ringerNormal = true
    private var ringerReceiver: BroadcastReceiver? = null

    init {
        ringerNormal = readRinger()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { ringerNormal = readRinger() }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
            }
            ringerReceiver = r
        } catch (_: Throwable) { }
    }

    private fun readRinger(): Boolean = try { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL } catch (_: Throwable) { true }

    fun configure(pack: String, volumePct: Int, respectSilent: Boolean) {
        this.volume = volumePct.coerceIn(0, 100) / 100f
        this.respectSilent = respectSilent
        if (pack == this.pack) return
        this.pack = pack
        releaseAudio()
        if (pack == "off" || pack == "system") return
        val gen = ++generation
        val myPack = pack
        Thread {
            try {
                val p = ClickPlayer.create(ctx)
                if (p != null) {
                    val rate = p.sampleRate
                    val made = HashMap<Kind, Array<FloatArray>>()
                    for (k in Kind.values()) made[k] = Array(3) { v -> synth(myPack, k, v, rate) }
                    val current = synchronized(this) {
                        if (gen == generation) { clips = made; player = p; true } else { p.release(); false }
                    }
                    // the keyboard is usually already open by now (warmUp() skips silent mode)
                    if (current) warmUp()
                } else {
                    loadSoundPool(myPack, gen)
                }
            } catch (_: Throwable) { }
        }.apply { priority = Thread.NORM_PRIORITY; name = "prism-sound-init"; start() }
    }

    private fun loadSoundPool(pack: String, gen: Int) {
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
        synchronized(this) { if (gen != generation) { p.release(); return }; pool = p }
        val dir = File(ctx.cacheDir, "sounds").apply { mkdirs() }
        for (kind in Kind.values()) {
            val arr = IntArray(3)
            for (v in 0 until 3) {
                val f = File(dir, "v3_${pack}_${kind.name}_$v.wav")
                if (!f.exists() || f.length() < 100) writeWav(f, synth(pack, kind, v, SR_FALLBACK), SR_FALLBACK)
                arr[v] = p.load(f.absolutePath, 1)
            }
            synchronized(ids) { if (pool === p) ids[kind] = arr }
        }
    }

    /** Keyboard opened: wake the audio path up so the first key isn't late. */
    fun warmUp() {
        if (pack == "off" || pack == "system" || volume <= 0f) return
        if (respectSilent && !ringerNormal) return
        player?.warmUp()
    }

    /** Keyboard hidden: let the audio path rest. */
    fun sleep() { player?.sleep() }

    fun play(kind: Kind) {
        if (pack == "off" || volume <= 0f) return
        if (respectSilent && !ringerNormal) return
        if (pack == "system") {
            val fx = when (kind) {
                Kind.KEY -> AudioManager.FX_KEYPRESS_STANDARD
                Kind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Kind.MOD -> AudioManager.FX_KEYPRESS_SPACEBAR
            }
            audio.playSoundEffect(fx, volume)
            return
        }
        val pl = player
        val cl = clips
        if (pl != null && cl != null) {
            val variants = cl[kind] ?: return
            pl.play(variants[Random.nextInt(variants.size)], volume)
            return
        }
        val p = pool ?: return
        val arr = synchronized(ids) { ids[kind] } ?: return
        val id = arr[Random.nextInt(arr.size)]
        if (id != 0) p.play(id, volume, volume, 1, 0, 1f)
    }

    private fun releaseAudio() {
        synchronized(this) {
            generation++
            player?.release(); player = null; clips = null
            synchronized(ids) { ids.clear() }
            pool?.release(); pool = null
        }
    }

    fun release() {
        releaseAudio()
        ringerReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Throwable) { } }
        ringerReceiver = null
    }

    // ------------------------------------------------------------------ synthesis

    private fun synth(pack: String, kind: Kind, variant: Int, sr: Int): FloatArray {
        val rnd = Random(pack.hashCode() * 31 + kind.ordinal * 7 + variant)
        val s = Synth(sr)
        return when (pack) {
            "iphone" -> s.iphone(kind, 1f + (variant - 1) * 0.012f, rnd)
            "click" -> s.click(kind, 1f + (variant - 1) * 0.035f, rnd)
            "glass" -> s.glass(kind, 1f + (variant - 1) * 0.035f, rnd)
            "mech" -> s.mech(kind, 1f + (variant - 1) * 0.035f, rnd)
            else -> s.soft(kind, 1f + (variant - 1) * 0.035f, rnd)
        }
    }

    private class Synth(val sr: Int) {

        /**
         * The iPhone keyboard's "tock": a tiny plastic shell struck by a fingertip. A very short bright
         * transient (the "t") rings a few stiff, fast-dying resonances (the "ock"); nothing lingers.
         * Delete is the same strike on a slightly bigger, lower shell; space/return/shift lower and
         * rounder still, with a little more body underneath.
         */
        fun iphone(kind: Kind, dt: Float, rnd: Random): FloatArray {
            val len = when (kind) { Kind.KEY -> 0.030; Kind.DELETE -> 0.034; Kind.MOD -> 0.040 }
            val out = FloatArray((sr * len).toInt())
            when (kind) {
                Kind.KEY -> {
                    addNoise(out, rnd, 4800f * dt, 4200f, 0.00032f, 0.85f)
                    addSine(out, 1700f * dt, 0.0026f, 0.55f)
                    addSine(out, 2950f * dt, 0.0016f, 0.42f)
                    addSine(out, 4700f * dt, 0.0009f, 0.24f)
                    addSine(out, 640f * dt, 0.0030f, 0.12f)
                }
                Kind.DELETE -> {
                    addNoise(out, rnd, 3900f * dt, 3600f, 0.00036f, 0.75f)
                    addSine(out, 1320f * dt, 0.0030f, 0.55f)
                    addSine(out, 2380f * dt, 0.0019f, 0.40f)
                    addSine(out, 3950f * dt, 0.0010f, 0.20f)
                    addSine(out, 540f * dt, 0.0036f, 0.18f)
                }
                Kind.MOD -> {
                    addNoise(out, rnd, 3200f * dt, 3000f, 0.00040f, 0.62f)
                    addSine(out, 1020f * dt, 0.0034f, 0.55f)
                    addSine(out, 1880f * dt, 0.0022f, 0.36f)
                    addSine(out, 3150f * dt, 0.0012f, 0.18f)
                    addSine(out, 390f * dt, 0.0052f, 0.30f)
                }
            }
            return finish(out, 0.00015f)
        }

        /** Soft "tock" — rounded, woody. */
        fun soft(kind: Kind, dt: Float, rnd: Random): FloatArray {
            val out = FloatArray((sr * 0.05).toInt())
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

        /** Short, dry, crisp click: a very fast bright tick with a tiny body. */
        fun click(kind: Kind, dt: Float, rnd: Random): FloatArray {
            val out = FloatArray((sr * 0.035).toInt())
            val (tick, body, low) = when (kind) {
                Kind.KEY -> Triple(3400f, 1750f, 640f)
                Kind.DELETE -> Triple(2800f, 1450f, 540f)
                Kind.MOD -> Triple(2300f, 1150f, 400f)
            }
            addNoise(out, rnd, tick * dt, 2600f, 0.00045f, 1.0f)
            addSine(out, body * dt, 0.0022f, 0.42f)
            addSine(out, low * dt, if (kind == Kind.MOD) 0.0050f else 0.0035f, if (kind == Kind.MOD) 0.30f else 0.18f)
            return finish(out, 0.0001f)
        }

        /** Crystalline "tink" with inharmonic partials. */
        fun glass(kind: Kind, dt: Float, rnd: Random): FloatArray {
            val out = FloatArray((sr * 0.09).toInt())
            val base = when (kind) { Kind.KEY -> 2650f; Kind.DELETE -> 2250f; Kind.MOD -> 1850f } * dt
            addSine(out, base, 0.016f, 0.42f)
            addSine(out, base * 1.51f, 0.011f, 0.26f)
            addSine(out, base * 2.37f, 0.007f, 0.16f)
            addNoise(out, rnd, base * 1.8f, 2500f, 0.0005f, 0.35f)
            return finish(out, 0.0002f)
        }

        /** Mechanical switch: sharp click then a soft bottom-out. */
        fun mech(kind: Kind, dt: Float, rnd: Random): FloatArray {
            val out = FloatArray((sr * 0.06).toInt())
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
            val f = freq.coerceAtMost(sr * 0.45f)
            val r = exp(-PI * bw / sr)
            val a1 = -2.0 * r * kotlin.math.cos(2.0 * PI * f / sr)
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
            // fade out the last 20% so the tail never clicks
            val fade = (out.size * 0.2f).toInt()
            for (i in 0 until fade) out[out.size - 1 - i] *= i.toFloat() / fade
            var peak = 0f
            for (v in out) peak = max(peak, kotlin.math.abs(v))
            if (peak > 0f) { val k = 0.85f / peak; for (i in out.indices) out[i] *= k }
            return out
        }
    }

    private fun writeWav(f: File, data: FloatArray, sr: Int) {
        val bytes = data.size * 2
        val bb = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + bytes); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(bytes)
        for (v in data) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private companion object {
        const val SR_FALLBACK = 48000
    }
}
