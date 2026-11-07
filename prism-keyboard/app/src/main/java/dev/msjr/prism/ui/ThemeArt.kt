package dev.msjr.prism.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Paints a theme's backdrop artwork. Everything is seeded, so a theme always looks the same,
 * and sized relative to the canvas, so the keyboard and the small swatches in settings match.
 * Draw into a software (bitmap) canvas: the blurs need it.
 */
object ThemeArt {

    fun paint(c: Canvas, th: Theme, w: Int, h: Int, density: Float, strength: Float = 0.7f) {
        val W = w.toFloat(); val H = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, H, th.base[0], th.base[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        val boost = (0.55f + 0.45f * strength) * th.vivid
        val rnd = Random(th.id.hashCode())
        when (th.style) {
            Backdrop.AURORA -> aurora(c, th, W, H, boost, p)
            Backdrop.NEBULA -> nebula(c, th, W, H, boost, density, rnd, p)
            Backdrop.SILK -> silk(c, th, W, H, boost, density, p)
            Backdrop.BOKEH -> bokeh(c, th, W, H, boost, density, rnd, p)
            Backdrop.PRISM -> prism(c, th, W, H, boost, p)
            Backdrop.MESH -> mesh(c, th, W, H, boost, p)
        }
        p.maskFilter = null
        // a soft sheen from above gives the whole panel depth
        p.shader = LinearGradient(0f, 0f, 0f, H * 0.45f,
            Color.argb(if (th.dark) 16 else 40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p)
        p.shader = null
        p.color = th.veil
        c.drawRect(0f, 0f, W, H, p)
        grain(c, W, H, th.dark)
    }

    private fun withAlpha(col: Int, a: Float) =
        Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col))

    private fun glow(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, col: Int, a: Float, W: Float, H: Float) {
        if (r <= 0f) return
        p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(col, a), withAlpha(col, a * 0.45f), withAlpha(col, 0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(max(0f, cx - r), max(0f, cy - r), min(W, cx + r), min(H, cy + r), p)
    }

    // (x, y, radius as a fraction of width, colour index, alpha)
    private val AURORA_SPOTS = arrayOf(
        floatArrayOf(0.12f, 0.20f, 0.54f, 0f, 0.60f),
        floatArrayOf(0.88f, 0.15f, 0.48f, 1f, 0.60f),
        floatArrayOf(0.62f, 0.95f, 0.60f, 2f, 0.60f),
        floatArrayOf(0.20f, 0.90f, 0.50f, 3f, 0.55f),
        floatArrayOf(0.50f, 0.45f, 0.26f, 0f, 0.45f),
        floatArrayOf(0.78f, 0.60f, 0.24f, 1f, 0.45f),
        floatArrayOf(0.32f, 0.55f, 0.22f, 2f, 0.40f),
    )

    private fun aurora(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, p: Paint) {
        for (s in AURORA_SPOTS) {
            val col = th.blobs[s[3].toInt() % th.blobs.size]
            glow(c, p, s[0] * W, s[1] * H, s[2] * W * 1.2f, col, s[4] * boost, W, H)
        }
    }

    private fun mesh(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, p: Paint) {
        // four big colour fields from the corners, overlapping in the middle
        val R = max(W, H) * 0.95f
        glow(c, p, 0f, 0f, R, th.blobs[0], 0.85f * boost, W, H)
        glow(c, p, W, H * 0.1f, R * 0.9f, th.blobs[1], 0.80f * boost, W, H)
        glow(c, p, W * 0.15f, H, R * 0.85f, th.blobs[2], 0.75f * boost, W, H)
        glow(c, p, W * 0.95f, H * 1.05f, R * 0.8f, th.blobs[3], 0.70f * boost, W, H)
        glow(c, p, W * 0.5f, H * 0.5f, R * 0.35f, th.blobs[1], 0.30f * boost, W, H)
    }

    private fun nebula(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, d: Float, rnd: Random, p: Paint) {
        // gas clouds
        glow(c, p, W * 0.18f, H * 0.35f, W * 0.55f, th.blobs[0], 0.70f * boost, W, H)
        glow(c, p, W * 0.82f, H * 0.70f, W * 0.55f, th.blobs[1], 0.55f * boost, W, H)
        glow(c, p, W * 0.55f, H * 0.15f, W * 0.40f, th.blobs[2], 0.40f * boost, W, H)
        glow(c, p, W * 0.40f, H * 0.95f, W * 0.45f, th.blobs[3], 0.60f * boost, W, H)
        // a faint milky band across the sky
        c.save()
        c.rotate(-14f, W / 2f, H / 2f)
        c.scale(1f, 0.16f, W / 2f, H / 2f)
        val band = W * 0.75f
        p.shader = RadialGradient(W / 2f, H / 2f, band, intArrayOf(withAlpha(Color.WHITE, 0.13f * boost), withAlpha(Color.WHITE, 0f)),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(W / 2f, H / 2f, band, p)
        c.restore()
        // stars
        p.shader = null
        val n = (W * H / (1500f * d * d)).toInt().coerceIn(30, 400)
        for (i in 0 until n) {
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H
            val big = rnd.nextFloat() < 0.07f
            val r = (if (big) 0.9f + rnd.nextFloat() * 0.7f else 0.35f + rnd.nextFloat() * 0.6f) * d
            val tint = if (rnd.nextFloat() < 0.25f) th.blobs[rnd.nextInt(th.blobs.size)] else Color.WHITE
            val col = Theme.blend(Color.WHITE, tint, 0.35f)
            if (big) glow(c, p, x, y, r * 6f, col, 0.35f, W, H)
            p.shader = null
            p.color = withAlpha(col, 0.45f + rnd.nextFloat() * 0.5f)
            c.drawCircle(x, y, r, p)
        }
    }

    private fun silk(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, d: Float, p: Paint) {
        // a dim colour field under the ribbons
        glow(c, p, W * 0.15f, H * 0.2f, W * 0.6f, th.blobs[2], 0.35f * boost, W, H)
        glow(c, p, W * 0.9f, H * 0.9f, W * 0.6f, th.blobs[3], 0.30f * boost, W, H)
        val path = Path()
        // (start y, control 1 y, control 2 y, end y, thickness as fraction of H, colour a, colour b, alpha)
        val ribbons = arrayOf(
            floatArrayOf(0.95f, 0.10f, 1.10f, 0.20f, 0.30f, 0f, 1f, 0.55f),
            floatArrayOf(0.20f, 1.05f, -0.10f, 0.85f, 0.22f, 1f, 2f, 0.45f),
            floatArrayOf(0.65f, 0.40f, 0.90f, 0.50f, 0.12f, 3f, 0f, 0.50f),
        )
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (r in ribbons) {
            path.reset()
            path.moveTo(-W * 0.1f, r[0] * H)
            path.cubicTo(W * 0.3f, r[1] * H, W * 0.7f, r[2] * H, W * 1.1f, r[3] * H)
            val ca = th.blobs[r[5].toInt() % th.blobs.size]
            val cb = th.blobs[r[6].toInt() % th.blobs.size]
            val a = r[7] * boost
            // the soft body of the ribbon
            p.strokeWidth = r[4] * H
            p.maskFilter = BlurMaskFilter(max(1f, r[4] * H * 0.32f), BlurMaskFilter.Blur.NORMAL)
            p.shader = LinearGradient(0f, 0f, W, 0f, withAlpha(ca, a), withAlpha(cb, a), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            // and its bright, crisp fold
            p.strokeWidth = max(1f, 1.2f * d)
            p.maskFilter = BlurMaskFilter(max(1f, 1.5f * d), BlurMaskFilter.Blur.NORMAL)
            p.shader = LinearGradient(0f, 0f, W, 0f,
                withAlpha(Theme.blend(ca, Color.WHITE, 0.5f), a * 0.9f), withAlpha(Theme.blend(cb, Color.WHITE, 0.5f), a * 0.5f),
                Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
        p.maskFilter = null
        p.style = Paint.Style.FILL
    }

    private fun bokeh(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, d: Float, rnd: Random, p: Paint) {
        aurora(c, th, W, H, boost * 0.75f, p)
        p.shader = null
        val n = (W * H / (5200f * d * d)).toInt().coerceIn(10, 60)
        for (i in 0 until n) {
            val x = rnd.nextFloat() * W; val y = rnd.nextFloat() * H
            val r = (5f + rnd.nextFloat() * rnd.nextFloat() * 30f) * d * min(1f, W / (360f * d))
            val col = th.blobs[rnd.nextInt(th.blobs.size)]
            val a = (0.10f + rnd.nextFloat() * 0.22f) * boost * (if (th.dark) 1f else 1.3f)
            p.maskFilter = BlurMaskFilter(max(1f, r * 0.12f), BlurMaskFilter.Blur.NORMAL)
            p.style = Paint.Style.FILL
            p.color = withAlpha(col, a)
            c.drawCircle(x, y, r, p)
            // the brighter rim real lens bokeh has
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, r * 0.08f)
            p.color = withAlpha(Theme.blend(col, Color.WHITE, 0.4f), a * 0.9f)
            c.drawCircle(x, y, r * 0.96f, p)
        }
        p.maskFilter = null
        p.style = Paint.Style.FILL
    }

    private fun prism(c: Canvas, th: Theme, W: Float, H: Float, boost: Float, p: Paint) {
        aurora(c, th, W, H, boost * 0.8f, p)
        // diagonal holographic bands
        val spectrum = intArrayOf(0xFFFFB5E8.toInt(), 0xFFFFD6A5.toInt(), 0xFFFDFFB6.toInt(), 0xFFCAFFBF.toInt(),
            0xFF9BF6FF.toInt(), 0xFFA0C4FF.toInt(), 0xFFBDB2FF.toInt(), 0xFFFFB5E8.toInt())
        val cols = IntArray(spectrum.size) { withAlpha(spectrum[it], 0.42f * boost) }
        p.shader = LinearGradient(0f, H, W * 0.55f, -H * 0.2f, cols, null, Shader.TileMode.MIRROR)
        c.drawRect(0f, 0f, W, H, p)
        // two glossy highlights
        glow(c, p, W * 0.25f, H * 0.15f, W * 0.35f, Color.WHITE, 0.55f, W, H)
        glow(c, p, W * 0.8f, H * 0.8f, W * 0.3f, Color.WHITE, 0.4f, W, H)
    }

    private var grainTile: Bitmap? = null

    /** fine film grain: hides gradient banding and makes the glass feel real */
    private fun grain(c: Canvas, W: Float, H: Float, dark: Boolean) {
        val tile = grainTile ?: run {
            val n = 96
            val px = IntArray(n * n)
            val r = Random(7)
            for (i in px.indices) {
                val v = r.nextFloat()
                px[i] = if (v < 0.5f) Color.argb((v * 2f * 22).toInt(), 255, 255, 255)
                else Color.argb(((v - 0.5f) * 2f * 22).toInt(), 0, 0, 0)
            }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).also { grainTile = it }
        }
        val p = Paint()
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = if (dark) 150 else 110
        c.drawRect(0f, 0f, W, H, p)
    }

    /**
     * A theme swatch for the settings screen: the theme's real artwork with a tiny glass keyboard on it.
     * Renders into a bitmap once per size.
     */
    class Swatch(private val th: Theme, private val split: Theme? = null, private val density: Float, private val keys: Boolean = true) : Drawable() {
        private var cache: Bitmap? = null
        private val bp = Paint(Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            var bmp = cache
            if (bmp == null || bmp.width != b.width() || bmp.height != b.height()) {
                bmp = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val w = b.width(); val h = b.height()
                if (split != null) {
                    c.save(); c.clipRect(0, 0, w / 2, h); render(c, th, w, h); c.restore()
                    c.save(); c.clipRect(w / 2, 0, w, h); render(c, split, w, h); c.restore()
                } else render(c, th, w, h)
                cache = bmp
            }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), bp)
        }

        private fun render(c: Canvas, t: Theme, w: Int, h: Int) {
            // the artwork is drawn at keyboard scale and shrunk, so details look the same as on the keyboard
            val scale = 0.5f
            c.save()
            c.scale(scale, scale)
            ThemeArt.paint(c, t, (w / scale).toInt(), (h / scale).toInt(), density)
            c.restore()
            if (!keys) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val pad = w * 0.08f
            val kh = (h - pad * 2) / 4.96f
            val gap = kh * 0.32f
            var y = pad
            val rr = kh * 0.32f
            for (n in intArrayOf(5, 5, 4)) {
                val kw = (w - pad * 2 - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val x = pad + i * (kw + gap)
                    drawKey(c, p, t, RectF(x, y, x + kw, y + kh), rr, false)
                }
                y += kh + gap
            }
            // bottom row: space + accent enter
            val ew = (w - pad * 2) * 0.28f
            drawKey(c, p, t, RectF(pad, y, w - pad - ew - gap, y + kh), rr, false)
            drawKey(c, p, t, RectF(w - pad - ew, y, w - pad, y + kh), rr, true)
        }

        private fun drawKey(c: Canvas, p: Paint, t: Theme, r: RectF, rad: Float, accent: Boolean) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = if (accent) withAlpha(t.accent, 0.85f)
            else if (t.dark) Color.argb(46, Color.red(t.keyTint), Color.green(t.keyTint), Color.blue(t.keyTint))
            else Color.argb(170, 255, 255, 255)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, density * 0.6f)
            p.color = Color.argb(if (t.dark) 70 else 200, 255, 255, 255)
            c.drawRoundRect(r, rad, rad, p)
        }

        override fun setAlpha(alpha: Int) { bp.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { bp.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
