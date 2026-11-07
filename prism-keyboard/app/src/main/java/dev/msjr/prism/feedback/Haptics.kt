package dev.msjr.prism.feedback

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Crisp, short haptics tuned to feel like a "tap" rather than a buzz.
 * Uses composition primitives when the vibrator supports them (S24 does), falls back gracefully.
 */
class Haptics(ctx: Context) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) { null }

    // vibrate() is a call into the system that can take a few ms; keep it off the UI thread
    private val thread = HandlerThread("prism-haptics", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)

    private var strength = 0.55f
    private var keyEffect: VibrationEffect? = null
    private var modEffect: VibrationEffect? = null
    private var tickEffect: VibrationEffect? = null
    private var heavyEffect: VibrationEffect? = null

    fun configure(level: Int) {
        strength = level.coerceIn(0, 100) / 100f
        if (strength <= 0f || vibrator?.hasVibrator() != true) {
            keyEffect = null; modEffect = null; tickEffect = null; heavyEffect = null
            return
        }
        // iPhone-like: every key gets the same short, light, crisp tap (a "tick", never a buzz)
        val strong = strength > 0.8f
        keyEffect = build(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f + 0.5f * strength, 4 + (strength * 6).toInt(),
            if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
        modEffect = keyEffect
        tickEffect = build(VibrationEffect.Composition.PRIMITIVE_TICK, 0.25f + 0.35f * strength, 3 + (strength * 4).toInt(),
            VibrationEffect.EFFECT_TICK)
        heavyEffect = build(VibrationEffect.Composition.PRIMITIVE_THUD, 0.4f + 0.6f * strength, 14 + (strength * 20).toInt(),
            VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    private fun build(primitive: Int, scale: Float, fallbackMs: Int, predefined: Int): VibrationEffect? {
        val v = vibrator ?: return null
        try {
            if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(primitive)) {
                return VibrationEffect.startComposition().addPrimitive(primitive, scale.coerceIn(0f, 1f)).compose()
            }
            // Samsung & co. map these to their own carefully tuned haptic patterns
            if (Build.VERSION.SDK_INT >= 29) return VibrationEffect.createPredefined(predefined)
        } catch (_: Throwable) { }
        val amp = if (v.hasAmplitudeControl()) (40 + 215 * scale).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        return VibrationEffect.createOneShot(fallbackMs.toLong(), amp)
    }

    private fun play(e: VibrationEffect?) {
        if (e == null) return
        worker.post { try { vibrator?.vibrate(e) } catch (_: Throwable) { } }
    }

    fun shutdown() { thread.quitSafely() }

    fun key() = play(keyEffect)
    fun modifier() = play(modEffect)
    fun tick() = play(tickEffect)
    fun heavy() = play(heavyEffect)
}
