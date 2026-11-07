package dev.msjr.prism.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Plays short key clicks with the lowest latency the phone offers.
 *
 * SoundPool goes through the normal mixer (20 ms buffers on most phones) and the audio hardware
 * falls asleep between keys, so the first click after a pause comes late. Here one small
 * low-latency stream (the "fast" mixer: ~4 ms buffers) is kept running while you type and clicks are
 * mixed into it directly. A few seconds after the last key the stream pauses, so nothing runs while
 * the keyboard just sits there.
 */
class ClickPlayer private constructor(
    private val track: AudioTrack,
    val sampleRate: Int,
    private val burst: Int,
) {
    private class Voice(val data: FloatArray, val gain: Float) { var pos = 0 }

    private val lock = Object()
    private val pending = ArrayList<Voice>(8)        // guarded by lock
    private var awakeUntil = 0L                       // guarded by lock (uptime ms)
    @Volatile private var running = true
    private val active = ArrayList<Voice>(8)          // audio thread only

    private val thread = Thread({ loop() }, "prism-click").apply { start() }

    /** Queue a click. Cheap enough for the touch path: no allocation of audio, no system calls. */
    fun play(data: FloatArray, gain: Float) {
        synchronized(lock) {
            if (pending.size < 16) pending += Voice(data, gain)
            awakeUntil = SystemClock.uptimeMillis() + IDLE_MS
            lock.notifyAll()
        }
    }

    /** Get the stream going now (keyboard just opened) so the very first key is instant too. */
    fun warmUp() {
        synchronized(lock) {
            awakeUntil = SystemClock.uptimeMillis() + IDLE_MS
            lock.notifyAll()
        }
    }

    /** Let the stream pause as soon as nothing is playing (keyboard hidden). */
    fun sleep() {
        synchronized(lock) { awakeUntil = 0L; lock.notifyAll() }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) { }
        val mix = FloatArray(burst)
        val out = ShortArray(burst)
        var playing = false
        var underruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
        try {
            while (running) {
                synchronized(lock) {
                    while (running && pending.isEmpty() && active.isEmpty() && SystemClock.uptimeMillis() > awakeUntil) {
                        if (playing) {
                            try { track.pause(); track.flush() } catch (_: Throwable) { }
                            playing = false
                        }
                        lock.wait()
                    }
                    if (pending.isNotEmpty()) { active.addAll(pending); pending.clear() }
                }
                if (!running) break
                if (!playing) {
                    track.play()
                    playing = true
                }

                java.util.Arrays.fill(mix, 0f)
                val it = active.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(burst, v.data.size - v.pos)
                    val g = v.gain
                    val d = v.data
                    var p = v.pos
                    for (i in 0 until n) { mix[i] += d[p] * g; p++ }
                    v.pos = p
                    if (p >= d.size) it.remove()
                }
                for (i in 0 until burst) {
                    // soft limit so several overlapping clicks never crackle
                    var s = mix[i]
                    if (s > 0.9f) s = 0.9f + (s - 0.9f) * 0.25f else if (s < -0.9f) s = -0.9f + (s + 0.9f) * 0.25f
                    out[i] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                track.write(out, 0, burst)

                // the stream glitched: give it one more burst of headroom (like Oboe's latency tuner)
                if (Build.VERSION.SDK_INT >= 24) {
                    val u = track.underrunCount
                    if (u > underruns) {
                        underruns = u
                        val cur = track.bufferSizeInFrames
                        if (cur + burst <= track.bufferCapacityInFrames) track.bufferSizeInFrames = cur + burst
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "click stream stopped", t)
        } finally {
            try { track.pause(); track.flush() } catch (_: Throwable) { }
            try { track.release() } catch (_: Throwable) { }
        }
    }

    companion object {
        private const val TAG = "PrismClick"
        private const val IDLE_MS = 6000L

        fun create(ctx: Context): ClickPlayer? {
            if (Build.VERSION.SDK_INT < 26) return null
            return try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
                val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 192
                val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(maxOf(minBytes, burst * 2 * 2))
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return null }
                // two bursts queued: as short as it gets without glitching; grows itself if needed
                try { track.bufferSizeInFrames = burst * 2 } catch (_: Throwable) { }
                ClickPlayer(track, rate, burst)
            } catch (t: Throwable) {
                Log.w(TAG, "low-latency stream unavailable", t)
                null
            }
        }
    }
}
