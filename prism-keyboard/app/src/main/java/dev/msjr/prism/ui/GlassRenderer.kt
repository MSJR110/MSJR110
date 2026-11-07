package dev.msjr.prism.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the "liquid glass" look: a soft ambient backdrop, and glass shapes that really refract it
 * (AGSL runtime shader on Android 13+, a frosted fallback elsewhere).
 */
class GlassRenderer(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density

    var theme: Theme = Theme.ALL[0]
        private set
    var strength = 0.7f
        private set
    private var refractionWanted = true

    private var backdrop: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private val backdropMatrix = Matrix()
    private var backdropTop = 0f
    private var backdropKey = ""
    private var bW = 0
    private var bH = 0
    private var bMode = ""
    private var bStrength = -1f

    private val runtime: RuntimeShader? = createRuntimeShader()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rimShader: LinearGradient? = null
    private val rimMatrix = Matrix()
    private val tmp = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var runtimeBroken = false
    val usesRefraction: Boolean get() = runtime != null && refractionWanted && !runtimeBroken

    fun configure(theme: Theme, glassStrength: Int, refraction: Boolean) {
        val changed = theme.id != this.theme.id
        this.theme = theme
        this.strength = glassStrength.coerceIn(0, 100) / 100f
        this.refractionWanted = refraction
        rimShader = LinearGradient(0f, 0f, 0f, 1f,
            intArrayOf(theme.rimTop, theme.rimBottom, adjustAlpha(theme.rimTop, 0.35f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        rimPaint.shader = rimShader
        shadowPaint.color = theme.shadow
        if (changed) backdropKey = ""
    }

    /** Invalidate a cached backdrop, e.g. when the user picks a new photo. */
    fun resetBackdrop() { backdropKey = "" }

    /**
     * Make sure the backdrop bitmap matches the given panel size.
     * @param top y offset of the panel inside the view (the transparent preview area is above it)
     */
    fun ensureBackdrop(width: Int, height: Int, top: Float, mode: String) {
        if (width <= 0 || height <= 0) return
        backdropTop = top
        if (backdrop == null || backdropKey.isEmpty() || width != bW || height != bH || mode != bMode || strength != bStrength) {
            bW = width; bH = height; bMode = mode; bStrength = strength
            backdropKey = theme.id
            backdrop?.recycle()
            val bmp = (if (mode == "image") loadImageBackdrop(width, height) else null)
                ?: makeAurora(width, height)
            backdrop = bmp
            backdropShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        backdropMatrix.setTranslate(0f, top)
        backdropShader?.setLocalMatrix(backdropMatrix)
        runtime?.let { rs ->
            backdropShader?.let {
                if (Build.VERSION.SDK_INT >= 33) try { rs.setInputShader("bg", it) } catch (_: Throwable) { runtimeBroken = true }
            }
        }
    }

    fun drawBackdrop(canvas: Canvas) {
        val b = backdrop ?: return
        canvas.drawBitmap(b, 0f, backdropTop, bitmapPaint)
    }

    /** Soft light that follows the finger — makes the glass feel alive. */
    fun drawGlow(canvas: Canvas, x: Float, y: Float, alpha: Float, radius: Float) {
        if (alpha <= 0.01f) return
        val c = theme.accent
        val a = (alpha * (if (theme.dark) 90 else 70)).toInt().coerceIn(0, 255)
        glowPaint.shader = RadialGradient(x, y, radius,
            intArrayOf(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, glowPaint)
    }

    /**
     * Draw one glass capsule.
     * @param tint glass body color; [tintAlpha] how much of it covers the refracted backdrop
     */
    fun drawGlass(
        canvas: Canvas, r: RectF, radius: Float, press: Float,
        tint: Int, tintAlpha: Float, shadow: Boolean = true, rim: Boolean = true,
    ) {
        val inflate = press * 1.2f * density
        tmp.set(r.left - inflate, r.top - inflate, r.right + inflate, r.bottom + inflate)
        val rad = radius + inflate

        if (shadow) {
            val dy = (1.1f + press * 1.5f) * density
            canvas.drawRoundRect(tmp.left, tmp.top + dy, tmp.right, tmp.bottom + dy, rad, rad, shadowPaint)
        }

        val rs = runtime
        var drawn = false
        if (rs != null && !runtimeBroken && refractionWanted && Build.VERSION.SDK_INT >= 33 && backdropShader != null) try {
            rs.setFloatUniform("center", tmp.centerX(), tmp.centerY())
            rs.setFloatUniform("halfSize", tmp.width() / 2f, tmp.height() / 2f)
            rs.setFloatUniform("radius", min(rad, min(tmp.width(), tmp.height()) / 2f))
            rs.setFloatUniform("bezel", max(4f, min(tmp.width(), tmp.height()) * 0.32f))
            rs.setFloatUniform("strength", (4f + 12f * strength) * density)
            rs.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha)
            rs.setFloatUniform("press", press)
            rs.setFloatUniform("dark", if (theme.dark) 1f else 0f)
            glassPaint.shader = rs
            canvas.drawRect(tmp.left - 2f, tmp.top - 2f, tmp.right + 2f, tmp.bottom + 2f, glassPaint)
            drawn = true
        } catch (t: Throwable) {
            Log.w(TAG, "glass shader failed, using frosted fallback", t)
            runtimeBroken = true
        }
        if (!drawn) {
            // frosted fallback: translucent body + a top sheen
            val base = if (theme.dark) 0.16f else 0.62f
            val a = ((base + tintAlpha * 0.5f + press * 0.18f).coerceIn(0f, 1f) * 255).toInt()
            fillPaint.shader = null
            fillPaint.color = Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawRoundRect(tmp, rad, rad, fillPaint)
        }

        if (rim) {
            val sw = 0.9f * density
            rimPaint.strokeWidth = sw
            rimMatrix.setScale(1f, tmp.height())
            rimMatrix.postTranslate(0f, tmp.top)
            rimShader?.setLocalMatrix(rimMatrix)
            val h = sw / 2f
            canvas.drawRoundRect(tmp.left + h, tmp.top + h, tmp.right - h, tmp.bottom - h, rad - h, rad - h, rimPaint)
        }
    }

    // ------------------------------------------------------------------ backdrops

    private fun makeAurora(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), theme.base[0], theme.base[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // layout of light blobs: (x, y, radius as fraction of width, color index, alpha)
        val spots = arrayOf(
            floatArrayOf(0.12f, 0.20f, 0.54f, 0f, 0.60f),
            floatArrayOf(0.88f, 0.15f, 0.48f, 1f, 0.60f),
            floatArrayOf(0.62f, 0.95f, 0.60f, 2f, 0.60f),
            floatArrayOf(0.20f, 0.90f, 0.50f, 3f, 0.55f),
            floatArrayOf(0.50f, 0.45f, 0.26f, 0f, 0.45f),
            floatArrayOf(0.78f, 0.60f, 0.24f, 1f, 0.45f),
            floatArrayOf(0.32f, 0.55f, 0.22f, 2f, 0.40f),
        )
        val boost = 0.55f + 0.45f * strength
        for (s in spots) {
            val col = theme.blobs[s[3].toInt() % theme.blobs.size]
            val a = (s[4] * boost * 255).toInt().coerceIn(0, 255)
            val cx = s[0] * w; val cy = s[1] * h; val rad = s[2] * w * 1.2f
            p.shader = RadialGradient(cx, cy, rad,
                intArrayOf(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        p.shader = null
        p.color = theme.veil
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun loadImageBackdrop(w: Int, h: Int): Bitmap? = try {
        val f = File(ctx.filesDir, BACKGROUND_FILE)
        if (!f.exists()) null else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
            val src = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (src == null) null else {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                // center-crop
                val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
                val m = Matrix()
                m.setScale(scale, scale)
                m.postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
                c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                src.recycle()
                // cheap soft blur: down/up sample
                val small = Bitmap.createScaledBitmap(out, max(1, w / 10), max(1, h / 10), true)
                val blurred = Bitmap.createScaledBitmap(small, w, h, true)
                small.recycle()
                val c2 = Canvas(out)
                val bp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * (0.35f + 0.5f * strength)).toInt() }
                c2.drawBitmap(blurred, 0f, 0f, bp)
                blurred.recycle()
                val veil = Paint().apply { color = theme.veil }
                c2.drawRect(0f, 0f, w.toFloat(), h.toFloat(), veil)
                out
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "background image failed", t)
        null
    }

    private fun createRuntimeShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            RuntimeShader(GLASS_AGSL)
        } catch (t: Throwable) {
            Log.w(TAG, "glass shader unavailable", t)
            null
        }
    }

    companion object {
        const val TAG = "PrismGlass"
        const val BACKGROUND_FILE = "background.jpg"

        fun adjustAlpha(c: Int, f: Float) =
            Color.argb((Color.alpha(c) * f).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

        /** Liquid glass: rounded-rect lens with refraction, dispersion, frost and specular rim. */
        val GLASS_AGSL = """
uniform shader bg;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float4 tint;
uniform float press;
uniform float dark;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

half4 main(float2 coord) {
    float2 p = coord - center;
    float d = sdRoundRect(p, halfSize, radius);
    float dx = sdRoundRect(p + float2(1.0, 0.0), halfSize, radius) - sdRoundRect(p - float2(1.0, 0.0), halfSize, radius);
    float dy = sdRoundRect(p + float2(0.0, 1.0), halfSize, radius) - sdRoundRect(p - float2(0.0, 1.0), halfSize, radius);
    float2 n = float2(dx, dy);
    n = n / max(length(n), 0.0001);

    float edge = clamp(1.0 + d / bezel, 0.0, 1.0);
    float curve = edge * edge;
    float mag = curve * strength * (1.0 + 0.4 * press);
    float2 off = -n * mag;

    float3 c;
    c.r = float(bg.eval(coord + off * 1.12).r);
    c.g = float(bg.eval(coord + off).g);
    c.b = float(bg.eval(coord + off * 0.88).b);
    float3 f = float3(0.0);
    f += float3(bg.eval(coord + off + float2(2.5, 1.5)).rgb);
    f += float3(bg.eval(coord + off + float2(-2.5, -1.5)).rgb);
    f += float3(bg.eval(coord + off + float2(1.5, -2.5)).rgb);
    f += float3(bg.eval(coord + off + float2(-1.5, 2.5)).rgb);
    c = mix(c, f * 0.25, 0.45);

    float luma = dot(c, float3(0.299, 0.587, 0.114));
    c = clamp(mix(float3(luma), c, 1.35), 0.0, 1.0);

    c = mix(c, tint.rgb, tint.a);

    float2 L = normalize(float2(-0.35, -1.0));
    float lit = dot(n, L);
    float spec = pow(max(lit, 0.0), 3.0) * curve + 0.45 * pow(max(-lit, 0.0), 4.0) * curve;
    float rim = smoothstep(-2.5, 0.0, d);
    c += float3(spec * (0.42 + 0.3 * press) + rim * 0.10);
    float shade = max(-lit, 0.0) * curve * 0.5;
    c -= float3(shade * (0.06 + 0.06 * dark));

    float body = 1.0 - edge;
    c += float3(press * (0.10 + 0.08 * body));

    float aa = clamp(0.5 - d, 0.0, 1.0);
    return half4(half3(c * aa), half(aa));
}
"""
    }
}
