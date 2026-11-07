package dev.msjr.prism.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Crisp vector icons drawn straight onto the canvas (no bitmaps, no resource lookups). */
object Icons {
    private val path = Path()
    private val r = RectF()

    /** All icons are drawn centered at (cx, cy) inside a box of size [s]. Paint must be STROKE. */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, filled: Boolean, locked: Boolean) {
        path.reset()
        val w = s * 0.5f; val h = s * 0.52f
        val top = cy - h / 2f - (if (locked) s * 0.06f else 0f)
        path.moveTo(cx, top)
        path.lineTo(cx + w / 2f, top + h * 0.5f)
        path.lineTo(cx + w * 0.22f, top + h * 0.5f)
        path.lineTo(cx + w * 0.22f, top + h)
        path.lineTo(cx - w * 0.22f, top + h)
        path.lineTo(cx - w * 0.22f, top + h * 0.5f)
        path.lineTo(cx - w / 2f, top + h * 0.5f)
        path.close()
        val old = p.style
        p.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(path, p)
        p.style = old
        if (locked) {
            c.drawLine(cx - w * 0.3f, top + h + s * 0.12f, cx + w * 0.3f, top + h + s * 0.12f, p)
        }
    }

    fun delete(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, rtl: Boolean) {
        path.reset()
        val w = s * 0.62f; val h = s * 0.42f
        val l = cx - w / 2f; val t = cy - h / 2f
        val dir = if (rtl) -1f else 1f
        // body pointing left (or right for rtl)
        if (!rtl) {
            path.moveTo(l, cy)
            path.lineTo(l + w * 0.3f, t)
            path.lineTo(l + w, t)
            path.lineTo(l + w, t + h)
            path.lineTo(l + w * 0.3f, t + h)
        } else {
            path.moveTo(l + w, cy)
            path.lineTo(l + w * 0.7f, t)
            path.lineTo(l, t)
            path.lineTo(l, t + h)
            path.lineTo(l + w * 0.7f, t + h)
        }
        path.close()
        c.drawPath(path, p)
        val xc = cx + dir * w * 0.12f
        val x = h * 0.2f
        c.drawLine(xc - x, cy - x, xc + x, cy + x, p)
        c.drawLine(xc - x, cy + x, xc + x, cy - x, p)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, rtl: Boolean) {
        val w = s * 0.5f; val h = s * 0.32f
        val dir = if (rtl) -1f else 1f
        val right = cx + dir * w / 2f
        val left = cx - dir * w / 2f
        path.reset()
        path.moveTo(right, cy - h)
        path.lineTo(right, cy)
        path.lineTo(left, cy)
        c.drawPath(path, p)
        val a = s * 0.12f
        c.drawLine(left, cy, left + dir * a, cy - a, p)
        c.drawLine(left, cy, left + dir * a, cy + a, p)
    }

    fun search(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rad = s * 0.17f
        c.drawCircle(cx - s * 0.05f, cy - s * 0.05f, rad, p)
        c.drawLine(cx + rad * 0.65f, cy + rad * 0.65f, cx + s * 0.22f, cy + s * 0.22f, p)
    }

    fun send(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, rtl: Boolean) {
        val d = if (rtl) -1f else 1f
        path.reset()
        path.moveTo(cx - d * s * 0.26f, cy - s * 0.22f)
        path.lineTo(cx + d * s * 0.28f, cy)
        path.lineTo(cx - d * s * 0.26f, cy + s * 0.22f)
        path.lineTo(cx - d * s * 0.14f, cy)
        path.close()
        c.drawPath(path, p)
        c.drawLine(cx - d * s * 0.14f, cy, cx + d * s * 0.05f, cy, p)
    }

    fun globe(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rad = s * 0.24f
        c.drawCircle(cx, cy, rad, p)
        r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad)
        c.drawOval(r, p)
        c.drawLine(cx - rad, cy, cx + rad, cy, p)
        c.drawLine(cx, cy - rad, cx, cy + rad, p)
    }

    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rad = s * 0.24f
        c.drawCircle(cx, cy, rad, p)
        val e = rad * 0.36f
        val old = p.style
        p.style = Paint.Style.FILL
        c.drawCircle(cx - e, cy - rad * 0.25f, rad * 0.09f, p)
        c.drawCircle(cx + e, cy - rad * 0.25f, rad * 0.09f, p)
        p.style = old
        r.set(cx - rad * 0.5f, cy - rad * 0.25f, cx + rad * 0.5f, cy + rad * 0.55f)
        c.drawArc(r, 20f, 140f, false, p)
    }

    fun clipboard(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.36f; val h = s * 0.46f
        r.set(cx - w / 2, cy - h / 2 + s * 0.03f, cx + w / 2, cy + h / 2)
        c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
        r.set(cx - w * 0.25f, cy - h / 2 - s * 0.02f, cx + w * 0.25f, cy - h / 2 + s * 0.07f)
        c.drawRoundRect(r, s * 0.03f, s * 0.03f, p)
        c.drawLine(cx - w * 0.25f, cy, cx + w * 0.25f, cy, p)
        c.drawLine(cx - w * 0.25f, cy + h * 0.2f, cx + w * 0.1f, cy + h * 0.2f, p)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rad = s * 0.15f
        c.drawCircle(cx, cy, rad * 0.55f, p)
        c.drawCircle(cx, cy, rad * 1.15f, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = cx + (Math.cos(a) * rad * 1.15f).toFloat()
            val y1 = cy + (Math.sin(a) * rad * 1.15f).toFloat()
            val x2 = cx + (Math.cos(a) * rad * 1.6f).toFloat()
            val y2 = cy + (Math.sin(a) * rad * 1.6f).toFloat()
            c.drawLine(x1, y1, x2, y2, p)
        }
    }

    fun oneHand(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.42f; val h = s * 0.3f
        r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
        c.drawLine(cx + w * 0.1f, cy - h / 2, cx + w * 0.1f, cy + h / 2, p)
    }

    fun chevron(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, left: Boolean) {
        val d = if (left) 1f else -1f
        val a = s * 0.12f
        c.drawLine(cx + d * a * 0.5f, cy - a, cx - d * a * 0.5f, cy, p)
        c.drawLine(cx - d * a * 0.5f, cy, cx + d * a * 0.5f, cy + a, p)
    }

    fun expand(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val a = s * 0.16f
        c.drawLine(cx - a, cy, cx + a, cy, p)
        c.drawLine(cx - a, cy, cx - a * 0.5f, cy - a * 0.5f, p)
        c.drawLine(cx - a, cy, cx - a * 0.5f, cy + a * 0.5f, p)
        c.drawLine(cx + a, cy, cx + a * 0.5f, cy - a * 0.5f, p)
        c.drawLine(cx + a, cy, cx + a * 0.5f, cy + a * 0.5f, p)
    }

    fun pin(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rad = s * 0.1f
        c.drawCircle(cx, cy - rad * 0.6f, rad, p)
        c.drawLine(cx, cy + rad * 0.4f, cx, cy + rad * 2.2f, p)
    }

    fun close(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val a = s * 0.12f
        c.drawLine(cx - a, cy - a, cx + a, cy + a, p)
        c.drawLine(cx - a, cy + a, cx + a, cy - a, p)
    }

    fun trash(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.3f; val h = s * 0.34f
        r.set(cx - w / 2, cy - h / 2 + s * 0.04f, cx + w / 2, cy + h / 2 + s * 0.04f)
        c.drawRoundRect(r, s * 0.03f, s * 0.03f, p)
        c.drawLine(cx - w * 0.7f, cy - h / 2, cx + w * 0.7f, cy - h / 2, p)
        c.drawLine(cx - w * 0.2f, cy - h / 2 - s * 0.05f, cx + w * 0.2f, cy - h / 2 - s * 0.05f, p)
    }
}
