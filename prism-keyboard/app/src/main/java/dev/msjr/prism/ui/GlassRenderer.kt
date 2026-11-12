package dev.msjr.prism.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The liquid-glass material. A soft artwork backdrop fills the keyboard panel, and every key, capsule
 * and balloon is a lens over it: clear and slightly magnifying in the middle, bending the backdrop near
 * its rim, with a thin glint on the edge that faces the light (AGSL on Android 13+, a frosted look
 * elsewhere). The light can be moved, which is how the glints follow the phone as it tilts.
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
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var rimShader: LinearGradient? = null
    private val rimMatrix = Matrix()
    private val tmp = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The panel: backdrop clipped to softly rounded top corners. */
    private val panelPath = Path()
    private val panelEdge = Path()
    val panelRadius = 20f * density

    /** Bumped whenever anything that changes how keys look changes (theme, backdrop, light…). */
    var generation = 0
        private set

    private var glowShader: RadialGradient? = null
    private var glowKey = 0L
    private val glowMatrix = Matrix()
    private var rippleShader: RadialGradient? = null
    private var rippleColor = 0
    private val rippleMatrix = Matrix()

    /** Direction the light comes from (unit vector, screen space; default: above, slightly left). */
    private var lightX = DEFAULT_LIGHT_X
    private var lightY = DEFAULT_LIGHT_Y

    private var runtimeBroken = false
    val usesRefraction: Boolean get() = runtime != null && refractionWanted && !runtimeBroken

    fun configure(theme: Theme, glassStrength: Int, refraction: Boolean) {
        val changed = theme.id != this.theme.id || theme.accent != this.theme.accent
        this.theme = theme
        this.strength = glassStrength.coerceIn(0, 100) / 100f
        this.refractionWanted = refraction
        rimShader = LinearGradient(0f, 0f, 0f, 1f,
            intArrayOf(theme.rimTop, theme.rimBottom, adjustAlpha(theme.rimTop, 0.35f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        rimPaint.shader = rimShader
        shadowPaint.color = theme.shadow
        if (Build.VERSION.SDK_INT >= 28) shadowPaint.maskFilter = BlurMaskFilter(2.4f * density, BlurMaskFilter.Blur.NORMAL)
        if (changed) backdropKey = ""
        generation++
    }

    /**
     * Move the light (tilting the phone). Returns true if the glints changed enough to be worth redrawing.
     */
    fun setLight(x: Float, y: Float): Boolean {
        val len = sqrt(x * x + y * y)
        if (len < 0.001f) return false
        val nx = x / len; val ny = y / len
        val dx = nx - lightX; val dy = ny - lightY
        if (dx * dx + dy * dy < 0.0009f) return false
        lightX = nx; lightY = ny
        generation++
        return true
    }

    fun resetLight() { if (setLight(DEFAULT_LIGHT_X, DEFAULT_LIGHT_Y)) Unit }

    /** Invalidate a cached backdrop, e.g. when the user picks a new photo. */
    fun resetBackdrop() { backdropKey = "" }

    /**
     * Make sure the backdrop bitmap matches the given panel size.
     * @param top y offset of the panel inside the view (the transparent preview area is above it)
     */
    fun ensureBackdrop(width: Int, height: Int, top: Float, mode: String) {
        if (width <= 0 || height <= 0) return
        if (backdrop == null || backdropKey.isEmpty() || width != bW || height != bH || mode != bMode || strength != bStrength || top != backdropTop) {
            val sizeChanged = backdrop == null || backdropKey.isEmpty() || width != bW || height != bH || mode != bMode || strength != bStrength
            backdropTop = top
            if (sizeChanged) {
                bW = width; bH = height; bMode = mode; bStrength = strength
                backdropKey = theme.id
                backdrop?.recycle()
                val bmp = (if (mode == "image") loadImageBackdrop(width, height) else null)
                    ?: makeArt(width, height)
                backdrop = bmp
                backdropShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val r = panelRadius
            val w = width.toFloat(); val b = top + height
            panelPath.reset()
            panelPath.moveTo(0f, b); panelPath.lineTo(0f, top + r)
            panelPath.quadTo(0f, top, r, top)
            panelPath.lineTo(w - r, top)
            panelPath.quadTo(w, top, w, top + r)
            panelPath.lineTo(w, b); panelPath.close()
            panelEdge.reset()
            panelEdge.moveTo(0f, top + r * 1.6f); panelEdge.lineTo(0f, top + r)
            panelEdge.quadTo(0f, top, r, top)
            panelEdge.lineTo(w - r, top)
            panelEdge.quadTo(w, top, w, top + r)
            panelEdge.lineTo(w, top + r * 1.6f)
            edgePaint.strokeWidth = max(1f, 0.8f * density)
            edgePaint.shader = LinearGradient(0f, top, 0f, top + r * 1.6f,
                Color.argb(if (theme.dark) 70 else 235, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            generation++
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
        val s = backdropShader ?: return
        panelPaint.shader = s
        canvas.drawPath(panelPath, panelPaint)
        canvas.drawPath(panelEdge, edgePaint)
    }

    fun clipPanel(canvas: Canvas) { canvas.clipPath(panelPath) }

    /** Soft light that follows the finger — makes the glass feel alive. */
    fun drawGlow(canvas: Canvas, x: Float, y: Float, alpha: Float, radius: Float) {
        if (alpha <= 0.01f) return
        val c = theme.accent
        // one cached gradient, moved with a matrix and faded with paint alpha (no per-frame allocation)
        val k = (c.toLong() shl 32) or radius.toRawBits().toLong()
        if (glowShader == null || k != glowKey) {
            val a = if (theme.dark) 90 else 70
            glowShader = RadialGradient(0f, 0f, radius,
                intArrayOf(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            glowKey = k
        }
        glowMatrix.setTranslate(x, y)
        glowShader?.setLocalMatrix(glowMatrix)
        glowPaint.shader = glowShader
        glowPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius, glowPaint)
    }

    /** A ring of light spreading from a tap: [t] 0 → 1 over its life. */
    fun drawRipple(canvas: Canvas, x: Float, y: Float, t: Float) {
        if (t <= 0f || t >= 1f) return
        val c = if (theme.dark) Theme.blend(theme.accent, Color.WHITE, 0.35f) else theme.accent
        if (rippleShader == null || rippleColor != c) {
            val a = if (theme.dark) 120 else 90
            val on = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
            val off = Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
            rippleShader = RadialGradient(0f, 0f, 1f, intArrayOf(off, off, on, off), floatArrayOf(0f, 0.55f, 0.86f, 1f), Shader.TileMode.CLAMP)
            rippleColor = c
        }
        val ease = 1f - (1f - t) * (1f - t)
        val r = (26f + 92f * ease) * density
        rippleMatrix.setScale(r, r)
        rippleMatrix.postTranslate(x, y)
        rippleShader?.setLocalMatrix(rippleMatrix)
        ripplePaint.shader = rippleShader
        ripplePaint.alpha = ((1f - t) * (1f - t) * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, r, ripplePaint)
    }

    /**
     * Draw one glass shape.
     * @param tint glass body colour; [tintAlpha] how much of it covers the refracted backdrop
     * @param press 0 resting … 1 pressed (may overshoot a little): the glass swells and brightens
     */
    fun drawGlass(
        canvas: Canvas, r: RectF, radius: Float, press: Float,
        tint: Int, tintAlpha: Float, shadow: Boolean = true, alpha: Float = 1f,
    ) {
        val inflate = press * 1.2f * density
        tmp.set(r.left - inflate, r.top - inflate, r.right + inflate, r.bottom + inflate)
        val rad = min(radius + inflate, min(tmp.width(), tmp.height()) / 2f)
        val a255 = (alpha * 255).toInt().coerceIn(0, 255)

        if (shadow) {
            // a soft contact shadow (blurred on Android 9+), a little deeper when pressed
            val dy = (1.6f + press * 1.4f) * density
            shadowPaint.alpha = (Color.alpha(theme.shadow) * alpha).toInt()
            canvas.drawRoundRect(tmp.left, tmp.top + dy, tmp.right, tmp.bottom + dy, rad, rad, shadowPaint)
        }

        val rs = runtime
        var drawn = false
        if (rs != null && !runtimeBroken && refractionWanted && Build.VERSION.SDK_INT >= 33 && backdropShader != null) try {
            rs.setFloatUniform("center", tmp.centerX(), tmp.centerY())
            rs.setFloatUniform("halfSize", tmp.width() / 2f, tmp.height() / 2f)
            rs.setFloatUniform("radius", rad)
            rs.setFloatUniform("bezel", max(3f, min(tmp.width(), tmp.height()) * 0.275f))
            rs.setFloatUniform("strength", (5f + 14f * strength) * density)
            rs.setFloatUniform("px1", density)
            rs.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha)
            rs.setFloatUniform("press", press)
            rs.setFloatUniform("dark", if (theme.dark) 1f else 0f)
            rs.setFloatUniform("light", lightX, lightY)
            rs.setFloatUniform("join", 0f)
            glassPaint.shader = rs
            glassPaint.alpha = a255
            canvas.drawRect(tmp.left - 2f, tmp.top - 2f, tmp.right + 2f, tmp.bottom + 2f, glassPaint)
            drawn = true
        } catch (t: Throwable) {
            Log.w(TAG, "glass shader failed, using frosted fallback", t)
            runtimeBroken = true
        }
        if (!drawn) {
            // frosted: a translucent body, lighter toward the top, and a rim that catches the light
            val base = if (theme.dark) 0.16f else 0.44f
            val a = ((base + tintAlpha * 0.5f + press * 0.18f).coerceIn(0f, 1f) * a255).toInt()
            fillPaint.shader = null
            fillPaint.color = Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawRoundRect(tmp, rad, rad, fillPaint)
            val sw = 0.9f * density
            rimPaint.strokeWidth = sw
            rimPaint.alpha = a255
            rimMatrix.setScale(1f, tmp.height())
            rimMatrix.postTranslate(0f, tmp.top)
            rimShader?.setLocalMatrix(rimMatrix)
            val h = sw / 2f
            canvas.drawRoundRect(tmp.left + h, tmp.top + h, tmp.right - h, tmp.bottom - h, rad - h, rad - h, rimPaint)
        }
    }

    private val balloonPath = Path()
    private val tmp2 = RectF()

    /**
     * The key-preview balloon: a big rounded [bubble] melted into the pressed [key] below it,
     * all one piece of glass.
     */
    fun drawBalloon(canvas: Canvas, bubble: RectF, bubbleRadius: Float, key: RectF, keyRadius: Float, tint: Int, tintAlpha: Float) {
        val melt = min(key.width(), bubble.height()) * 0.42f
        // soft shadow under the whole balloon so it floats above the keys
        val dy = 2.2f * density
        shadowPaint.alpha = (Color.alpha(theme.shadow) * 1.6f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(bubble.left, bubble.top + dy, bubble.right, bubble.bottom + dy, bubbleRadius, bubbleRadius, shadowPaint)
        canvas.drawRoundRect(key.left, key.top + dy, key.right, key.bottom + dy, keyRadius, keyRadius, shadowPaint)
        shadowPaint.color = theme.shadow

        val rs = runtime
        if (rs != null && !runtimeBroken && refractionWanted && Build.VERSION.SDK_INT >= 33 && backdropShader != null) try {
            rs.setFloatUniform("center", bubble.centerX(), bubble.centerY())
            rs.setFloatUniform("halfSize", bubble.width() / 2f, bubble.height() / 2f)
            rs.setFloatUniform("radius", min(bubbleRadius, min(bubble.width(), bubble.height()) / 2f))
            rs.setFloatUniform("center2", key.centerX(), key.centerY())
            rs.setFloatUniform("halfSize2", key.width() / 2f, key.height() / 2f)
            rs.setFloatUniform("radius2", min(keyRadius, min(key.width(), key.height()) / 2f))
            rs.setFloatUniform("join", melt)
            rs.setFloatUniform("bezel", max(3f, min(bubble.width(), bubble.height()) * 0.22f))
            rs.setFloatUniform("strength", (5f + 14f * strength) * density)
            rs.setFloatUniform("px1", density)
            rs.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha)
            rs.setFloatUniform("press", 0.15f)
            rs.setFloatUniform("dark", if (theme.dark) 1f else 0f)
            rs.setFloatUniform("light", lightX, lightY)
            glassPaint.shader = rs
            glassPaint.alpha = 255
            val pad = melt + 2f
            tmp2.set(min(bubble.left, key.left) - pad, bubble.top - 2f, max(bubble.right, key.right) + pad, key.bottom + 2f)
            canvas.drawRect(tmp2, glassPaint)
            rs.setFloatUniform("join", 0f)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "balloon shader failed, using frosted fallback", t)
            runtimeBroken = true
        }
        // frosted: bubble + neck + key as one opaque-ish piece
        val a = ((if (theme.dark) 0.55f else 0.60f) + tintAlpha * 0.4f).coerceIn(0f, 1f)
        fillPaint.shader = null
        fillPaint.color = Color.argb((a * 255).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint))
        balloonPath.reset()
        balloonPath.addRoundRect(bubble, bubbleRadius, bubbleRadius, Path.Direction.CW)
        balloonPath.addRoundRect(key, keyRadius, keyRadius, Path.Direction.CW)
        balloonPath.addRect(key.left, bubble.bottom - bubbleRadius, key.right, key.top + keyRadius, Path.Direction.CW)
        balloonPath.fillType = Path.FillType.WINDING
        canvas.drawPath(balloonPath, fillPaint)
    }

    // ------------------------------------------------------------------ backdrops

    private fun makeArt(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ThemeArt.paint(Canvas(bmp), theme, w, h, density, strength)
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
        const val DEFAULT_LIGHT_X = -0.41f
        const val DEFAULT_LIGHT_Y = -0.912f

        fun adjustAlpha(c: Int, f: Float) =
            Color.argb((Color.alpha(c) * f).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

        /**
         * Liquid glass: a rounded-rect lens. Clear, slightly magnified centre; light bends only near
         * the edge (with a touch of colour dispersion); a thin bright glint along the rim that faces
         * the light and a softer one opposite; a faint shade inside the far rim gives it thickness;
         * tint is screen-blended so the glass brightens instead of turning grey.
         */
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
uniform float px1;
uniform float2 light;
uniform float2 center2;
uniform float2 halfSize2;
uniform float radius2;
uniform float join;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

// the shape: one rounded rect, or (join > 0) two melted together like a key and its balloon
float shape(float2 c) {
    float d = sdRoundRect(c - center, halfSize, radius);
    if (join > 0.0) {
        float d2 = sdRoundRect(c - center2, halfSize2, radius2);
        float h = clamp(0.5 + 0.5 * (d2 - d) / join, 0.0, 1.0);
        d = mix(d2, d, h) - join * h * (1.0 - h);
    }
    return d;
}

half4 main(float2 coord) {
    float2 p = coord - center;
    float d = shape(coord);
    float dx = shape(coord + float2(1.0, 0.0)) - shape(coord - float2(1.0, 0.0));
    float dy = shape(coord + float2(0.0, 1.0)) - shape(coord - float2(0.0, 1.0));
    float2 n = float2(dx, dy);
    n = n / max(length(n), 0.0001);

    float edge = clamp(1.0 + d / bezel, 0.0, 1.0);
    float curve = edge * edge * edge;
    float mag = curve * strength * (1.0 + 0.35 * press);
    float zoom = 0.10 + 0.04 * press;
    float2 s = coord - p * zoom - n * mag;
    float2 off = s - coord;

    float3 c = float3(
        float(bg.eval(coord + off * 1.10).r),
        float(bg.eval(s).g),
        float(bg.eval(coord + off * 0.90).b));
    float3 f = float3(bg.eval(s + float2(3.0, 2.0)).rgb) + float3(bg.eval(s + float2(-3.0, -2.0)).rgb)
        + float3(bg.eval(s + float2(2.0, -3.0)).rgb) + float3(bg.eval(s + float2(-2.0, 3.0)).rgb);
    c = mix(c, f * 0.25, 0.5);

    float luma = dot(c, float3(0.299, 0.587, 0.114));
    c = clamp(mix(float3(luma), c, 1.5), 0.0, 1.0);
    c = float3(1.0) - (float3(1.0) - c) * (float3(1.0) - tint.rgb * tint.a);

    // a sheen on the half that faces the light, a faint shade inside the far rim
    float2 L = normalize(light);
    float facing = clamp(dot(p / max(halfSize, float2(1.0, 1.0)), L) * 0.5 + 0.5, 0.0, 1.0);
    c += float3((0.05 + 0.02 * dark) * facing * facing);
    float lit = dot(n, L);
    c *= 1.0 - (0.10 - 0.04 * dark) * curve * max(-lit, 0.0);

    float band = clamp(1.0 + d / (1.6 * px1), 0.0, 1.0);
    band = band * band;
    float glint = band * (0.60 * pow(max(lit, 0.0), 1.5) + 0.26 * pow(max(-lit, 0.0), 2.0) + 0.08);
    float spec = curve * 0.24 * pow(max(lit, 0.0), 4.0);
    c += float3(glint * (1.0 + 0.4 * press) + spec + press * 0.10);

    float aa = clamp(0.5 - d, 0.0, 1.0);
    return half4(half3(clamp(c, 0.0, 1.0) * aa), half(aa));
}
"""
    }
}
