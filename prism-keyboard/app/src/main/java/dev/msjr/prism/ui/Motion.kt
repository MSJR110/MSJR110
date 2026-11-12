package dev.msjr.prism.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A damped spring: everything that moves in Prism is one of these, so motion can be interrupted
 * at any moment and always continues from where (and how fast) it was.
 */
class Spring(start: Float = 0f, var stiffness: Float = 520f, var damping: Float = 1f) {
    var value = start
    var target = start
    var velocity = 0f

    val moving: Boolean get() = value != target || velocity != 0f

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    /** Advance by [dt] seconds; true while still moving. */
    fun step(dt: Float, epsilon: Float = 0.002f): Boolean {
        if (!moving) return false
        val c = 2f * damping * sqrt(stiffness)
        var t = dt
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            t -= h
        }
        if (abs(value - target) < epsilon && abs(velocity) < epsilon * 12f) { value = target; velocity = 0f; return false }
        return true
    }
}

/**
 * Finger-driven scrolling with the iOS feel: exact while dragging, a long glide after a flick,
 * rubber-band past the ends and a spring back.
 */
class ScrollPhysics {
    var pos = 0f
        private set
    var max = 0f
    private var velocity = 0f
    private var dragging = false

    val moving: Boolean get() = !dragging && (abs(velocity) > 8f || pos < 0f || pos > max)

    fun jumpTo(p: Float) { pos = p.coerceIn(0f, max); velocity = 0f }

    fun startDrag() { dragging = true; velocity = 0f }

    fun dragBy(delta: Float) {
        // past the ends the content follows the finger less and less
        val over = if (pos < 0f) -pos else if (pos > max) pos - max else 0f
        val resist = if (over > 0f) 1f / (1f + over / 60f) else 1f
        pos += delta * resist
    }

    fun endDrag(v: Float) { dragging = false; velocity = v }

    private var animTarget = Float.NaN

    /** Glide to [p] (a tab was tapped). */
    fun animateTo(p: Float) { animTarget = p.coerceIn(0f, max); velocity = 0f; dragging = false }

    val animating: Boolean get() = !animTarget.isNaN()

    fun step(dt: Float): Boolean {
        if (dragging) return false
        if (!animTarget.isNaN()) {
            val d = animTarget - pos
            pos += d * min(1f, dt * 16f)
            if (abs(d) < 0.5f) { pos = animTarget; animTarget = Float.NaN }
            return true
        }
        if (pos < 0f || pos > max) {
            // critically damped pull back to the edge
            val edge = if (pos < 0f) 0f else max
            val k = 260f; val c = 2f * sqrt(k)
            var t = dt
            while (t > 0f) {
                val h = min(t, 1f / 240f)
                velocity += (-k * (pos - edge) - c * velocity) * h
                pos += velocity * h
                t -= h
            }
            if (abs(pos - edge) < 0.5f && abs(velocity) < 10f) { pos = edge; velocity = 0f; return false }
            return true
        }
        if (abs(velocity) <= 8f) { velocity = 0f; return false }
        pos += velocity * dt
        velocity *= exp(-dt * 2.6f)
        return true
    }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
