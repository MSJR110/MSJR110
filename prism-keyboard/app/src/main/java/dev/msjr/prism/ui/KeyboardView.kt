package dev.msjr.prism.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.OverScroller
import dev.msjr.prism.data.ClipboardStore
import dev.msjr.prism.data.EmojiData
import dev.msjr.prism.data.Settings
import dev.msjr.prism.keyboard.Key
import dev.msjr.prism.keyboard.KeyType
import dev.msjr.prism.keyboard.KeyboardLayout
import dev.msjr.prism.text.Suggestion
import dev.msjr.prism.text.SuggestionResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole keyboard in one hardware-accelerated view: suggestion strip, keys, emoji & clipboard panels,
 * key previews and long-press popups. Everything is drawn directly — no child views, no layout passes
 * while typing — which is what keeps it smooth.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val glass: GlassRenderer) : View(context) {

    interface Listener {
        fun onChar(text: String, fromPopup: Boolean)
        fun onDelete(repeat: Int)
        fun onDeleteWords(count: Int, commit: Boolean)
        fun onSpace()
        fun onEnter()
        fun onShiftDown()
        fun onSwitchPage(type: KeyType)
        fun onLang()
        fun onLangLongPress()
        fun onCursor(dx: Int, dy: Int)
        fun onTrackpad(active: Boolean)
        fun onSuggestion(s: Suggestion)
        fun onSuggestionLongPress(s: Suggestion)
        fun onToolbar(action: Toolbar)
        fun onEmoji(e: String)
        fun onPasteClip(text: String)
        fun onKeyFeedback(key: Key?, kind: Int)
    }

    enum class Toolbar { HIDE, SETTINGS, CLIPBOARD, EMOJI, ONE_HAND, ONE_HAND_SWITCH, ONE_HAND_OFF, CLOSE_PANEL, CLEAR_CLIPBOARD }
    enum class Mode { KEYS, EMOJI, CLIPBOARD }

    var listener: Listener? = null

    /**
     * Asked when a tap lands right on the border between two letters: return true to type [alt]
     * instead of [primary] (the IME checks which one actually continues a word).
     */
    var charResolver: ((primary: String, alt: String) -> Boolean)? = null

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ state

    private var layout: KeyboardLayout? = null
    private var settings: Settings? = null
    var mode = Mode.KEYS
        private set
    private var shiftState = 0 // 0 off, 1 once, 2 locked
    private var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED
    private var suggestions: SuggestionResult = SuggestionResult.EMPTY
    private var showSuggestions = true
    var clipChip: String? = null
        set(v) { field = v; invalidate() }
    var clipboardStore: ClipboardStore? = null
    private var recentEmoji: List<String> = emptyList()
    private var langLabel = "FA"

    // ------------------------------------------------------------------ geometry

    val extraTop = (30 * dp)                     // transparent room for previews over the app
    private var stripH = 46 * dp
    private var rowPitch = 60 * dp
    private var keysH = 0f
    private var bottomInset = 0
    private val padTop = 4 * dp
    private val padBottom = 6 * dp
    private val gapX = 6.5f * dp
    private val gapY = 12 * dp
    private val radius = 9 * dp
    private val keysArea = RectF()
    private val oneHandArea = RectF()
    private val stripArea = RectF()
    private val stripSlots = Array(3) { RectF() }
    private val toolbarBtns = ArrayList<Pair<Toolbar, RectF>>()
    private val clipChipRect = RectF()

    val visibleTop: Int get() = extraTop.toInt()

    // ------------------------------------------------------------------ paints

    private var typeface: Typeface = Typeface.DEFAULT
    private var typefaceBold: Typeface = Typeface.DEFAULT_BOLD
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val stripPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cardText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------------------------ animation

    private var animating = false
    private var lastFrameNs = 0L
    private var labelAlpha = 1f
    private var labelAlphaTarget = 1f
    private var glowX = 0f
    private var glowY = 0f
    private var glowAlpha = 0f
    private var previewScale = 0f
    private var popupAnim = 0f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNs == 0L) 1f / 120f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 1f / 30f)
            lastFrameNs = frameTimeNanos
            val busy = step(dt)
            invalidate()
            if (busy) Choreographer.getInstance().postFrameCallback(this) else { animating = false; lastFrameNs = 0L }
        }
    }

    private fun kick() {
        if (!animating) {
            animating = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** advance all springs; returns true while something still moves */
    private fun step(dt: Float): Boolean {
        var busy = false
        layout?.let { l ->
            for (k in l.allKeys) {
                if (k.press != k.pressTarget || abs(k.pressVel) > 0.001f) {
                    // instant in, quick release with one small liquid overshoot
                    val stiff = if (k.pressTarget > k.press) 2600f else 900f
                    val damp = if (k.pressTarget > k.press) 1f else 0.68f
                    springStep(k, stiff, damp, dt)
                    if (abs(k.press - k.pressTarget) < 0.002f && abs(k.pressVel) < 0.01f) {
                        k.press = k.pressTarget; k.pressVel = 0f
                    } else busy = true
                }
            }
        }
        for (k in panelKeys) {
            if (k.press != k.pressTarget || abs(k.pressVel) > 0.001f) {
                springStep(k, if (k.pressTarget > k.press) 1700f else 420f, if (k.pressTarget > k.press) 0.9f else 0.42f, dt)
                if (abs(k.press - k.pressTarget) < 0.002f && abs(k.pressVel) < 0.01f) { k.press = k.pressTarget; k.pressVel = 0f } else busy = true
            }
        }
        if (labelAlpha != labelAlphaTarget) {
            val d = labelAlphaTarget - labelAlpha
            labelAlpha += d * min(1f, dt * 14f)
            if (abs(d) < 0.01f) labelAlpha = labelAlphaTarget else busy = true
        }
        if (glowAlpha > 0f && activePointers() == 0) {
            glowAlpha = max(0f, glowAlpha - dt * 2.4f)
            busy = busy || glowAlpha > 0f
        }
        val previewTarget = if (pointers.values.any { it.previewKey != null }) 1f else 0f
        if (previewScale != previewTarget) {
            previewScale = if (previewTarget > previewScale) previewTarget else previewScale + (previewTarget - previewScale) * min(1f, dt * 30f)
            if (abs(previewScale - previewTarget) < 0.01f) previewScale = previewTarget else busy = true
        }
        val popTarget = if (popupPtr != null) 1f else 0f
        if (popupAnim != popTarget) {
            popupAnim += (popTarget - popupAnim) * min(1f, dt * 22f)
            if (abs(popupAnim - popTarget) < 0.01f) popupAnim = popTarget else busy = true
        }
        if (scroller.computeScrollOffset()) {
            panelScroll = scroller.currY.toFloat().coerceIn(0f, maxPanelScroll())
            busy = true
        }
        return busy
    }

    private fun springStep(k: Key, stiffness: Float, damping: Float, dt: Float) {
        val c = 2f * damping * sqrt(stiffness)
        var t = dt
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -stiffness * (k.press - k.pressTarget) - c * k.pressVel
            k.pressVel += a * h
            k.press += k.pressVel * h
            t -= h
        }
    }

    // ------------------------------------------------------------------ public API

    fun applySettings(s: Settings, font: Typeface, fontBold: Typeface) {
        settings = s
        typeface = font; typefaceBold = fontBold
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        rowPitch = 60f * dp * (s.heightPct / 100f) * (if (landscape) 0.7f else 1f)
        stripH = (if (landscape) 38f else 46f) * dp
        showSuggestions = s.suggestions
        requestLayout()
        invalidate()
    }

    fun setLayout(l: KeyboardLayout) {
        layout = l
        val oldH = keysH
        computeKeysHeight()
        if (oldH != keysH) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setLangLabel(s: String) { langLabel = s; invalidate() }

    fun setShift(state: Int) {
        if (state != shiftState) { shiftState = state; invalidate() }
    }

    fun setEnterAction(action: Int) { enterAction = action; invalidate() }

    fun setSuggestions(r: SuggestionResult) {
        suggestions = r
        invalidate()
    }

    fun setRecentEmoji(list: List<String>) { recentEmoji = list; emojiSections = null }

    fun setMode(m: Mode) {
        if (mode == m) return
        cancelAllPointers()
        mode = m
        panelScroll = 0f
        scroller.forceFinished(true)
        if (m == Mode.EMOJI) emojiSections = null
        layoutPanelKeys()
        invalidate()
    }

    fun refreshPanel() { cardCache.clear(); invalidate() }

    fun resetState() {
        cancelAllPointers()
        labelAlpha = 1f; labelAlphaTarget = 1f
        glowAlpha = 0f
        invalidate()
    }

    // ------------------------------------------------------------------ measure / layout

    private fun computeKeysHeight() {
        val l = layout ?: return
        val rows = l.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        // keep the panels the same height as the letters page so nothing jumps
        val letterRows = max(rows, 4f)
        keysH = padTop + padBottom + rowPitch * (if (l.id == "num") rows * 1.0f else letterRows)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val b = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        if (b != bottomInset) { bottomInset = b; requestLayout() }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        computeKeysHeight()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (extraTop + stripH + keysH + bottomInset).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
        layoutPanelKeys()
    }

    private fun layoutKeys() {
        val l = layout ?: return
        val w = width.toFloat()
        if (w <= 0f) return
        stripArea.set(0f, extraTop, w, extraTop + stripH)
        val oneHand = settings?.oneHand ?: 0
        val kbW = if (oneHand != 0 && l.id != "num") w * 0.82f else w
        val left = if (oneHand == 2) w - kbW else 0f
        keysArea.set(left, extraTop + stripH, left + kbW, extraTop + stripH + keysH)
        if (oneHand != 0 && l.id != "num") {
            oneHandArea.set(if (oneHand == 1) kbW else 0f, keysArea.top, if (oneHand == 1) w else w - kbW, keysArea.bottom)
        } else oneHandArea.setEmpty()

        val sidePad = 3 * dp
        val unitW = (kbW - sidePad * 2) / l.units
        var y = keysArea.top + padTop
        val totalWeight = l.rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        val pitch = (keysH - padTop - padBottom) / totalWeight
        for ((ri, row) in l.rows.withIndex()) {
            val rowH = pitch * row.heightWeight
            val rowUnits = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = keysArea.left + sidePad + (l.units - rowUnits) / 2f * unitW
            for ((ki, k) in row.keys.withIndex()) {
                val kw = k.width * unitW
                k.rect.set(x + gapX / 2f, y + gapY / 2f, x + kw - gapX / 2f, y + rowH - gapY / 2f)
                // touch areas cover the gaps and stretch to the edges: no dead zones
                val hl = if (ki == 0) keysArea.left else x
                val hr = if (ki == row.keys.size - 1) keysArea.right else x + kw
                val ht = if (ri == 0) keysArea.top else y
                val hb = if (ri == l.rows.size - 1) keysArea.bottom + bottomInset else y + rowH
                k.hit.set(hl, ht, hr, hb)
                x += kw
            }
            y += rowH
        }
    }

    // ------------------------------------------------------------------ drawing

    // The resting keyboard (backdrop + every key unpressed) is recorded once into a GPU layer and
    // reused every frame; only keys that are moving get drawn on top. That keeps each frame cheap
    // even though every key is a refraction shader.
    private val restNode: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("prism-keys") else null
    private var restSig: List<Any?>? = null

    private fun restSignature(l: KeyboardLayout): List<Any?> = listOf(
        l, width, height, shiftState, enterAction, langLabel, settings, glass.generation, oneHandArea.left, oneHandArea.right,
    )

    private fun drawResting(c: Canvas, l: KeyboardLayout, th: Theme) {
        glass.drawBackdrop(c)
        for (k in l.allKeys) drawKey(c, k, th, l, 0f)
        if (!oneHandArea.isEmpty) drawOneHandSide(c, th)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val th = glass.theme
        glass.ensureBackdrop(width, (height - extraTop).toInt(), extraTop, settings?.bgMode ?: "aurora")

        val node = restNode
        val cached = node != null && mode == Mode.KEYS && labelAlpha >= 0.999f && canvas.isHardwareAccelerated
        if (cached && node != null) {
            val sig = restSignature(l)
            if (sig != restSig || !node.hasDisplayList()) {
                node.setPosition(0, 0, width, height)
                node.setUseCompositingLayer(true, null)
                val rc = node.beginRecording(width, height)
                try { drawResting(rc, l, th) } finally { node.endRecording() }
                restSig = sig
            }
            canvas.drawRenderNode(node)
            // only what's moving is drawn live, on top of its resting copy
            for (k in l.allKeys) {
                if (k.press > 0.004f || k.pressTarget > 0f) drawKey(canvas, k, th, l, max(0f, k.press))
            }
            if (glowAlpha > 0f && settings?.glow == true) glass.drawGlow(canvas, glowX, glowY, glowAlpha * 0.5f, 90 * dp)
            drawStrip(canvas, th)
        } else {
            glass.drawBackdrop(canvas)
            if (glowAlpha > 0f && settings?.glow == true) glass.drawGlow(canvas, glowX, glowY, glowAlpha, 90 * dp)
            drawStrip(canvas, th)
            when (mode) {
                Mode.KEYS -> {
                    for (k in l.allKeys) drawKey(canvas, k, th, l, k.press)
                    if (!oneHandArea.isEmpty) drawOneHandSide(canvas, th)
                }
                Mode.EMOJI -> drawEmojiPanel(canvas, th)
                Mode.CLIPBOARD -> drawClipboardPanel(canvas, th)
            }
        }

        // floating stuff
        if (mode == Mode.KEYS) {
            for (p in pointers.values) p.previewKey?.let { drawPreview(canvas, it, p, th) }
            popupPtr?.let { drawPopup(canvas, it, th) }
        }
    }

    private fun tintFor(k: Key, th: Theme): Pair<Int, Float> {
        val isEnterAccent = k.type == KeyType.ENTER && enterHasAction()
        return when {
            isEnterAccent -> th.accent to 0.62f
            k.type == KeyType.SHIFT && shiftState != 0 -> (if (th.dark) Color.WHITE else Color.WHITE) to (if (th.dark) 0.55f else 0.95f)
            k.isModifier && k.type != KeyType.ZWNJ && k.type != KeyType.EMOJI -> th.modTint.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) to Color.alpha(it) / 255f }
            else -> th.keyTint.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) to Color.alpha(it) / 255f }
        }
    }

    private fun enterHasAction(): Boolean = when (enterAction) {
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE -> true
        else -> false
    }

    private fun drawKey(canvas: Canvas, k: Key, th: Theme, l: KeyboardLayout, press: Float) {
        val (tint, ta) = tintFor(k, th)
        glass.drawGlass(canvas, k.rect, radius, press.coerceIn(-0.2f, 1.3f), tint, ta)
        if (labelAlpha <= 0.01f) return
        val shiftOn = shiftState != 0
        val labelColor = if (k.type == KeyType.SHIFT && shiftOn && !th.dark) Color.BLACK
        else if (k.type == KeyType.SHIFT && shiftOn) Color.BLACK
        else if (k.type == KeyType.ENTER && enterHasAction()) Color.WHITE
        else th.label
        val alpha = (labelAlpha * 255).toInt()
        val cx = k.rect.centerX(); val cy = k.rect.centerY()
        val s = min(k.rect.height(), 46 * dp)
        iconPaint.color = labelColor; iconPaint.alpha = alpha
        iconPaint.strokeWidth = 1.8f * dp
        when (k.type) {
            KeyType.SHIFT -> Icons.shift(canvas, cx, cy, s, iconPaint, shiftOn, shiftState == 2)
            KeyType.DELETE -> Icons.delete(canvas, cx, cy, s, iconPaint, false)
            KeyType.LANG -> {
                Icons.globe(canvas, cx, cy - 2 * dp, s * 0.92f, iconPaint)
                hintPaint.typeface = typefaceBold
                hintPaint.textSize = 8.5f * sp
                hintPaint.color = th.labelDim; hintPaint.alpha = (alpha * 0.9f).toInt()
                canvas.drawText(langLabel, cx, k.rect.bottom - 4 * dp, hintPaint)
            }
            KeyType.EMOJI -> Icons.emoji(canvas, cx, cy, s, iconPaint)
            KeyType.ENTER -> when (enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> Icons.search(canvas, cx, cy, s, iconPaint)
                EditorInfo.IME_ACTION_SEND -> Icons.send(canvas, cx, cy, s, iconPaint, l.rtl)
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icons.send(canvas, cx, cy, s * 0.85f, iconPaint, l.rtl)
                EditorInfo.IME_ACTION_DONE -> {
                    canvas.drawLine(cx - s * 0.16f, cy, cx - s * 0.04f, cy + s * 0.12f, iconPaint)
                    canvas.drawLine(cx - s * 0.04f, cy + s * 0.12f, cx + s * 0.18f, cy - s * 0.12f, iconPaint)
                }
                else -> Icons.enter(canvas, cx, cy, s, iconPaint, l.rtl)
            }
            KeyType.SPACE -> {
                labelPaint.typeface = typeface
                labelPaint.textSize = 13f * sp
                labelPaint.color = th.labelDim; labelPaint.alpha = (alpha * 0.85f).toInt()
                drawCentered(canvas, k.label, cx, cy, labelPaint)
            }
            KeyType.ZWNJ -> {
                labelPaint.typeface = typeface
                labelPaint.textSize = 12f * sp
                labelPaint.color = th.label; labelPaint.alpha = alpha
                drawCentered(canvas, k.label, cx, cy, labelPaint)
            }
            KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS -> {
                labelPaint.typeface = typefaceBold
                labelPaint.textSize = 15f * sp
                labelPaint.color = th.label; labelPaint.alpha = alpha
                drawCentered(canvas, k.label, cx, cy, labelPaint)
            }
            KeyType.CHAR -> {
                val label = displayLabel(k)
                labelPaint.typeface = typeface
                labelPaint.textSize = when {
                    l.id == "num" -> 26f * sp
                    label.length > 2 -> 16f * sp
                    l.rtl -> 22f * sp
                    shiftOn && label[0].isLetter() -> 22f * sp
                    else -> 24f * sp
                }
                labelPaint.color = th.label; labelPaint.alpha = alpha
                drawCentered(canvas, label, cx, cy + if (l.id == "num" && k.hint != null) -5 * dp else 0f, labelPaint)
                k.hint?.let { h ->
                    hintPaint.typeface = typeface
                    hintPaint.color = th.labelDim; hintPaint.alpha = (alpha * 0.85f).toInt()
                    if (l.id == "num") {
                        hintPaint.textSize = 9f * sp
                        canvas.drawText(h, cx, k.rect.bottom - 6 * dp, hintPaint)
                    } else {
                        hintPaint.textSize = 10.5f * sp
                        val hx = if (l.rtl) k.rect.left + 8 * dp else k.rect.right - 7 * dp
                        canvas.drawText(shown(h), hx, k.rect.top + 14 * dp, hintPaint)
                    }
                }
            }
        }
    }

    private fun displayLabel(k: Key): String {
        val shiftOn = shiftState != 0
        return if (shiftOn && k.label.length == 1 && k.label[0].isLetter()) k.label.uppercase() else k.label
    }

    /** A lone diacritic (َ ُ ِ ّ …) gets an invisible base so it isn't drawn on a dotted circle. */
    private fun shown(s: String): String =
        if (s.length == 1 && Character.getType(s[0]) == Character.NON_SPACING_MARK.toInt()) "\u00A0$s" else s

    private fun drawCentered(c: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    // --- preview bubble (iOS style)

    private val tmpRect = RectF()

    private fun drawPreview(canvas: Canvas, k: Key, p: Ptr, th: Theme) {
        if (settings?.keyPreview == false || k.type != KeyType.CHAR || popupPtr === p) return
        if (layout?.id == "num") return
        val text = shown(if (p.flick && k.hint != null) k.hint else displayLabel(k))
        val sc = 0.6f + 0.4f * previewScale
        val w = max(k.rect.width() * 1.45f, 52 * dp) * sc
        val h = k.rect.height() * 1.25f * sc
        val cx = k.rect.centerX().coerceIn(w / 2f + 2 * dp, width - w / 2f - 2 * dp)
        val bottom = k.rect.top - 6 * dp
        var top = bottom - h
        if (top < 2 * dp) top = 2 * dp
        tmpRect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val bubbleTint = if (th.dark) Color.rgb(70, 72, 90) else Color.WHITE
        glass.drawGlass(canvas, tmpRect, 16 * dp, 0.3f, bubbleTint, if (th.dark) 0.72f else 0.9f)
        labelPaint.typeface = typeface
        labelPaint.textSize = (if (text.length > 2) 22f else 34f) * sp * sc
        labelPaint.color = th.label; labelPaint.alpha = 255
        drawCentered(canvas, text, tmpRect.centerX(), tmpRect.centerY(), labelPaint)
    }

    // --- long-press popup

    private val popupRect = RectF()
    private var popupCols = 1
    private var popupItemW = 0f
    private var popupItemH = 0f

    private fun popupItems(p: Ptr): List<String> {
        val k = p.key ?: return emptyList()
        val shiftOn = shiftState != 0
        val base = if (k.type == KeyType.CHAR && k.label.length == 1 && k.label[0].isLetter() && k.output == k.label)
            listOf(displayLabel(k)) else emptyList()
        val alts = k.popup.map { if (shiftOn && it.length == 1) it.uppercase() else it }
        return (alts + base).distinct()
    }

    private fun layoutPopup(p: Ptr) {
        val k = p.key ?: return
        val items = popupItems(p)
        if (items.isEmpty()) return
        popupCols = min(items.size, 7)
        val rows = ceil(items.size / popupCols.toFloat()).toInt()
        popupItemW = max(k.rect.width(), 38 * dp)
        if (items.any { it.length > 2 }) popupItemW = max(popupItemW, 56 * dp)
        popupItemH = k.rect.height() * 1.05f
        val w = popupItemW * popupCols + 8 * dp
        val h = popupItemH * rows + 8 * dp
        var left = k.rect.left - 4 * dp
        if (left + w > width - 2 * dp) left = width - 2 * dp - w
        if (left < 2 * dp) left = 2 * dp
        var top = k.rect.top - 6 * dp - h
        if (top < 2 * dp) top = 2 * dp
        popupRect.set(left, top, left + w, top + h)
    }

    private fun popupIndexAt(p: Ptr, x: Float, y: Float): Int {
        val items = popupItems(p)
        if (items.isEmpty()) return -1
        val rows = ceil(items.size / popupCols.toFloat()).toInt()
        val col = floor((x - popupRect.left - 4 * dp) / popupItemW).toInt().coerceIn(0, popupCols - 1)
        // rows stack upward from the bottom row
        val fromBottom = floor((popupRect.bottom - 4 * dp - y) / popupItemH).toInt().coerceIn(0, rows - 1)
        val idx = fromBottom * popupCols + col
        return idx.coerceAtMost(items.size - 1)
    }

    private fun drawPopup(canvas: Canvas, p: Ptr, th: Theme) {
        val items = popupItems(p)
        if (items.isEmpty()) return
        val bubbleTint = if (th.dark) Color.rgb(70, 72, 90) else Color.WHITE
        val sc = 0.85f + 0.15f * popupAnim
        val cx = popupRect.centerX(); val cy = popupRect.bottom
        canvas.save()
        canvas.scale(sc, sc, cx, cy)
        glass.drawGlass(canvas, popupRect, 16 * dp, 0.2f, bubbleTint, if (th.dark) 0.78f else 0.92f)
        val rows = ceil(items.size / popupCols.toFloat()).toInt()
        for ((i, s) in items.withIndex()) {
            val col = i % popupCols
            val row = i / popupCols
            val l = popupRect.left + 4 * dp + col * popupItemW
            val b = popupRect.bottom - 4 * dp - row * popupItemH
            tmpRect.set(l + 2 * dp, b - popupItemH + 2 * dp, l + popupItemW - 2 * dp, b - 2 * dp)
            if (i == p.popupIndex) {
                accentPaint.color = th.accent
                canvas.drawRoundRect(tmpRect, 11 * dp, 11 * dp, accentPaint)
            }
            labelPaint.typeface = typeface
            labelPaint.textSize = (if (s.length > 2) 15f else 24f) * sp
            labelPaint.color = if (i == p.popupIndex) Color.WHITE else th.label
            labelPaint.alpha = 255
            drawCentered(canvas, shown(s), tmpRect.centerX(), tmpRect.centerY(), labelPaint)
        }
        if (rows > 1) { /* rows drawn above */ }
        canvas.restore()
    }

    // --- one-handed side buttons

    private val oneHandBtnSwitch = RectF()
    private val oneHandBtnExpand = RectF()

    private fun drawOneHandSide(canvas: Canvas, th: Theme) {
        val cx = oneHandArea.centerX()
        val s = min(oneHandArea.width() * 0.8f, 46 * dp)
        oneHandBtnSwitch.set(cx - s / 2, oneHandArea.centerY() - s - 8 * dp, cx + s / 2, oneHandArea.centerY() - 8 * dp)
        oneHandBtnExpand.set(cx - s / 2, oneHandArea.centerY() + 8 * dp, cx + s / 2, oneHandArea.centerY() + 8 * dp + s)
        val t = th.modTint
        glass.drawGlass(canvas, oneHandBtnSwitch, s / 2, 0f, Color.rgb(Color.red(t), Color.green(t), Color.blue(t)), Color.alpha(t) / 255f)
        glass.drawGlass(canvas, oneHandBtnExpand, s / 2, 0f, Color.rgb(Color.red(t), Color.green(t), Color.blue(t)), Color.alpha(t) / 255f)
        iconPaint.color = th.label; iconPaint.alpha = 255; iconPaint.strokeWidth = 1.8f * dp
        Icons.chevron(canvas, oneHandBtnSwitch.centerX(), oneHandBtnSwitch.centerY(), s, iconPaint, settings?.oneHand == 2)
        Icons.expand(canvas, oneHandBtnExpand.centerX(), oneHandBtnExpand.centerY(), s, iconPaint)
    }

    // --- strip

    private fun drawStrip(canvas: Canvas, th: Theme) {
        val w = width.toFloat()
        val top = stripArea.top
        val h = stripH
        toolbarBtns.clear()
        clipChipRect.setEmpty()
        when (mode) {
            Mode.EMOJI -> { drawEmojiTabs(canvas, th); return }
            Mode.CLIPBOARD -> { drawClipboardHeader(canvas, th); return }
            Mode.KEYS -> {}
        }
        val slots = suggestions.slots
        val hasSuggestions = showSuggestions && slots.any { it != null }
        if (hasSuggestions) {
            val sw = w / 3f
            // physical order: in RTL the "left" slot goes on the right
            val rtl = layout?.rtl == true
            for (i in 0 until 3) {
                val pos = if (rtl) 2 - i else i
                stripSlots[i].set(pos * sw, top, (pos + 1) * sw, top + h)
            }
            dividerPaint.color = th.labelDim; dividerPaint.alpha = 50
            canvas.drawLine(sw, top + h * 0.3f, sw, top + h * 0.7f, dividerPaint)
            canvas.drawLine(2 * sw, top + h * 0.3f, 2 * sw, top + h * 0.7f, dividerPaint)
            for (i in 0 until 3) {
                val s = slots[i] ?: continue
                val r = stripSlots[i]
                val highlighted = i == 1 && (suggestions.autoCorrect != null || s.kind == Suggestion.Kind.LAYOUT_FIX || s.kind == Suggestion.Kind.SHORTCUT)
                if (highlighted) {
                    tmpRect.set(r.left + 6 * dp, r.top + 6 * dp, r.right - 6 * dp, r.bottom - 6 * dp)
                    glass.drawGlass(canvas, tmpRect, tmpRect.height() / 2f, 0f, th.accent, 0.38f, shadow = false)
                }
                stripPaint.typeface = if (i == 1) typefaceBold else typeface
                stripPaint.textSize = 16f * sp
                stripPaint.color = if (highlighted) Color.WHITE else th.label
                if (i != 1 && !highlighted) stripPaint.alpha = 225
                var text = s.text
                if (s.kind == Suggestion.Kind.TYPED && !suggestions.typedValid) text = "«$text»"
                val shown = TextUtils.ellipsize(text, stripPaint, r.width() - 16 * dp, TextUtils.TruncateAt.END).toString()
                drawCentered(canvas, shown, r.centerX(), r.centerY(), stripPaint)
            }
        } else {
            // toolbar
            val s = 36 * dp
            var x = 8 * dp
            val cy = top + h / 2f
            val actions = listOf(Toolbar.SETTINGS, Toolbar.CLIPBOARD, Toolbar.ONE_HAND)
            val t = th.modTint
            for (a in actions) {
                val r = RectF(x, cy - s / 2, x + s, cy + s / 2)
                toolbarBtns += a to r
                glass.drawGlass(canvas, r, s / 2, 0f, Color.rgb(Color.red(t), Color.green(t), Color.blue(t)), Color.alpha(t) / 255f, shadow = false)
                iconPaint.color = th.label; iconPaint.alpha = 230; iconPaint.strokeWidth = 1.6f * dp
                when (a) {
                    Toolbar.SETTINGS -> Icons.gear(canvas, r.centerX(), r.centerY(), s, iconPaint)
                    Toolbar.CLIPBOARD -> Icons.clipboard(canvas, r.centerX(), r.centerY(), s, iconPaint)
                    Toolbar.EMOJI -> Icons.emoji(canvas, r.centerX(), r.centerY(), s * 0.95f, iconPaint)
                    Toolbar.ONE_HAND -> Icons.oneHand(canvas, r.centerX(), r.centerY(), s, iconPaint)
                    else -> {}
                }
                x += s + 8 * dp
            }
            val hide = RectF(w - 8 * dp - s, cy - s / 2, w - 8 * dp, cy + s / 2)
            toolbarBtns += Toolbar.HIDE to hide
            iconPaint.color = th.label; iconPaint.alpha = 230; iconPaint.strokeWidth = 2f * dp
            val hcx = hide.centerX(); val hcy = hide.centerY(); val hs = s * 0.18f
            canvas.drawLine(hcx - hs, hcy - hs * 0.45f, hcx, hcy + hs * 0.55f, iconPaint)
            canvas.drawLine(hcx, hcy + hs * 0.55f, hcx + hs, hcy - hs * 0.45f, iconPaint)
            clipChip?.let { chip ->
                val left = x + 8 * dp
                val right = hide.left - 6 * dp
                if (right - left > 60 * dp) {
                    stripPaint.typeface = typeface
                    stripPaint.textSize = 13.5f * sp
                    val label = TextUtils.ellipsize(chip.replace('\n', ' '), stripPaint, right - left - 40 * dp, TextUtils.TruncateAt.END).toString()
                    val tw = stripPaint.measureText(label) + 44 * dp
                    clipChipRect.set(right - tw, cy - s / 2, right, cy + s / 2)
                    glass.drawGlass(canvas, clipChipRect, s / 2, 0f, th.accent, 0.35f, shadow = false)
                    iconPaint.color = th.label; iconPaint.alpha = 255
                    Icons.clipboard(canvas, clipChipRect.left + 18 * dp, cy, s * 0.8f, iconPaint)
                    stripPaint.color = th.label
                    drawCentered(canvas, label, clipChipRect.left + 32 * dp + (tw - 44 * dp) / 2f, cy, stripPaint)
                }
            }
        }
    }

    // ------------------------------------------------------------------ panels (emoji / clipboard)

    private val panelKeys = ArrayList<Key>()
    private var panelAbc: Key? = null
    private var panelSpace: Key? = null
    private var panelDel: Key? = null
    private val panelContent = RectF()
    private var panelScroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null

    private class EmojiSection(val icon: String, val items: List<String>, var top: Float = 0f)
    private var emojiSections: List<EmojiSection>? = null
    private var emojiCell = 44f
    private var emojiCols = 8
    private var emojiContentH = 0f

    private fun layoutPanelKeys() {
        panelKeys.clear()
        if (width <= 0) return
        val barH = 46 * dp
        val area = RectF(0f, extraTop + stripH, width.toFloat(), extraTop + stripH + keysH)
        val y0 = area.bottom - barH - 4 * dp
        val unit = (width - 6 * dp) / 10f
        val abcLabel = if (layout?.rtl == true) "ابپ" else "ABC"
        val abc = Key(KeyType.LETTERS, abcLabel, width = 1.6f)
        val space = Key(KeyType.SPACE, if (layout?.rtl == true) "فاصله" else "space", " ", 6.4f)
        val del = Key(KeyType.DELETE, "del", width = 2f)
        var x = 3 * dp
        for (k in listOf(abc, space, del)) {
            val kw = k.width * unit
            k.rect.set(x + gapX / 2, y0, x + kw - gapX / 2, y0 + barH - 6 * dp)
            k.hit.set(x, y0 - 4 * dp, x + kw, area.bottom + bottomInset)
            x += kw
            panelKeys += k
        }
        panelAbc = abc; panelSpace = space; panelDel = del
        panelContent.set(area.left + 4 * dp, area.top + 2 * dp, area.right - 4 * dp, y0 - 4 * dp)
        emojiCols = max(6, (panelContent.width() / (46 * dp)).toInt())
        emojiCell = panelContent.width() / emojiCols
    }

    private fun sections(): List<EmojiSection> {
        emojiSections?.let { return it }
        val list = ArrayList<EmojiSection>()
        val probe = Paint()
        if (recentEmoji.isNotEmpty()) list += EmojiSection("🕘", recentEmoji)
        for (c in EmojiData.categories) {
            val items = c.items.filter { Build.VERSION.SDK_INT < 23 || probe.hasGlyph(it) }
            if (items.isNotEmpty()) list += EmojiSection(c.icon, items)
        }
        var y = 0f
        for (s in list) {
            s.top = y
            y += ceil(s.items.size / emojiCols.toFloat()) * emojiCell + 10 * dp
        }
        emojiContentH = y
        emojiSections = list
        return list
    }

    private fun maxPanelScroll(): Float = when (mode) {
        Mode.EMOJI -> max(0f, emojiContentH - panelContent.height())
        Mode.CLIPBOARD -> max(0f, clipContentH - panelContent.height())
        else -> 0f
    }

    private fun drawPanelBar(canvas: Canvas, th: Theme) {
        for (k in panelKeys) {
            val (tint, ta) = tintFor(k, th)
            glass.drawGlass(canvas, k.rect, radius, k.press, tint, ta)
            iconPaint.color = th.label; iconPaint.alpha = 255; iconPaint.strokeWidth = 1.8f * dp
            when (k.type) {
                KeyType.DELETE -> Icons.delete(canvas, k.rect.centerX(), k.rect.centerY(), k.rect.height(), iconPaint, false)
                KeyType.SPACE -> {
                    labelPaint.typeface = typeface; labelPaint.textSize = 13f * sp
                    labelPaint.color = th.labelDim; labelPaint.alpha = 220
                    drawCentered(canvas, k.label, k.rect.centerX(), k.rect.centerY(), labelPaint)
                }
                else -> {
                    labelPaint.typeface = typefaceBold; labelPaint.textSize = 15f * sp
                    labelPaint.color = th.label; labelPaint.alpha = 255
                    drawCentered(canvas, k.label, k.rect.centerX(), k.rect.centerY(), labelPaint)
                }
            }
        }
    }

    private fun drawEmojiTabs(canvas: Canvas, th: Theme) {
        val secs = sections()
        val n = secs.size
        if (n == 0) return
        val w = width.toFloat() / n
        val cur = currentSection()
        toolbarBtns.clear()
        for ((i, s) in secs.withIndex()) {
            val r = RectF(i * w, stripArea.top, (i + 1) * w, stripArea.bottom)
            if (i == cur) {
                val sz = min(w, stripH) - 8 * dp
                tmpRect.set(r.centerX() - sz / 2, r.centerY() - sz / 2, r.centerX() + sz / 2, r.centerY() + sz / 2)
                glass.drawGlass(canvas, tmpRect, sz / 2, 0f, th.accent, 0.35f, shadow = false)
            }
            emojiPaint.textSize = 18f * sp
            emojiPaint.alpha = if (i == cur) 255 else 170
            drawCentered(canvas, s.icon, r.centerX(), r.centerY(), emojiPaint)
        }
    }

    private fun currentSection(): Int {
        val secs = emojiSections ?: return 0
        var cur = 0
        for ((i, s) in secs.withIndex()) if (s.top <= panelScroll + emojiCell * 0.5f) cur = i
        return cur
    }

    private fun drawEmojiPanel(canvas: Canvas, th: Theme) {
        val secs = sections()
        canvas.save()
        canvas.clipRect(panelContent)
        emojiPaint.textSize = 27f * sp
        emojiPaint.alpha = 255
        val top = panelContent.top - panelScroll
        for (s in secs) {
            val sTop = top + s.top
            val rows = ceil(s.items.size / emojiCols.toFloat()).toInt()
            if (sTop > panelContent.bottom || sTop + rows * emojiCell < panelContent.top) continue
            for ((i, e) in s.items.withIndex()) {
                val r = i / emojiCols; val c = i % emojiCols
                val cy = sTop + r * emojiCell + emojiCell / 2f
                if (cy < panelContent.top - emojiCell || cy > panelContent.bottom + emojiCell) continue
                val cx = panelContent.left + c * emojiCell + emojiCell / 2f
                if (pressedEmoji == e && pressedEmojiSection === s) {
                    tmpRect.set(cx - emojiCell / 2 + 2 * dp, cy - emojiCell / 2 + 2 * dp, cx + emojiCell / 2 - 2 * dp, cy + emojiCell / 2 - 2 * dp)
                    accentPaint.color = GlassRenderer.adjustAlpha(th.accent, 0.45f)
                    canvas.drawRoundRect(tmpRect, 12 * dp, 12 * dp, accentPaint)
                }
                drawCentered(canvas, e, cx, cy, emojiPaint)
            }
        }
        canvas.restore()
        drawPanelBar(canvas, th)
    }

    private var pressedEmoji: String? = null
    private var pressedEmojiSection: Any? = null

    private fun emojiAt(x: Float, y: Float): Pair<String, Any>? {
        if (!panelContent.contains(x, y)) return null
        val cy = y - panelContent.top + panelScroll
        for (s in sections()) {
            val rows = ceil(s.items.size / emojiCols.toFloat()).toInt()
            if (cy >= s.top && cy < s.top + rows * emojiCell) {
                val r = ((cy - s.top) / emojiCell).toInt()
                val c = ((x - panelContent.left) / emojiCell).toInt().coerceIn(0, emojiCols - 1)
                val idx = r * emojiCols + c
                return if (idx < s.items.size) s.items[idx] to s else null
            }
        }
        return null
    }

    // --- clipboard

    private class Card(val item: ClipboardStore.Item, val rect: RectF, val layout: StaticLayout)
    private val cardCache = ArrayList<Card>()
    private var clipContentH = 0f

    private fun clipCards(th: Theme): List<Card> {
        if (cardCache.isNotEmpty()) return cardCache
        val items = clipboardStore?.all() ?: emptyList()
        val cols = 2
        val gap = 8 * dp
        val cw = (panelContent.width() - gap) / cols
        val ch = 70 * dp
        cardText.textSize = 13.5f * sp
        cardText.typeface = typeface
        cardText.color = th.label
        for ((i, it) in items.withIndex()) {
            val c = i % cols; val r = i / cols
            val rect = RectF(c * (cw + gap), r * (ch + gap), c * (cw + gap) + cw, r * (ch + gap) + ch)
            val text = it.text.take(300)
            val sl = StaticLayout.Builder.obtain(text, 0, text.length, cardText, (cw - 20 * dp).toInt())
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            cardCache += Card(it, rect, sl)
        }
        clipContentH = if (items.isEmpty()) 0f else ceil(items.size / cols.toFloat()) * (ch + gap)
        return cardCache
    }

    private fun drawClipboardHeader(canvas: Canvas, th: Theme) {
        stripPaint.typeface = typefaceBold
        stripPaint.textSize = 15f * sp
        stripPaint.color = th.label
        drawCentered(canvas, "کلیپ‌بورد", width / 2f, stripArea.centerY(), stripPaint)
        val s = 34 * dp
        val cy = stripArea.centerY()
        val close = RectF(8 * dp, cy - s / 2, 8 * dp + s, cy + s / 2)
        val clear = RectF(width - 8 * dp - s, cy - s / 2, width - 8 * dp, cy + s / 2)
        val t = th.modTint
        for ((a, r) in listOf(Toolbar.CLOSE_PANEL to close, Toolbar.CLEAR_CLIPBOARD to clear)) {
            toolbarBtns += a to r
            glass.drawGlass(canvas, r, s / 2, 0f, Color.rgb(Color.red(t), Color.green(t), Color.blue(t)), Color.alpha(t) / 255f, shadow = false)
            iconPaint.color = th.label; iconPaint.alpha = 255; iconPaint.strokeWidth = 1.6f * dp
            if (a == Toolbar.CLOSE_PANEL) Icons.close(canvas, r.centerX(), r.centerY(), s, iconPaint)
            else Icons.trash(canvas, r.centerX(), r.centerY(), s, iconPaint)
        }
    }

    private fun drawClipboardPanel(canvas: Canvas, th: Theme) {
        val cards = clipCards(th)
        canvas.save()
        canvas.clipRect(panelContent)
        if (cards.isEmpty()) {
            stripPaint.typeface = typeface; stripPaint.textSize = 14f * sp
            stripPaint.color = th.labelDim
            drawCentered(canvas, "هنوز چیزی کپی نکردی", panelContent.centerX(), panelContent.centerY() - 10 * dp, stripPaint)
            stripPaint.textSize = 12f * sp
            drawCentered(canvas, "متن‌هایی که کپی می‌کنی اینجا جمع می‌شن (فقط روی گوشی خودت)", panelContent.centerX(), panelContent.centerY() + 14 * dp, stripPaint)
        }
        val (tint, ta) = th.keyTint.let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) to Color.alpha(it) / 255f }
        for (c in cards) {
            tmpRect.set(c.rect)
            tmpRect.offset(panelContent.left, panelContent.top - panelScroll)
            if (tmpRect.bottom < panelContent.top || tmpRect.top > panelContent.bottom) continue
            val pressed = pressedCard === c
            glass.drawGlass(canvas, tmpRect, 14 * dp, if (pressed) 1f else 0f, if (c.item.pinned) th.accent else tint,
                if (c.item.pinned) 0.3f else ta, shadow = false)
            canvas.save()
            canvas.translate(tmpRect.left + 10 * dp, tmpRect.top + 8 * dp)
            cardText.color = th.label
            c.layout.draw(canvas)
            canvas.restore()
            if (c.item.pinned) {
                iconPaint.color = th.label; iconPaint.alpha = 220; iconPaint.strokeWidth = 1.4f * dp
                Icons.pin(canvas, tmpRect.right - 12 * dp, tmpRect.bottom - 14 * dp, 28 * dp, iconPaint)
            }
        }
        canvas.restore()
        drawPanelBar(canvas, th)
    }

    private var pressedCard: Card? = null

    private fun cardAt(x: Float, y: Float): Card? {
        if (!panelContent.contains(x, y)) return null
        val lx = x - panelContent.left
        val ly = y - panelContent.top + panelScroll
        return cardCache.firstOrNull { it.rect.contains(lx, ly) }
    }

    // ------------------------------------------------------------------ touch

    private enum class PMode { KEY, TRACKPAD, DELETE_SWIPE, STRIP, PANEL_SCROLL, PANEL_TAP, PANEL_KEY, ONE_HAND, NONE }

    private inner class Ptr(val id: Int) {
        var key: Key? = null
        var previewKey: Key? = null
        var mode = PMode.NONE
        var downX = 0f; var downY = 0f
        var x = 0f; var y = 0f
        var lastX = 0f; var lastY = 0f
        var downTime = 0L
        var longFired = false
        var popupIndex = -1
        var flick = false
        /** the alternate came from holding the key (not a flick), so moving the finger keeps it */
        var flickByLong = false
        var downKey: Key? = null
        var committed = false
        var accX = 0f; var accY = 0f
        var repeat = 0
        var swipeWords = 0
        var stripIndex = -1
        var toolbar: Toolbar? = null
        val longPress = Runnable { onLongPress(this) }
        val repeater = Runnable { onRepeat(this) }
    }

    private val pointers = HashMap<Int, Ptr>()
    private var popupPtr: Ptr? = null

    private fun activePointers() = pointers.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx)
                val x = e.getX(idx); val y = e.getY(idx)
                if (y < extraTop) return e.actionMasked != MotionEvent.ACTION_DOWN
                if (mode != Mode.KEYS && e.actionMasked == MotionEvent.ACTION_DOWN) {
                    velocity?.recycle(); velocity = VelocityTracker.obtain()
                }
                velocity?.addMovement(e)
                down(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    move(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                velocity?.addMovement(e)
                val p = pointers[e.getPointerId(idx)]
                if (p != null) {
                    move(p, e.getX(idx), e.getY(idx))
                    up(p)
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) { velocity?.recycle(); velocity = null }
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun cancelAllPointers() {
        for (p in pointers.values) {
            ui.removeCallbacks(p.longPress); ui.removeCallbacks(p.repeater)
            p.key?.pressTarget = 0f
            if (p.mode == PMode.TRACKPAD) { labelAlphaTarget = 1f; listener?.onTrackpad(false) }
            if (p.mode == PMode.DELETE_SWIPE) listener?.onDeleteWords(0, true)
        }
        pointers.clear()
        popupPtr = null
        pressedEmoji = null; pressedCard = null
        kick()
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        val s = settings
        var tx = x; var ty = y
        if (s?.adaptiveTouch == true) { tx -= touchOffX; ty -= touchOffY }
        for (k in l.allKeys) if (k.hit.contains(tx, ty)) return k
        // nearest key fallback (e.g. finger slightly outside)
        var best: Key? = null; var bd = Float.MAX_VALUE
        for (k in l.allKeys) {
            val d = hypot(k.rect.centerX() - tx, k.rect.centerY() - ty)
            if (d < bd) { bd = d; best = k }
        }
        return if (bd < rowPitch) best else null
    }

    private fun down(id: Int, x: Float, y: Float) {
        val p = Ptr(id)
        p.downX = x; p.downY = y; p.x = x; p.y = y; p.lastX = x; p.lastY = y
        p.downTime = System.currentTimeMillis()
        pointers[id] = p
        glowX = x; glowY = y; if (settings?.glow == true) glowAlpha = 1f

        // strip
        if (stripArea.contains(x, y)) {
            p.mode = PMode.STRIP
            p.toolbar = toolbarBtns.firstOrNull { it.second.contains(x, y) }?.first
            if (p.toolbar == null && mode == Mode.EMOJI) {
                val n = sections().size
                if (n > 0) p.stripIndex = (x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
            } else if (p.toolbar == null && !clipChipRect.isEmpty && clipChipRect.contains(x, y)) {
                p.stripIndex = 100
            } else if (p.toolbar == null) {
                p.stripIndex = stripSlots.indexOfFirst { it.contains(x, y) }
                if (p.stripIndex >= 0 && suggestions.slots[p.stripIndex] != null) {
                    ui.postDelayed(p.longPress, 500)
                }
            }
            if (p.toolbar != null || p.stripIndex >= 0) listener?.onKeyFeedback(null, FEEDBACK_LIGHT)
            kick()
            return
        }

        if (mode != Mode.KEYS) { panelDown(p, x, y); return }

        if (!oneHandArea.isEmpty && oneHandArea.contains(x, y)) {
            p.mode = PMode.ONE_HAND
            p.toolbar = when {
                oneHandBtnSwitch.contains(x, y) -> Toolbar.ONE_HAND_SWITCH
                oneHandBtnExpand.contains(x, y) -> Toolbar.ONE_HAND_OFF
                else -> null
            }
            if (p.toolbar != null) listener?.onKeyFeedback(null, FEEDBACK_LIGHT)
            return
        }

        val k = keyAt(x, y) ?: return
        // rollover: a second finger lands while the first is still on a character or space —
        // type the first one now so fast typing keeps its order
        for (o in pointers.values) {
            if (o === p || o.mode != PMode.KEY || o.committed || popupPtr === o) continue
            when (o.key?.type) {
                KeyType.CHAR -> commitChar(o)
                KeyType.SPACE -> if (!o.longFired) listener?.onSpace() else continue
                else -> continue
            }
            o.committed = true
            o.key?.pressTarget = 0f
            o.previewKey = null
            ui.removeCallbacks(o.longPress)
        }
        p.key = k
        p.downKey = k
        p.mode = PMode.KEY
        k.pressTarget = 1f
        k.press = max(k.press, 0.85f); k.pressVel = 0f
        if (k.type == KeyType.CHAR || k.type == KeyType.ZWNJ) p.previewKey = if (k.type == KeyType.CHAR) k else null
        if (p.previewKey != null) previewScale = 1f
        invalidate()

        listener?.onKeyFeedback(k, FEEDBACK_DOWN)
        val lp = (settings?.longPressMs ?: 320).toLong()
        when (k.type) {
            KeyType.DELETE -> {
                listener?.onDelete(0)
                ui.postDelayed(p.repeater, 380)
            }
            KeyType.SHIFT -> listener?.onShiftDown()
            KeyType.SPACE -> ui.postDelayed(p.longPress, lp + 60)
            KeyType.LANG -> ui.postDelayed(p.longPress, 450)
            KeyType.CHAR, KeyType.ZWNJ, KeyType.EMOJI -> if (k.popup.isNotEmpty() || k.hint != null) ui.postDelayed(p.longPress, lp)
            else -> {}
        }
        kick()
    }

    private fun move(p: Ptr, x: Float, y: Float) {
        val dx = x - p.lastX; val dy = y - p.lastY
        p.lastX = x; p.lastY = y
        p.x = x; p.y = y
        if (p.mode != PMode.PANEL_SCROLL && p.mode != PMode.PANEL_TAP) { glowX = x; glowY = y; if (settings?.glow == true) glowAlpha = 1f }
        when (p.mode) {
            PMode.KEY -> {
                val k = p.key ?: return
                if (popupPtr === p) {
                    val i = popupIndexAt(p, x, y)
                    if (i != p.popupIndex) { p.popupIndex = i; listener?.onKeyFeedback(null, FEEDBACK_TICK); invalidate() }
                    return
                }
                when (k.type) {
                    KeyType.SPACE -> {
                        // a quick tap on space often slides a little: only a clear drag moves the cursor
                        if (abs(x - p.downX) > max(24 * dp, k.rect.width() * 0.14f) && !p.longFired) {
                            ui.removeCallbacks(p.longPress)
                            startTrackpad(p)
                        }
                    }
                    KeyType.DELETE -> {
                        if (settings?.swipeDelete != false && p.downX - x > 26 * dp) {
                            ui.removeCallbacks(p.repeater)
                            p.mode = PMode.DELETE_SWIPE
                            updateDeleteSwipe(p)
                        }
                    }
                    KeyType.CHAR -> {
                        if (p.flickByLong) return
                        val upDist = p.downY - y
                        // a deliberate flick up: well past the key's middle and mostly vertical,
                        // so a thumb rolling off a key while tapping doesn't type the corner letter
                        if (k.hint != null && upDist > k.rect.height() * 0.7f && abs(x - p.downX) < upDist * 0.7f) {
                            if (!p.flick) { p.flick = true; ui.removeCallbacks(p.longPress); invalidate() }
                            return
                        } else if (p.flick && upDist < k.rect.height() * 0.4f) {
                            p.flick = false; invalidate()
                        }
                        if (!p.flick) {
                            // slide to correct: only once the finger is clearly inside another key,
                            // so the small drift of a fast tap never changes the letter
                            val outX = max(0f, max(k.hit.left - x, x - k.hit.right))
                            val outY = max(0f, max(k.hit.top - y, y - k.hit.bottom))
                            val nk = if (outX > k.rect.width() * 0.4f || outY > k.rect.height() * 0.4f) keyAt(x, y) else null
                            if (nk != null && nk !== k && nk.type == KeyType.CHAR) {
                                k.pressTarget = 0f
                                nk.pressTarget = 1f
                                p.key = nk
                                p.previewKey = nk
                                ui.removeCallbacks(p.longPress)
                                if (nk.popup.isNotEmpty() || nk.hint != null) ui.postDelayed(p.longPress, (settings?.longPressMs ?: 320).toLong())
                                kick()
                            }
                        }
                    }
                    else -> {}
                }
            }
            PMode.TRACKPAD -> trackpadMove(p, dx, dy)
            PMode.DELETE_SWIPE -> updateDeleteSwipe(p)
            PMode.PANEL_TAP, PMode.PANEL_SCROLL -> panelMove(p, dx, dy)
            else -> {}
        }
        kick()
    }

    private fun up(p: Ptr) {
        pointers.remove(p.id)
        ui.removeCallbacks(p.longPress)
        ui.removeCallbacks(p.repeater)
        when (p.mode) {
            PMode.STRIP -> stripUp(p)
            PMode.ONE_HAND -> p.toolbar?.let { if (oneHandBtnSwitch.contains(p.x, p.y) || oneHandBtnExpand.contains(p.x, p.y)) listener?.onToolbar(it) }
            PMode.KEY -> {
                val k = p.key
                if (k != null) {
                    k.pressTarget = 0f
                    if (popupPtr === p) {
                        val items = popupItems(p)
                        if (p.popupIndex in items.indices) listener?.onChar(items[p.popupIndex], true)
                        popupPtr = null
                    } else if (!p.committed) {
                        when (k.type) {
                            KeyType.CHAR, KeyType.ZWNJ -> { commitChar(p); learnTouch(p, k) }
                            KeyType.SPACE -> if (!p.longFired) listener?.onSpace()
                            KeyType.ENTER -> listener?.onEnter()
                            KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS -> listener?.onSwitchPage(k.type)
                            KeyType.LANG -> if (!p.longFired) listener?.onLang()
                            KeyType.EMOJI -> if (!p.longFired) listener?.onToolbar(Toolbar.EMOJI)
                            else -> {}
                        }
                    }
                }
                p.previewKey = null
            }
            PMode.TRACKPAD -> {
                p.key?.pressTarget = 0f
                labelAlphaTarget = 1f
                listener?.onTrackpad(false)
            }
            PMode.DELETE_SWIPE -> {
                p.key?.pressTarget = 0f
                listener?.onDeleteWords(p.swipeWords, true)
            }
            PMode.PANEL_TAP, PMode.PANEL_SCROLL, PMode.PANEL_KEY -> panelUp(p)
            PMode.NONE -> {}
        }
        kick()
    }

    private fun commitChar(p: Ptr) {
        val k = p.key ?: return
        var text = if (p.flick && k.hint != null) k.hint else k.output
        if (!p.flick && k === p.downKey && k.type == KeyType.CHAR && k.output.length == 1 && k.output[0].isLetter()) {
            val alt = borderNeighbour(k, p.downX, p.downY)
            if (alt != null && charResolver?.invoke(text, alt.output) == true) text = alt.output
        }
        listener?.onChar(text, p.flick)
    }

    /** The letter next to [k] if the tap landed close to the border between them, else null. */
    private fun borderNeighbour(k: Key, x: Float, y: Float): Key? {
        val l = layout ?: return null
        var tx = x; var ty = y
        if (settings?.adaptiveTouch == true) { tx -= touchOffX; ty -= touchOffY }
        val kw = k.hit.width(); val kh = k.hit.height()
        if (kw <= 0f || kh <= 0f) return null
        fun dist(o: Key) = hypot((tx - o.hit.centerX()) / kw, (ty - o.hit.centerY()) / kh)
        val dk = dist(k)
        var best: Key? = null; var bd = Float.MAX_VALUE
        for (o in l.allKeys) {
            if (o === k || o.type != KeyType.CHAR || o.output.length != 1 || !o.output[0].isLetter()) continue
            val d = dist(o)
            if (d < bd) { bd = d; best = o }
        }
        // within about a fifth of a key of the border
        return if (best != null && bd - dk < 0.22f) best else null
    }

    private fun onLongPress(p: Ptr) {
        if (!pointers.containsKey(p.id)) return
        p.longFired = true
        if (p.mode == PMode.STRIP) {
            val s = suggestions.slots.getOrNull(p.stripIndex) ?: return
            listener?.onSuggestionLongPress(s)
            p.stripIndex = -1
            return
        }
        if (p.mode == PMode.PANEL_TAP) {
            // long-press on a clipboard card pins it
            pressedCard?.let { clipboardStore?.togglePin(it.item); cardCache.clear(); listener?.onKeyFeedback(null, FEEDBACK_HEAVY) }
            pressedCard = null
            p.mode = PMode.NONE
            invalidate()
            return
        }
        val k = p.key ?: return
        when (k.type) {
            KeyType.SPACE -> startTrackpad(p)
            KeyType.LANG -> { k.pressTarget = 0f; listener?.onLangLongPress() }
            KeyType.CHAR, KeyType.ZWNJ, KeyType.EMOJI -> {
                if (p.flick) return
                val items = popupItems(p)
                if (items.isEmpty()) return
                if (items.size == 1 && k.hint != null && k.popup.size <= 1) {
                    // a single alternate: just switch the preview to it, typed on release
                    p.flick = true
                    p.flickByLong = true
                    listener?.onKeyFeedback(null, FEEDBACK_TICK)
                    invalidate()
                    return
                }
                popupPtr = p
                p.popupIndex = 0
                p.previewKey = null
                layoutPopup(p)
                listener?.onKeyFeedback(null, FEEDBACK_TICK)
                kick()
            }
            else -> {}
        }
    }

    private fun onRepeat(p: Ptr) {
        if (!pointers.containsKey(p.id) || p.mode != PMode.KEY) return
        p.repeat++
        listener?.onDelete(p.repeat)
        val delay = when {
            p.repeat < 6 -> 70L
            p.repeat < 14 -> 50L
            else -> 110L // word mode: slower, one word at a time
        }
        ui.postDelayed(p.repeater, delay)
    }

    // --- space trackpad (iOS-style)

    private fun startTrackpad(p: Ptr) {
        if (p.mode == PMode.TRACKPAD) return
        p.mode = PMode.TRACKPAD
        p.longFired = true
        p.previewKey = null
        p.accX = 0f; p.accY = 0f
        labelAlphaTarget = 0f
        listener?.onTrackpad(true)
        listener?.onKeyFeedback(null, FEEDBACK_HEAVY)
        kick()
    }

    private fun trackpadMove(p: Ptr, dx: Float, dy: Float) {
        val speed = (settings?.trackpadSpeed ?: 100) / 100f
        val stepX = 9.5f * dp / speed
        val stepY = 26f * dp / speed
        // acceleration: quick swipes travel further
        val accel = 1f + min(2.5f, abs(dx) / (14f * dp))
        p.accX += dx * accel
        p.accY += dy
        var mx = 0
        while (p.accX >= stepX) { p.accX -= stepX; mx++ }
        while (p.accX <= -stepX) { p.accX += stepX; mx-- }
        var my = 0
        while (p.accY >= stepY) { p.accY -= stepY; my++ }
        while (p.accY <= -stepY) { p.accY += stepY; my-- }
        if (mx != 0 || my != 0) listener?.onCursor(mx, my)
    }

    // --- swipe-to-delete words

    private fun updateDeleteSwipe(p: Ptr) {
        val dist = p.downX - p.x - 26 * dp
        val n = if (dist < 0) 0 else 1 + (dist / (28 * dp)).toInt()
        if (n != p.swipeWords) {
            p.swipeWords = n
            listener?.onDeleteWords(n, false)
            listener?.onKeyFeedback(null, FEEDBACK_TICK)
        }
    }

    // --- adaptive touch: learn where this person's thumb actually lands

    private var touchOffX = 0f
    private var touchOffY = 0f
    var onTouchModelChanged: ((Float, Float) -> Unit)? = null
    private var touchSamples = 0

    fun setTouchModel(x: Float, y: Float) { touchOffX = x; touchOffY = y }

    private fun learnTouch(p: Ptr, k: Key) {
        if (settings?.adaptiveTouch != true || p.flick || k.type != KeyType.CHAR) return
        if (hypot(p.x - p.downX, p.y - p.downY) > 8 * dp) return
        val ox = (p.downX - k.rect.centerX()).coerceIn(-k.rect.width() / 2, k.rect.width() / 2)
        val oy = (p.downY - k.rect.centerY()).coerceIn(-k.rect.height() / 2, k.rect.height() / 2)
        // slow exponential average, capped so the layout never feels "off"
        touchOffX = (touchOffX * 0.985f + ox * 0.015f * 0.5f).coerceIn(-4 * dp, 4 * dp)
        touchOffY = (touchOffY * 0.985f + oy * 0.015f * 0.5f).coerceIn(-6 * dp, 6 * dp)
        if (++touchSamples % 50 == 0) onTouchModelChanged?.invoke(touchOffX, touchOffY)
    }

    // --- strip

    private fun stripUp(p: Ptr) {
        val t = p.toolbar
        if (t != null) {
            val r = toolbarBtns.firstOrNull { it.first == t }?.second
            if (r == null || r.contains(p.x, p.y) || abs(p.y - p.downY) < 30 * dp) listener?.onToolbar(t)
            return
        }
        if (p.longFired) return
        if (mode == Mode.EMOJI) {
            val secs = sections()
            if (p.stripIndex in secs.indices) {
                scroller.forceFinished(true)
                panelScroll = secs[p.stripIndex].top.coerceIn(0f, maxPanelScroll())
                invalidate()
            }
            return
        }
        if (p.stripIndex == 100) { clipChip?.let { listener?.onPasteClip(it) }; return }
        val s = suggestions.slots.getOrNull(p.stripIndex) ?: return
        listener?.onSuggestion(s)
    }

    // --- panels

    private fun panelDown(p: Ptr, x: Float, y: Float) {
        val k = panelKeys.firstOrNull { it.hit.contains(x, y) }
        if (k != null) {
            p.key = k
            p.mode = PMode.PANEL_KEY
            k.pressTarget = 1f
            listener?.onKeyFeedback(k, FEEDBACK_DOWN)
            if (k.type == KeyType.DELETE) {
                listener?.onDelete(0)
                ui.postDelayed(p.repeater, 380)
                p.mode = PMode.KEY // reuse the repeat logic
            }
            kick()
            return
        }
        scroller.forceFinished(true)
        p.mode = PMode.PANEL_TAP
        if (mode == Mode.EMOJI) {
            val hit = emojiAt(x, y)
            pressedEmoji = hit?.first; pressedEmojiSection = hit?.second
        } else {
            pressedCard = cardAt(x, y)
            if (pressedCard != null) ui.postDelayed(p.longPress, 480)
        }
        invalidate()
    }

    private fun panelMove(p: Ptr, dx: Float, dy: Float) {
        if (p.mode == PMode.PANEL_TAP && abs(p.y - p.downY) > 8 * dp) {
            p.mode = PMode.PANEL_SCROLL
            pressedEmoji = null; pressedCard = null
            ui.removeCallbacks(p.longPress)
        }
        if (p.mode == PMode.PANEL_SCROLL) {
            panelScroll = (panelScroll - dy).coerceIn(0f, maxPanelScroll())
            invalidate()
        }
    }

    private fun panelUp(p: Ptr) {
        when (p.mode) {
            PMode.PANEL_KEY -> {
                val k = p.key ?: return
                k.pressTarget = 0f
                when (k.type) {
                    KeyType.LETTERS -> listener?.onToolbar(Toolbar.CLOSE_PANEL)
                    KeyType.SPACE -> listener?.onSpace()
                    else -> {}
                }
            }
            PMode.PANEL_TAP -> {
                if (mode == Mode.EMOJI) {
                    pressedEmoji?.let { listener?.onEmoji(it) }
                    pressedEmoji = null
                } else {
                    pressedCard?.let { listener?.onPasteClip(it.item.text) }
                    pressedCard = null
                }
            }
            PMode.PANEL_SCROLL -> {
                val v = velocity
                if (v != null) {
                    v.computeCurrentVelocity(1000)
                    val vy = -v.getYVelocity(p.id)
                    scroller.fling(0, panelScroll.toInt(), 0, vy.toInt(), 0, 0, 0, maxPanelScroll().toInt())
                }
            }
            else -> {}
        }
        invalidate()
        kick()
    }

    // clipboard "x" removal is offered via long-press pin toggle; clear-all from the header.

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllPointers()
        Choreographer.getInstance().removeFrameCallback(frame)
        animating = false
    }

    companion object {
        const val FEEDBACK_DOWN = 0
        const val FEEDBACK_TICK = 1
        const val FEEDBACK_LIGHT = 2
        const val FEEDBACK_HEAVY = 3
    }
}
