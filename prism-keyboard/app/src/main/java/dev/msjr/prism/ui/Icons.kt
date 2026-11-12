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

    // ------------------------------------------------------------------ toolbar & editing icons

    /** A plain arrow: [dx], [dy] is the direction it points (one of them 0). */
    fun arrow(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, dx: Int, dy: Int) {
        val a = s * 0.17f; val h = s * 0.11f
        val fx = dx.toFloat(); val fy = dy.toFloat()
        c.drawLine(cx - fx * a, cy - fy * a, cx + fx * a, cy + fy * a, p)
        // the head: two short strokes back from the tip
        val tx = cx + fx * a; val ty = cy + fy * a
        c.drawLine(tx, ty, tx - fx * h - fy * h, ty - fy * h - fx * h, p)
        c.drawLine(tx, ty, tx - fx * h + fy * h, ty - fy * h + fx * h, p)
    }

    /** Arrow to a wall: jump to the start / end of the line. */
    fun arrowToEdge(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, dx: Int) {
        arrow(c, cx - dx * s * 0.03f, cy, s, p, dx, 0)
        val x = cx + dx * s * 0.21f
        c.drawLine(x, cy - s * 0.13f, x, cy + s * 0.13f, p)
    }

    /** Text cursor between two little arrows: the editing panel. */
    fun editCursor(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s * 0.17f; val w = s * 0.06f
        c.drawLine(cx, cy - h, cx, cy + h, p)
        c.drawLine(cx - w, cy - h, cx + w, cy - h, p)
        c.drawLine(cx - w, cy + h, cx + w, cy + h, p)
        val a = s * 0.07f; val o = s * 0.19f
        c.drawLine(cx - o, cy, cx - o + a, cy - a, p); c.drawLine(cx - o, cy, cx - o + a, cy + a, p)
        c.drawLine(cx + o, cy, cx + o - a, cy - a, p); c.drawLine(cx + o, cy, cx + o - a, cy + a, p)
    }

    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.075f
        r.set(cx - w, cy - s * 0.2f, cx + w, cy + s * 0.04f)
        c.drawRoundRect(r, w, w, p)
        r.set(cx - s * 0.14f, cy - s * 0.16f, cx + s * 0.14f, cy + s * 0.12f)
        c.drawArc(r, 20f, 140f, false, p)
        c.drawLine(cx, cy + s * 0.12f, cx, cy + s * 0.2f, p)
    }

    fun check(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        c.drawLine(cx - s * 0.16f, cy, cx - s * 0.04f, cy + s * 0.12f, p)
        c.drawLine(cx - s * 0.04f, cy + s * 0.12f, cx + s * 0.18f, cy - s * 0.12f, p)
    }

    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val a = s * 0.14f
        c.drawLine(cx - a, cy - a * 0.45f, cx, cy + a * 0.55f, p)
        c.drawLine(cx, cy + a * 0.55f, cx + a, cy - a * 0.45f, p)
    }

    fun copy(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.24f; val h = s * 0.3f; val o = s * 0.05f
        r.set(cx - w / 2 + o, cy - h / 2 + o, cx + w / 2 + o, cy + h / 2 + o)
        c.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
        path.reset()
        path.moveTo(cx - w / 2 - o, cy + h / 2 - o * 1.6f)
        path.lineTo(cx - w / 2 - o, cy - h / 2 - o)
        path.lineTo(cx + w / 2 - o * 1.6f, cy - h / 2 - o)
        c.drawPath(path, p)
    }

    fun cut(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val rr = s * 0.06f
        c.drawCircle(cx - s * 0.1f, cy + s * 0.13f, rr, p)
        c.drawCircle(cx + s * 0.1f, cy + s * 0.13f, rr, p)
        c.drawLine(cx - s * 0.07f, cy + s * 0.08f, cx + s * 0.1f, cy - s * 0.2f, p)
        c.drawLine(cx + s * 0.07f, cy + s * 0.08f, cx - s * 0.1f, cy - s * 0.2f, p)
    }

    fun selectAll(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val a = s * 0.18f; val k = s * 0.07f
        // four corners of a frame and a filled block inside
        c.drawLine(cx - a, cy - a, cx - a + k, cy - a, p); c.drawLine(cx - a, cy - a, cx - a, cy - a + k, p)
        c.drawLine(cx + a, cy - a, cx + a - k, cy - a, p); c.drawLine(cx + a, cy - a, cx + a, cy - a + k, p)
        c.drawLine(cx - a, cy + a, cx - a + k, cy + a, p); c.drawLine(cx - a, cy + a, cx - a, cy + a - k, p)
        c.drawLine(cx + a, cy + a, cx + a - k, cy + a, p); c.drawLine(cx + a, cy + a, cx + a, cy + a - k, p)
        r.set(cx - a * 0.45f, cy - a * 0.45f, cx + a * 0.45f, cy + a * 0.45f)
        c.drawRoundRect(r, s * 0.02f, s * 0.02f, p)
    }

    /** Selection brackets around a cursor: start / stop selecting with the arrows. */
    fun select(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s * 0.16f; val o = s * 0.14f; val k = s * 0.05f
        c.drawLine(cx - o, cy - h, cx - o, cy + h, p)
        c.drawLine(cx - o, cy - h, cx - o + k, cy - h, p); c.drawLine(cx - o, cy + h, cx - o + k, cy + h, p)
        c.drawLine(cx + o, cy - h, cx + o, cy + h, p)
        c.drawLine(cx + o, cy - h, cx + o - k, cy - h, p); c.drawLine(cx + o, cy + h, cx + o - k, cy + h, p)
        c.drawLine(cx - k, cy, cx + k, cy, p)
    }

    /** A curved arrow turning back: undo (mirrored = redo). */
    fun undo(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, redo: Boolean) {
        val d = if (redo) -1f else 1f
        val rad = s * 0.14f
        r.set(cx - rad, cy - rad * 0.7f, cx + rad, cy + rad * 1.3f)
        if (redo) c.drawArc(r, 180f, 200f, false, p) else c.drawArc(r, 160f, 200f, false, p)
        val tx = cx - d * rad; val ty = cy + rad * 0.3f
        c.drawLine(tx, ty, tx - d * s * 0.02f, ty - s * 0.12f, p)
        c.drawLine(tx, ty, tx + d * s * 0.11f, ty - s * 0.035f, p)
    }

    /** The Prism mark: a small prism splitting a ray. */
    fun prism(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val a = s * 0.2f
        path.reset()
        path.moveTo(cx, cy - a)
        path.lineTo(cx + a, cy + a * 0.8f)
        path.lineTo(cx - a, cy + a * 0.8f)
        path.close()
        c.drawPath(path, p)
        c.drawLine(cx - a * 0.2f, cy + a * 0.15f, cx + a * 0.35f, cy + a * 0.15f, p)
    }
}
