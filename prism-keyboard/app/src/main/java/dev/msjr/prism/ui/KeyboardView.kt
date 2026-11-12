package dev.msjr.prism.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import dev.msjr.prism.data.ClipboardStore
import dev.msjr.prism.data.Settings
import dev.msjr.prism.keyboard.Key
import dev.msjr.prism.keyboard.KeyType
import dev.msjr.prism.keyboard.KeyboardLayout
import dev.msjr.prism.text.Pt
import dev.msjr.prism.text.Suggestion
import dev.msjr.prism.text.SuggestionResult
import dev.msjr.prism.text.TapDecoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole keyboard in one hardware-accelerated view: suggestion strip, keys, panels (emoji, clipboard,
 * editing), key balloons, long-press popups and the glide trail. Everything is drawn directly — no child
 * views, no layout passes while typing — and everything that moves is a spring.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, val glass: GlassRenderer) : View(context) {

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
        fun onEdit(action: EditAction)
        /**
         * A word was drawn across the keys. [path]: x, y pairs in key units (key widths, row heights);
         * [centers]: where each letter sits in the same units; [aspect]: row height / key width;
         * [fallback]: the letter under the finger
         * when it lifted, typed if the path makes no word.
         */
        fun onGlide(path: FloatArray, centers: Map<Char, Pt>, aspect: Float, fallback: String?)
        fun onKeyFeedback(key: Key?, kind: Int)
    }

    enum class Toolbar {
        HIDE, SETTINGS, CLIPBOARD, EMOJI, EDIT, VOICE, ONE_HAND, ONE_HAND_SWITCH, ONE_HAND_OFF,
        CLOSE_PANEL, CLEAR_CLIPBOARD, EMOJI_SEARCH, CLOSE_SEARCH, TOGGLE,
    }
    enum class EditAction { LEFT, RIGHT, UP, DOWN, HOME, END, SELECT, SELECT_ALL, COPY, CUT, PASTE, UNDO, REDO }
    enum class Mode { KEYS, EMOJI, CLIPBOARD, EDIT }

    var listener: Listener? = null

    init {
        // deliver finger movement as soon as it arrives instead of once per frame:
        // slides, glides, the space trackpad and swipe-delete follow the finger a frame sooner
        if (Build.VERSION.SDK_INT >= 30) {
            try { requestUnbufferedDispatch(android.view.InputDevice.SOURCE_CLASS_POINTER) } catch (_: Throwable) { }
        }
    }

    /**
     * Asked for every letter tap with the letters the finger could have meant (the hit one first):
     * returns how well each continues the word being typed, as log weights (only differences matter),
     * or null for "no opinion" (not in a word, no dictionary…).
     */
    var letterScorer: ((candidates: List<String>) -> FloatArray?)? = null

    /** Words may be drawn across the keys right now (a text field with suggestions, not a password…). */
    var glideEnabled = false
    /** A voice keyboard is installed: show the microphone in the toolbar. */
    var voiceAvailable = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The editing panel's "select" switch is on. */
    var selecting = false
        set(v) { if (field != v) { field = v; invalidate() } }

    internal val dp = resources.displayMetrics.density
    @Suppress("DEPRECATION")
    internal val sp = resources.displayMetrics.scaledDensity
    internal val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ state

    private var layout: KeyboardLayout? = null
    internal var settings: Settings? = null
        private set
    var mode = Mode.KEYS
        private set
    private var shiftState = 0 // 0 off, 1 once, 2 locked
    private var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED
    private var suggestions: SuggestionResult = SuggestionResult.EMPTY
    private var showSuggestions = true
    var clipChip: String? = null
        set(v) { field = v; invalidate() }
    var clipboardStore: ClipboardStore? = null
    // emoji search: the letters type into [searchQuery] (null = not searching), matches show in the strip
    private var searchQuery: String? = null
    private var searchResults: List<String> = emptyList()
    private var searchScroll = 0f
    private val searchArea = RectF()
    private var searchCell = 44f
    private var searchHint = "Search emoji"
    private var langLabel = "FA"
    internal val persianUi: Boolean get() = langLabel == "FA"
    /** the toolbar was opened over the suggestions with the Prism button */
    private var toolbarOpen = false

    // ------------------------------------------------------------------ geometry

    val extraTop = (34 * dp)                     // transparent room for balloons over the app
    internal var stripH = 46 * dp
    private var rowPitch = 60 * dp
    internal var keysH = 0f
    internal var bottomInset = 0
    private val padTop = 4 * dp
    private val padBottom = 6 * dp
    internal val gapX = 6.5f * dp
    private val gapY = 12 * dp
    internal val radius = 10 * dp
    private val keysArea = RectF()
    private val oneHandArea = RectF()
    internal val stripArea = RectF()
    private val stripSlots = Array(3) { RectF() }
    internal val toolbarBtns = ArrayList<Pair<Toolbar, RectF>>()
    private val clipChipRect = RectF()

    val visibleTop: Int get() = extraTop.toInt()

    // ------------------------------------------------------------------ paints

    internal var typeface: Typeface = Typeface.DEFAULT
    internal var typefaceBold: Typeface = Typeface.DEFAULT_BOLD
    internal val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    internal val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    internal val stripPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    internal val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // ------------------------------------------------------------------ panels

    private val emojiPanel = EmojiPanel(this)
    private val clipboardPanel = ClipboardPanel(this)
    private val editPanel = EditPanel(this)
    private var panel: Panel? = null
    /** 0 = keys, 1 = the panel fully in */
    private val panelT = Spring(0f, stiffness = 900f, damping = 1f)
    private var leavingPanel: Panel? = null

    // ------------------------------------------------------------------ animation

    private var animating = false
    private var lastFrameNs = 0L
    private var labelAlpha = 1f
    private var labelAlphaTarget = 1f
    private var glowX = 0f
    private var glowY = 0f
    private var glowAlpha = 0f
    private val popupT = Spring(0f, stiffness = 1500f, damping = 0.78f)
    /** suggestions ↔ toolbar: 0 = toolbar, 1 = suggestions */
    private val stripT = Spring(0f, stiffness = 1100f, damping = 1f)
    /** the new resting layer fading in over the old one (layout switches, shift…) */
    private var fade = 1f

    private class Ripple(var x: Float = 0f, var y: Float = 0f, var t: Float = 1f)
    private val ripples = Array(6) { Ripple() }
    private var rippleNext = 0

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNs == 0L) 1f / 120f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 1f / 30f)
            lastFrameNs = frameTimeNanos
            val busy = step(dt)
            invalidate()
            if (busy) Choreographer.getInstance().postFrameCallback(this) else { animating = false; lastFrameNs = 0L }
        }
    }

    internal fun kick() {
        if (!animating) {
            animating = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** advance everything that moves; returns true while something still does */
    private fun step(dt: Float): Boolean {
        var busy = false
        layout?.let { l -> for (k in l.allKeys) if (stepKey(k, dt)) busy = true }
        if (labelAlpha != labelAlphaTarget) {
            val d = labelAlphaTarget - labelAlpha
            labelAlpha += d * min(1f, dt * 14f)
            if (abs(d) < 0.01f) labelAlpha = labelAlphaTarget else busy = true
        }
        if (glowAlpha > 0f && pointers.isEmpty()) {
            glowAlpha = max(0f, glowAlpha - dt * 2.4f)
            busy = busy || glowAlpha > 0f
        }
        for (r in ripples) if (r.t < 1f) { r.t = min(1f, r.t + dt / 0.5f); busy = true }
        popupT.target = if (popupPtr != null) 1f else 0f
        if (popupT.step(dt)) busy = true
        if (stripT.step(dt)) busy = true
        if (panelT.step(dt)) busy = true else if (panelT.value == 0f) leavingPanel = null
        if (fade < 1f) { fade = min(1f, fade + dt / 0.13f); busy = true; if (fade >= 1f) oldNode = null }
        if ((panel ?: leavingPanel)?.step(dt) == true) busy = true
        if (trailCount > 0) {
            // the glide trail keeps fading after the finger lifts
            val now = SystemClock.uptimeMillis()
            if (now - trailT[trailCount - 1] > TRAIL_MS && pointers.values.none { it.mode == PMode.GLIDE }) trailCount = 0 else busy = true
        }
        return busy
    }

    internal fun stepKey(k: Key, dt: Float): Boolean {
        if (k.press == k.pressTarget && abs(k.pressVel) <= 0.001f) return false
        // instant in, quick release with one small liquid overshoot
        val down = k.pressTarget > k.press
        val stiffness = if (down) 2600f else 900f
        val c = 2f * (if (down) 1f else 0.68f) * sqrt(stiffness)
        var t = dt
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -stiffness * (k.press - k.pressTarget) - c * k.pressVel
            k.pressVel += a * h
            k.press += k.pressVel * h
            t -= h
        }
        if (abs(k.press - k.pressTarget) < 0.002f && abs(k.pressVel) < 0.01f) { k.press = k.pressTarget; k.pressVel = 0f; return false }
        return true
    }

    // ------------------------------------------------------------------ public API

    fun applySettings(s: Settings, font: Typeface, fontBold: Typeface) {
        settings = s
        typeface = font; typefaceBold = fontBold
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        rowPitch = 60f * dp * (s.heightPct / 100f) * (if (landscape) 0.7f else 1f)
        stripH = (if (landscape) 38f else 46f) * dp
        showSuggestions = s.suggestions
        updateLiveLight()
        requestLayout()
        invalidate()
    }

    fun setLayout(l: KeyboardLayout) {
        if (layout !== l) beginFade()
        layout = l
        val oldH = keysH
        computeKeysHeight()
        if (oldH != keysH) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setLangLabel(s: String) { langLabel = s; invalidate() }

    fun setShift(state: Int) {
        if (state != shiftState) { beginFade(); shiftState = state; invalidate() }
    }

    fun setEnterAction(action: Int) { enterAction = action; invalidate() }

    fun setSuggestions(r: SuggestionResult) {
        suggestions = r
        // typing again puts the suggestions back
        if (toolbarOpen && r.query.isNotEmpty()) toolbarOpen = false
        updateStripTarget()
        invalidate()
    }

    private fun hasSuggestions() = showSuggestions && suggestions.slots.any { it != null }

    private fun updateStripTarget() {
        val t = if (hasSuggestions() && !toolbarOpen) 1f else 0f
        if (stripT.target != t) { stripT.target = t; kick() }
    }

    /** Show emoji search in the strip ([query] null = stop searching). */
    fun setEmojiSearch(query: String?, results: List<String>, hint: String = searchHint) {
        if (results != searchResults) searchScroll = 0f
        searchQuery = query
        searchResults = if (query == null) emptyList() else results
        searchHint = hint
        invalidate()
    }

    val emojiSearching: Boolean get() = searchQuery != null

    fun setMode(m: Mode) {
        if (mode == m) return
        cancelAllPointers()
        val old = panel
        mode = m
        panel = when (m) {
            Mode.KEYS -> null
            Mode.EMOJI -> emojiPanel
            Mode.CLIPBOARD -> clipboardPanel
            Mode.EDIT -> editPanel
        }
        panel?.let { layoutPanel(it); it.onShow() }
        if (panel == null) { leavingPanel = old; panelT.target = 0f }
        else { leavingPanel = null; panelT.value = if (old == null) panelT.value else 0.4f; panelT.target = 1f }
        toolbarOpen = false
        updateStripTarget()
        kick()
        invalidate()
    }

    fun refreshPanel() { clipboardPanel.refresh(); emojiPanel.refresh(); invalidate() }

    fun resetState() {
        cancelAllPointers()
        labelAlpha = 1f; labelAlphaTarget = 1f
        glowAlpha = 0f
        toolbarOpen = false
        trailCount = 0
        fade = 1f; oldNode = null
        stripT.snap(if (hasSuggestions()) 1f else 0f)
        invalidate()
    }

    // ------------------------------------------------------------------ live light (glints follow the phone)

    private val sensors: SensorManager? = try { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager } catch (_: Throwable) { null }
    private var sensorOn = false
    private var gx = 0f
    private var gy = 0f
    private val tilt = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // world "up" in screen space, smoothed; a phone lying flat falls back to the default light
            val ax = e.values[0] / SensorManager.GRAVITY_EARTH
            val ay = e.values[1] / SensorManager.GRAVITY_EARTH
            gx += (ax - gx) * 0.18f
            gy += (ay - gy) * 0.18f
            val lx = gx * 1.15f + GlassRenderer.DEFAULT_LIGHT_X * 0.55f
            val ly = -gy * 0.85f + GlassRenderer.DEFAULT_LIGHT_Y * 0.55f
            if (glass.setLight(lx, ly)) invalidate()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateLiveLight() {
        val want = settings?.liveLight == true && windowVisibility == VISIBLE && isAttachedToWindow && glass.usesRefraction
        if (want == sensorOn) return
        val sm = sensors ?: return
        if (want) {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensorOn = sm.registerListener(tilt, acc, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.unregisterListener(tilt)
            sensorOn = false
            glass.resetLight()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateLiveLight()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLiveLight()
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
        panel?.let { layoutPanel(it) }
    }

    private fun layoutPanel(p: Panel) {
        if (width <= 0) return
        p.header.set(0f, extraTop, width.toFloat(), extraTop + stripH)
        p.body.set(0f, extraTop + stripH, width.toFloat(), extraTop + stripH + keysH)
        p.layout()
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
    // even though every key is a refraction shader. A second layer holds the previous look while the
    // new one fades in over it (switching pages, languages, shift).
    private val nodeA: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("prism-keys-a") else null
    private val nodeB: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("prism-keys-b") else null
    private var curNode: RenderNode? = nodeA
    private var oldNode: RenderNode? = null
    private var restSig: List<Any?>? = null

    private fun beginFade() {
        val cur = curNode ?: return
        if (!cur.hasDisplayList() || !isShown || restSig == null || mode != Mode.KEYS) return
        oldNode = cur
        curNode = if (cur === nodeA) nodeB else nodeA
        restSig = null
        fade = 0f
        kick()
    }

    private fun restSignature(l: KeyboardLayout): List<Any?> = listOf(
        l, width, height, shiftState, enterAction, langLabel, settings, glass.generation, oneHandArea.left, oneHandArea.right,
    )

    private fun drawResting(c: Canvas, l: KeyboardLayout, th: Theme) {
        glass.drawBackdrop(c)
        for (k in l.allKeys) drawKey(c, k, th, l, 0f, 1f)
        if (!oneHandArea.isEmpty) drawOneHandSide(c, th)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val th = glass.theme
        glass.ensureBackdrop(width, (height - extraTop).toInt(), extraTop, settings?.bgMode ?: "aurora")

        val pt = panelT.value.coerceIn(0f, 1f)
        val shownPanel = panel ?: leavingPanel
        val node = curNode
        val cached = node != null && labelAlpha >= 0.999f && canvas.isHardwareAccelerated && pt < 0.999f
        if (pt >= 0.999f || !cached) glass.drawBackdrop(canvas)
        if (cached && node != null) {
            val sig = restSignature(l)
            if (sig != restSig || !node.hasDisplayList()) {
                node.setPosition(0, 0, width, height)
                node.setUseCompositingLayer(true, null)
                val rc = node.beginRecording(width, height)
                try { drawResting(rc, l, th) } finally { node.endRecording() }
                restSig = sig
            }
            val old = oldNode
            if (old != null && fade < 1f && old.hasDisplayList()) {
                old.alpha = 1f
                canvas.drawRenderNode(old)
                node.alpha = fade
            } else node.alpha = 1f
            canvas.drawRenderNode(node)
            // only what's moving is drawn live, on top of its resting copy
            if (pt < 0.5f) for (k in l.allKeys) {
                if (k.press > 0.004f || k.pressTarget > 0f) drawKey(canvas, k, th, l, max(0f, k.press), 1f)
            }
        } else if (pt < 0.999f) {
            for (k in l.allKeys) drawKey(canvas, k, th, l, k.press, 1f)
            if (!oneHandArea.isEmpty) drawOneHandSide(canvas, th)
        }

        if (shownPanel != null && pt > 0.001f) {
            // the panel rises in over the keys: the backdrop wipes them out as it comes
            if (pt < 0.999f) {
                canvas.saveLayerAlpha(0f, extraTop, width.toFloat(), height.toFloat(), (min(1f, pt * 1.6f) * 255).toInt())
                glass.drawBackdrop(canvas)
                canvas.restore()
            }
            canvas.save()
            canvas.translate(0f, (1f - pt) * 18 * dp)
            shownPanel.draw(canvas, th, pt)
            canvas.restore()
        } else {
            drawLights(canvas)
            drawStrip(canvas, th)
            drawTrail(canvas, th)
            // floating stuff
            for (p in pointers.values) p.previewKey?.let { drawPreview(canvas, it, p, th) }
            popupPtr?.let { drawPopup(canvas, it, th) }
        }
    }

    private fun drawLights(canvas: Canvas) {
        if (settings?.glow != true) return
        canvas.save()
        glass.clipPanel(canvas)
        if (glowAlpha > 0f) glass.drawGlow(canvas, glowX, glowY, glowAlpha * 0.5f, 90 * dp)
        for (r in ripples) if (r.t < 1f) glass.drawRipple(canvas, r.x, r.y, r.t)
        canvas.restore()
    }

    private fun addRipple(x: Float, y: Float) {
        if (settings?.glow != true) return
        val r = ripples[rippleNext]
        rippleNext = (rippleNext + 1) % ripples.size
        r.x = x; r.y = y; r.t = 0f
    }

    internal fun tintOf(color: Int): Pair<Int, Float> =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color)) to Color.alpha(color) / 255f

    internal fun tintFor(k: Key, th: Theme): Pair<Int, Float> {
        return when {
            // enter always wears the theme's colour; fully when it sends/searches/goes
            k.type == KeyType.ENTER -> th.accent to (if (enterHasAction()) 0.66f else if (th.dark) 0.36f else 0.46f)
            k.type == KeyType.SHIFT && shiftState != 0 -> Color.WHITE to (if (th.dark) 0.55f else 0.95f)
            k.isModifier && k.type != KeyType.ZWNJ && k.type != KeyType.EMOJI -> tintOf(th.modTint)
            else -> tintOf(th.keyTint)
        }
    }

    private fun enterHasAction(): Boolean = when (enterAction) {
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE -> true
        else -> false
    }

    private fun drawKey(canvas: Canvas, k: Key, th: Theme, l: KeyboardLayout, press: Float, alphaMul: Float) {
        val (tint, ta) = tintFor(k, th)
        glass.drawGlass(canvas, k.rect, radius, press.coerceIn(-0.2f, 1.3f), tint, ta, alpha = alphaMul)
        if (labelAlpha <= 0.01f) return
        val shiftOn = shiftState != 0
        val labelColor = if (k.type == KeyType.SHIFT && shiftOn) Color.BLACK
        else if (k.type == KeyType.ENTER && enterHasAction()) Color.WHITE
        else th.label
        val alpha = (labelAlpha * alphaMul * 255).toInt()
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
                EditorInfo.IME_ACTION_SEND -> Icons.send(canvas, cx, cy, s, iconPaint, rtlIcons(l))
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icons.send(canvas, cx, cy, s * 0.85f, iconPaint, rtlIcons(l))
                EditorInfo.IME_ACTION_DONE -> Icons.check(canvas, cx, cy, s, iconPaint)
                else -> Icons.enter(canvas, cx, cy, s, iconPaint, rtlIcons(l))
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

    /** Arrow icons point the Persian way on every Persian page, symbols included (those are laid out LTR). */
    private fun rtlIcons(l: KeyboardLayout) = l.rtl || (langLabel == "FA" && l.id != "num")

    private fun displayLabel(k: Key): String {
        val shiftOn = shiftState != 0
        return if (shiftOn && k.label.length == 1 && k.label[0].isLetter()) k.label.uppercase() else k.label
    }

    /** A lone diacritic (َ ُ ِ ّ …) gets an invisible base so it isn't drawn on a dotted circle. */
    private fun shown(s: String): String =
        if (s.length == 1 && Character.getType(s[0]) == Character.NON_SPACING_MARK.toInt()) " $s" else s

    internal fun drawCentered(c: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    /** A round glass button with an icon drawn by [icon] (toolbar, panel headers). */
    internal fun drawRoundButton(canvas: Canvas, r: RectF, th: Theme, alpha: Float, accent: Boolean = false, press: Float = 0f) {
        val (t, ta) = if (accent) th.accent to 0.42f else tintOf(th.modTint)
        glass.drawGlass(canvas, r, r.height() / 2f, press, t, ta, shadow = false, alpha = alpha)
        iconPaint.color = th.label; iconPaint.alpha = (235 * alpha).toInt(); iconPaint.strokeWidth = 1.6f * dp
    }

    // --- preview balloon

    private val tmpRect = RectF()

    private fun drawPreview(canvas: Canvas, k: Key, p: Ptr, th: Theme) {
        if (settings?.keyPreview == false || k.type != KeyType.CHAR || popupPtr === p) return
        if (layout?.id == "num") return
        val text = shown(if (p.flick && k.hint != null) k.hint else displayLabel(k))
        // a wide bubble that grows out of the key itself, one piece of glass
        val w = max(k.rect.width() * 1.55f, 54 * dp)
        val h = k.rect.height() * 1.12f
        val cx = k.rect.centerX().coerceIn(w / 2f + 2 * dp, width - w / 2f - 2 * dp)
        var bottom = k.rect.top + 1 * dp
        if (bottom - h < 2 * dp) bottom = 2 * dp + h
        tmpRect.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        glass.drawBalloon(canvas, tmpRect, 14 * dp, k.rect, radius, th.balloon, if (th.dark) 0.78f else 0.94f)
        labelPaint.typeface = typeface
        labelPaint.textSize = (if (text.length > 2) 22f else 32f) * sp
        labelPaint.color = th.label; labelPaint.alpha = 255
        drawCentered(canvas, text, tmpRect.centerX(), tmpRect.centerY() + 1 * dp, labelPaint)
    }

    // --- long-press popup

    private val popupRect = RectF()
    private var popupCols = 1
    private var popupItemW = 0f
    private var popupItemH = 0f
    /** drawn while the popup springs shut after the finger lifted */
    private var popupGhost: Ptr? = null

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
        // sits right on the key so the two melt into one balloon
        var top = k.rect.top + 1 * dp - h
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
        val k = p.key
        // springs up out of the key
        val t = popupT.value
        canvas.save()
        if (k != null && t < 0.999f) {
            val sc = 0.6f + 0.4f * t
            canvas.scale(sc, sc, k.rect.centerX(), k.rect.top)
        }
        if (k != null) glass.drawBalloon(canvas, popupRect, 14 * dp, k.rect, radius, th.balloon, if (th.dark) 0.8f else 0.95f)
        else glass.drawGlass(canvas, popupRect, 14 * dp, 0.2f, th.balloon, if (th.dark) 0.8f else 0.95f)
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
        val (t, ta) = tintOf(th.modTint)
        glass.drawGlass(canvas, oneHandBtnSwitch, s / 2, 0f, t, ta)
        glass.drawGlass(canvas, oneHandBtnExpand, s / 2, 0f, t, ta)
        iconPaint.color = th.label; iconPaint.alpha = 255; iconPaint.strokeWidth = 1.8f * dp
        Icons.chevron(canvas, oneHandBtnSwitch.centerX(), oneHandBtnSwitch.centerY(), s, iconPaint, settings?.oneHand == 2)
        Icons.expand(canvas, oneHandBtnExpand.centerX(), oneHandBtnExpand.centerY(), s, iconPaint)
    }

    // --- strip

    private val toggleRect = RectF()

    private fun drawStrip(canvas: Canvas, th: Theme) {
        toolbarBtns.clear()
        clipChipRect.setEmpty()
        if (searchQuery != null) { drawSearchStrip(canvas, th); return }
        val t = stripT.value.coerceIn(0f, 1f)
        if (t < 0.999f) drawToolbar(canvas, th, 1f - t, t)
        if (t > 0.001f) drawSuggestions(canvas, th, t)
    }

    private fun drawSuggestions(canvas: Canvas, th: Theme, a: Float) {
        val w = width.toFloat()
        val top = stripArea.top
        val h = stripH
        val cy = top + h / 2f
        val live = stripT.target == 1f
        // the Prism button: opens the toolbar over the suggestions
        val bs = 34 * dp
        toggleRect.set(7 * dp, cy - bs / 2, 7 * dp + bs, cy + bs / 2)
        if (live) toolbarBtns += Toolbar.TOGGLE to toggleRect
        drawRoundButton(canvas, toggleRect, th, a)
        Icons.prism(canvas, toggleRect.centerX(), toggleRect.centerY() + 1 * dp, bs, iconPaint)

        val slots = suggestions.slots
        val left = toggleRect.right + 5 * dp
        val sw = (w - left - 4 * dp) / 3f
        // physical order: in RTL the "left" slot goes on the right
        val rtl = layout?.rtl == true
        val slide = (1f - a) * 10 * dp
        for (i in 0 until 3) {
            val pos = if (rtl) 2 - i else i
            stripSlots[i].set(left + pos * sw, top, left + (pos + 1) * sw, top + h)
        }
        dividerPaint.color = th.labelDim; dividerPaint.alpha = (50 * a).toInt(); dividerPaint.strokeWidth = dp
        canvas.drawLine(left + sw, top + h * 0.32f, left + sw, top + h * 0.68f, dividerPaint)
        canvas.drawLine(left + 2 * sw, top + h * 0.32f, left + 2 * sw, top + h * 0.68f, dividerPaint)
        for (i in 0 until 3) {
            val s = slots[i] ?: continue
            val r = stripSlots[i]
            val highlighted = i == 1 && (suggestions.autoCorrect != null || s.kind == Suggestion.Kind.LAYOUT_FIX ||
                s.kind == Suggestion.Kind.SHORTCUT || s.kind == Suggestion.Kind.EXTRA)
            val pressed = pointers.values.any { it.mode == PMode.STRIP && it.stripIndex == i }
            if (highlighted || pressed) {
                tmpRect.set(r.left + 5 * dp, r.top + 6 * dp, r.right - 5 * dp, r.bottom - 6 * dp)
                if (highlighted) glass.drawGlass(canvas, tmpRect, tmpRect.height() / 2f, if (pressed) 0.6f else 0f, th.accent, 0.42f, shadow = false, alpha = a)
                else { val (tc, ta) = tintOf(th.keyTint); glass.drawGlass(canvas, tmpRect, tmpRect.height() / 2f, 0.5f, tc, ta, shadow = false, alpha = a) }
            }
            val emoji = s.kind == Suggestion.Kind.EMOJI
            stripPaint.typeface = if (i == 1) typefaceBold else typeface
            stripPaint.textSize = (if (emoji) 21f else 16f) * sp
            stripPaint.color = if (highlighted && th.dark) Color.WHITE else th.label
            stripPaint.alpha = ((if (i != 1 && !highlighted) 225 else 255) * a).toInt()
            var text = s.text
            if (s.kind == Suggestion.Kind.TYPED && !suggestions.typedValid) text = "«$text»"
            val shown = TextUtils.ellipsize(text, stripPaint, r.width() - 16 * dp, TextUtils.TruncateAt.END).toString()
            drawCentered(canvas, shown, r.centerX(), r.centerY() + slide, stripPaint)
        }
    }

    private fun drawToolbar(canvas: Canvas, th: Theme, a: Float, t: Float) {
        val w = width.toFloat()
        val top = stripArea.top
        val h = stripH
        val live = stripT.target == 0f
        val s = 36 * dp
        var x = 7 * dp
        val cy = top + h / 2f - t * 10 * dp
        val actions = ArrayList<Toolbar>(6)
        // opened over the suggestions: the first button takes you back to them
        if (toolbarOpen && hasSuggestions()) actions += Toolbar.TOGGLE
        actions += Toolbar.SETTINGS; actions += Toolbar.CLIPBOARD; actions += Toolbar.EDIT
        if (voiceAvailable) actions += Toolbar.VOICE
        actions += Toolbar.ONE_HAND
        for (act in actions) {
            val r = RectF(x, cy - s / 2, x + s, cy + s / 2)
            if (live) toolbarBtns += act to r
            val pressed = pointers.values.any { it.mode == PMode.STRIP && it.toolbar == act }
            drawRoundButton(canvas, r, th, a, press = if (pressed) 0.8f else 0f)
            when (act) {
                Toolbar.TOGGLE -> Icons.chevron(canvas, r.centerX() - dp, r.centerY(), s, iconPaint, true)
                Toolbar.SETTINGS -> Icons.gear(canvas, r.centerX(), r.centerY(), s, iconPaint)
                Toolbar.CLIPBOARD -> Icons.clipboard(canvas, r.centerX(), r.centerY(), s, iconPaint)
                Toolbar.EDIT -> Icons.editCursor(canvas, r.centerX(), r.centerY(), s, iconPaint)
                Toolbar.VOICE -> Icons.mic(canvas, r.centerX(), r.centerY(), s, iconPaint)
                Toolbar.ONE_HAND -> Icons.oneHand(canvas, r.centerX(), r.centerY(), s, iconPaint)
                else -> {}
            }
            x += s + 7 * dp
        }
        val hide = RectF(w - 7 * dp - s, cy - s / 2, w - 7 * dp, cy + s / 2)
        if (live) toolbarBtns += Toolbar.HIDE to hide
        iconPaint.color = th.label; iconPaint.alpha = (230 * a).toInt(); iconPaint.strokeWidth = 2f * dp
        Icons.chevronDown(canvas, hide.centerX(), hide.centerY(), s, iconPaint)
        val chip = clipChip ?: return
        val left = x + 4 * dp
        val right = hide.left - 4 * dp
        if (right - left > 60 * dp) {
            stripPaint.typeface = typeface
            stripPaint.textSize = 13.5f * sp
            val label = TextUtils.ellipsize(chip.replace('\n', ' '), stripPaint, right - left - 40 * dp, TextUtils.TruncateAt.END).toString()
            val tw = stripPaint.measureText(label) + 44 * dp
            clipChipRect.set(right - tw, cy - s / 2, right, cy + s / 2)
            glass.drawGlass(canvas, clipChipRect, s / 2, 0f, th.accent, 0.38f, shadow = false, alpha = a)
            iconPaint.color = th.label; iconPaint.alpha = (255 * a).toInt(); iconPaint.strokeWidth = 1.6f * dp
            Icons.clipboard(canvas, clipChipRect.left + 18 * dp, cy, s * 0.8f, iconPaint)
            stripPaint.color = th.label; stripPaint.alpha = (255 * a).toInt()
            drawCentered(canvas, label, clipChipRect.left + 32 * dp + (tw - 44 * dp) / 2f, cy, stripPaint)
        }
    }

    /** Emoji search: [×] [query field] [matching emoji…] — the keys below type into the query. */
    private fun drawSearchStrip(canvas: Canvas, th: Theme) {
        val w = width.toFloat()
        val top = stripArea.top
        val h = stripH
        val cy = top + h / 2f
        val s = 36 * dp
        val close = RectF(7 * dp, cy - s / 2, 7 * dp + s, cy + s / 2)
        toolbarBtns += Toolbar.CLOSE_SEARCH to close
        drawRoundButton(canvas, close, th, 1f)
        iconPaint.strokeWidth = 1.8f * dp
        Icons.close(canvas, close.centerX(), close.centerY(), s, iconPaint)

        // the query field
        val q = searchQuery.orEmpty()
        stripPaint.typeface = typeface
        stripPaint.textSize = 15f * sp
        val fieldW = min(w * 0.42f, max(120 * dp, stripPaint.measureText(q) + 56 * dp))
        val field = RectF(close.right + 7 * dp, cy - s / 2, close.right + 7 * dp + fieldW, cy + s / 2)
        glass.drawGlass(canvas, field, s / 2, 0f, th.accent, 0.30f, shadow = false)
        iconPaint.color = th.label; iconPaint.alpha = 200; iconPaint.strokeWidth = 1.6f * dp
        Icons.search(canvas, field.left + 18 * dp, cy, s * 0.8f, iconPaint)
        val textLeft = field.left + 32 * dp
        if (q.isEmpty()) {
            stripPaint.color = th.labelDim; stripPaint.alpha = 200
            val hint = TextUtils.ellipsize(searchHint, stripPaint, field.right - textLeft - 10 * dp, TextUtils.TruncateAt.END).toString()
            drawCentered(canvas, hint, (textLeft + field.right - 6 * dp) / 2f, cy, stripPaint)
        } else {
            stripPaint.color = th.label
            // keep the end of a long query (where you're typing) in view
            val shown = TextUtils.ellipsize(q, stripPaint, field.right - textLeft - 14 * dp, TextUtils.TruncateAt.START).toString()
            val tw = stripPaint.measureText(shown)
            drawCentered(canvas, shown, textLeft + tw / 2f, cy, stripPaint)
            accentPaint.color = th.label
            canvas.drawRect(textLeft + tw + 2 * dp, cy - 9 * dp, textLeft + tw + 3.5f * dp, cy + 9 * dp, accentPaint)
        }

        // the matches, scrollable sideways
        searchArea.set(field.right + 4 * dp, top, w - 4 * dp, top + h)
        searchCell = 44 * dp
        canvas.save()
        canvas.clipRect(searchArea)
        emojiPaint.textSize = 24f * sp
        emojiPaint.alpha = 255
        if (searchResults.isEmpty() && q.isNotEmpty()) {
            stripPaint.color = th.labelDim; stripPaint.alpha = 160
            drawCentered(canvas, "—", searchArea.centerX(), cy, stripPaint)
        }
        for ((i, e) in searchResults.withIndex()) {
            val cx = searchArea.left + i * searchCell + searchCell / 2f - searchScroll
            if (cx < searchArea.left - searchCell || cx > searchArea.right + searchCell) continue
            drawCentered(canvas, e, cx, cy, emojiPaint)
        }
        canvas.restore()
    }

    private fun maxSearchScroll(): Float = max(0f, searchResults.size * searchCell - searchArea.width())

    // --- glide trail

    private var trailX = FloatArray(256)
    private var trailY = FloatArray(256)
    private var trailT = LongArray(256)
    private var trailCount = 0

    private fun trailAdd(x: Float, y: Float) {
        if (trailCount == trailX.size) {
            // drop the oldest half: only the recent tail is ever drawn
            val half = trailCount / 2
            System.arraycopy(trailX, half, trailX, 0, trailCount - half)
            System.arraycopy(trailY, half, trailY, 0, trailCount - half)
            System.arraycopy(trailT, half, trailT, 0, trailCount - half)
            trailCount -= half
        }
        trailX[trailCount] = x; trailY[trailCount] = y; trailT[trailCount] = SystemClock.uptimeMillis()
        trailCount++
    }

    private fun drawTrail(canvas: Canvas, th: Theme) {
        if (trailCount < 2) return
        val now = SystemClock.uptimeMillis()
        val core = if (th.dark) Theme.blend(th.accent, Color.WHITE, 0.55f) else th.accent
        canvas.save()
        glass.clipPanel(canvas)
        for (pass in 0..1) {
            // a soft wide halo under a bright thin core, both tapering toward the tail
            trailPaint.color = if (pass == 0) th.accent else core
            for (i in 1 until trailCount) {
                val age = (now - trailT[i]).toFloat() / TRAIL_MS
                if (age >= 1f) continue
                val life = 1f - age
                trailPaint.strokeWidth = (if (pass == 0) 15f else 5.5f) * dp * (0.35f + 0.65f * life)
                trailPaint.alpha = ((if (pass == 0) 60 else 230) * life * life).toInt()
                canvas.drawLine(trailX[i - 1], trailY[i - 1], trailX[i], trailY[i], trailPaint)
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ touch

    private enum class PMode { KEY, SLIDE, GLIDE, TRACKPAD, DELETE_SWIPE, STRIP, PANEL, ONE_HAND, NONE }

    /** Follow a finger that slid off !#1 / shift: light up the character under it. */
    private fun slideTo(p: Ptr, x: Float, y: Float) {
        val nk = keyAt(x, y)?.takeIf { it.type == KeyType.CHAR }
        if (nk === p.key) return
        p.key?.pressTarget = 0f
        p.key = nk
        p.previewKey = nk
        if (nk != null) {
            nk.pressTarget = 1f
            listener?.onKeyFeedback(null, FEEDBACK_TICK)
        }
        invalidate()
        kick()
    }

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
        /** the letter that was actually typed (the decoder may have picked a neighbour of [key]) */
        var typedKey: Key? = null
        var committed = false
        var accX = 0f; var accY = 0f
        var repeat = 0
        var swipeWords = 0
        var stripIndex = -1
        /** the finger dragged the emoji search results sideways (so lifting it is not a tap) */
        var stripScrolled = false
        var toolbar: Toolbar? = null
        /** for [PMode.SLIDE]: the key the slide started on */
        var slideFrom: KeyType? = null
        /** the finger's path while it may be drawing a word: x, y pairs in pixels */
        var path = FloatArray(0)
        var pathN = 0
        val longPress = Runnable { onLongPress(this) }
        val repeater = Runnable { onRepeat(this) }

        fun pathAdd(px: Float, py: Float) {
            if (pathN > 0) {
                val dx = px - path[pathN * 2 - 2]; val dy = py - path[pathN * 2 - 1]
                if (dx * dx + dy * dy < 4f * dp * dp) return
            }
            if (pathN * 2 + 2 > path.size) path = path.copyOf(max(64, path.size * 2))
            path[pathN * 2] = px; path[pathN * 2 + 1] = py
            pathN++
        }
    }

    private val pointers = HashMap<Int, Ptr>()
    private var popupPtr: Ptr? = null
    private var velocity: VelocityTracker? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx)
                val x = e.getX(idx); val y = e.getY(idx)
                if (y < extraTop) return e.actionMasked != MotionEvent.ACTION_DOWN
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    velocity?.recycle(); velocity = VelocityTracker.obtain()
                }
                velocity?.addMovement(e)
                down(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    // a glide wants every point the screen reported, not just the latest
                    if (p.mode == PMode.GLIDE || (p.mode == PMode.KEY && p.pathN > 0)) {
                        for (hI in 0 until e.historySize) {
                            val hx = e.getHistoricalX(i, hI); val hy = e.getHistoricalY(i, hI)
                            p.pathAdd(hx, hy)
                            if (p.mode == PMode.GLIDE) trailAdd(hx, hy)
                        }
                    }
                    move(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                velocity?.addMovement(e)
                val p = pointers[e.getPointerId(idx)]
                if (p != null) {
                    move(p, e.getX(idx), e.getY(idx), lifting = true)
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
            if (p.mode == PMode.PANEL) (panel ?: leavingPanel)?.cancel()
        }
        pointers.clear()
        popupPtr = null
        trailCount = 0
        kick()
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        val tx = correctedX(x, y); val ty = correctedY(x, y)
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
        p.downTime = SystemClock.uptimeMillis()
        pointers[id] = p

        if (mode != Mode.KEYS) {
            // panels follow one finger
            if (pointers.values.none { it !== p && it.mode == PMode.PANEL }) {
                p.mode = PMode.PANEL
                panel?.down(x, y)
            }
            kick()
            return
        }

        glowX = x; glowY = y; if (settings?.glow == true) glowAlpha = 1f

        // strip
        if (stripArea.contains(x, y)) {
            p.mode = PMode.STRIP
            p.toolbar = toolbarBtns.firstOrNull { it.second.contains(x, y) }?.first
            if (p.toolbar == null && searchQuery != null) {
                if (searchArea.contains(x, y)) {
                    val i = ((x - searchArea.left + searchScroll) / searchCell).toInt()
                    if (i in searchResults.indices) p.stripIndex = SEARCH_HIT + i
                }
            } else if (p.toolbar == null && !clipChipRect.isEmpty && clipChipRect.contains(x, y)) {
                p.stripIndex = 100
            } else if (p.toolbar == null && stripT.target == 1f) {
                p.stripIndex = stripSlots.indexOfFirst { it.contains(x, y) }
                if (p.stripIndex >= 0 && suggestions.slots[p.stripIndex] != null) {
                    ui.postDelayed(p.longPress, 500)
                } else p.stripIndex = -1
            }
            if (p.toolbar != null || p.stripIndex >= 0) listener?.onKeyFeedback(null, FEEDBACK_LIGHT)
            invalidate()
            kick()
            return
        }

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
        // feedback and the pressed look first: they're what the finger "feels", so nothing
        // (not even typing the previous finger's letter) may delay them
        listener?.onKeyFeedback(k, FEEDBACK_DOWN)
        addRipple(k.rect.centerX(), k.rect.centerY())
        // deleting a letter the decoder just changed: type the next one exactly where it's tapped
        if (k.type == KeyType.DELETE) { if (lastDecoded) noDecodeNext = true; lastDecoded = false }
        p.key = k
        p.downKey = k
        p.mode = PMode.KEY
        k.pressTarget = 1f
        k.press = max(k.press, 0.85f); k.pressVel = 0f
        if (k.type == KeyType.CHAR) p.previewKey = k
        invalidate()

        // rollover: a second finger lands while the first is still on a character or space —
        // type the first one now so fast typing keeps its order
        for (o in pointers.values) {
            if (o === p || popupPtr === o) continue
            if (o.mode == PMode.GLIDE) { finishGlide(o); o.committed = true; o.mode = PMode.NONE; continue }
            if (o.mode != PMode.KEY || o.committed) continue
            when (o.key?.type) {
                KeyType.CHAR -> { commitChar(o); learnTouch(o) }
                KeyType.SPACE -> if (!o.longFired) listener?.onSpace() else continue
                else -> continue
            }
            o.committed = true
            // the same key pressed by both fingers stays down for the new one
            if (o.key !== k) o.key?.pressTarget = 0f
            o.previewKey = null
            ui.removeCallbacks(o.longPress)
        }

        // a letter pressed alone may be the start of a glide: start remembering the path
        if (glideEnabled && settings?.glide == true && pointers.size == 1 && isLetterKey(k) && isLetterPage()) p.pathAdd(x, y)

        val lp = (settings?.longPressMs ?: 320).toLong()
        when (k.type) {
            KeyType.DELETE -> {
                listener?.onDelete(0)
                ui.postDelayed(p.repeater, 380)
            }
            KeyType.SHIFT -> listener?.onShiftDown()
            KeyType.SPACE -> ui.postDelayed(p.longPress, lp + 60)
            KeyType.LANG -> ui.postDelayed(p.longPress, 450)
            KeyType.ENTER -> ui.postDelayed(p.longPress, lp + 80)
            KeyType.CHAR, KeyType.ZWNJ, KeyType.EMOJI -> if (k.popup.isNotEmpty() || k.hint != null) ui.postDelayed(p.longPress, lp)
            else -> {}
        }
        kick()
    }

    private fun isLetterPage(): Boolean = layout?.id.let { it == "fa" || it == "en" }

    private fun move(p: Ptr, x: Float, y: Float, lifting: Boolean = false) {
        val dx = x - p.lastX; val dy = y - p.lastY
        p.lastX = x; p.lastY = y
        p.x = x; p.y = y
        if (p.mode != PMode.PANEL) { glowX = x; glowY = y; if (settings?.glow == true) glowAlpha = 1f }
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
                    KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS, KeyType.SHIFT -> {
                        // press !#1 (or shift), slide onto a character and let go —
                        // it's typed and you're back where you were, in one motion
                        val out = max(max(k.hit.left - x, x - k.hit.right) / k.rect.width(),
                            max(k.hit.top - y, y - k.hit.bottom) / k.rect.height())
                        if (out > 0.3f && !lifting) {
                            val target = keyAt(x, y)
                            if (target != null && target.type == KeyType.CHAR) {
                                ui.removeCallbacks(p.longPress)
                                k.pressTarget = 0f
                                p.slideFrom = k.type
                                p.mode = PMode.SLIDE
                                p.key = null
                                if (k.type != KeyType.SHIFT) listener?.onSwitchPage(k.type)
                                slideTo(p, x, y)
                            }
                        }
                    }
                    KeyType.DELETE -> {
                        if (settings?.swipeDelete != false && p.downX - x > 26 * dp) {
                            ui.removeCallbacks(p.repeater)
                            p.mode = PMode.DELETE_SWIPE
                            updateDeleteSwipe(p)
                        }
                    }
                    KeyType.CHAR -> moveOnChar(p, k, x, y, lifting)
                    else -> {}
                }
            }
            PMode.GLIDE -> { p.pathAdd(x, y); trailAdd(x, y) }
            PMode.SLIDE -> slideTo(p, x, y)
            PMode.TRACKPAD -> trackpadMove(p, dx, dy)
            PMode.DELETE_SWIPE -> updateDeleteSwipe(p)
            PMode.PANEL -> panel?.move(x, y)
            PMode.STRIP -> if (searchQuery != null && p.toolbar == null) {
                // drag the emoji matches sideways
                if (!p.stripScrolled && abs(x - p.downX) > 8 * dp) p.stripScrolled = true
                if (p.stripScrolled) { searchScroll = (searchScroll - dx).coerceIn(0f, maxSearchScroll()); invalidate() }
            }
            else -> {}
        }
        kick()
    }

    private fun moveOnChar(p: Ptr, k: Key, x: Float, y: Float, lifting: Boolean) {
        if (p.flickByLong) return
        val canGlide = p.pathN > 0 && k === p.downKey && pointers.size == 1 && !p.committed
        if (canGlide) p.pathAdd(x, y)
        val upDist = p.downY - y
        val side = abs(x - p.downX)
        // a deliberate flick up: well past the key's middle and almost straight up, so a thumb rolling
        // off a key while tapping doesn't type the corner letter — and a glide is never mistaken for one
        val flickZone = k.hint != null && upDist > k.rect.height() * 0.7f &&
            side < (if (canGlide) min(upDist * 0.45f, k.rect.width() * 0.5f) else upDist * 0.7f) &&
            (!canGlide || upDist < k.rect.height() * 1.9f)
        if (flickZone) {
            if (!p.flick) { p.flick = true; ui.removeCallbacks(p.longPress); invalidate() }
            return
        } else if (p.flick && (upDist < k.rect.height() * 0.4f || canGlide)) {
            p.flick = false; invalidate()
        }
        if (p.flick || lifting) return
        val outX = max(0f, max(k.hit.left - x, x - k.hit.right))
        val outY = max(0f, max(k.hit.top - y, y - k.hit.bottom))
        val left = outX > k.rect.width() * 0.4f || outY > k.rect.height() * 0.4f
        if (canGlide) {
            // the finger travelled into another key without lifting: it is drawing a word
            if (left && hypot(x - p.downX, y - p.downY) > k.rect.width() * 0.85f) startGlide(p)
            return
        }
        // a thumb rolls as it lifts and the last reported point jumps: never change the letter during
        // a quick tap — only a held, deliberate slide
        if (SystemClock.uptimeMillis() - p.downTime > 140) {
            val nk = if (left) keyAt(x, y) else null
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

    private fun startGlide(p: Ptr) {
        ui.removeCallbacks(p.longPress)
        p.mode = PMode.GLIDE
        p.key?.pressTarget = 0f
        p.previewKey = null
        trailCount = 0
        for (i in 0 until p.pathN) trailAdd(p.path[i * 2], p.path[i * 2 + 1])
        listener?.onKeyFeedback(null, FEEDBACK_TICK)
        invalidate()
    }

    private fun finishGlide(p: Ptr) {
        val l = layout ?: return
        // everything in key units, the way the decoder knows the layout
        val unitW = keyUnitW()
        if (unitW <= 0f || p.pathN < 2) return
        val centers = HashMap<Char, Pt>()
        var rowTop = Float.MAX_VALUE
        for (k in l.allKeys) if (isLetterKey(k)) rowTop = min(rowTop, k.rect.centerY())
        for (k in l.allKeys) {
            if (!isLetterKey(k)) continue
            val c = Pt((k.rect.centerX() - keysArea.left) / unitW, (k.rect.centerY() - rowTop) / rowPitch)
            centers[k.output[0].lowercaseChar()] = c
            // letters that live on a key's corner (ض on ص, آ on ا…) sit on that same key
            k.hint?.let { h -> if (h.length == 1 && h[0].isLetter()) centers.putIfAbsent(h[0], c) }
            for (alt in k.popup) if (alt.length == 1 && alt[0].isLetter()) centers.putIfAbsent(alt[0].lowercaseChar(), c)
        }
        val pts = FloatArray(p.pathN * 2)
        for (i in 0 until p.pathN) {
            val px = p.path[i * 2]; val py = p.path[i * 2 + 1]
            pts[i * 2] = (correctedX(px, py) - keysArea.left) / unitW
            pts[i * 2 + 1] = (correctedY(px, py) - rowTop) / rowPitch
        }
        val under = keyAt(p.x, p.y)?.takeIf { isLetterKey(it) }
        listener?.onGlide(pts, centers, rowPitch / unitW, under?.output)
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
                    // another finger may still be holding the same key (fast double letters)
                    if (pointers.values.none { it.key === k && it.mode == PMode.KEY }) k.pressTarget = 0f
                    if (popupPtr === p) {
                        val items = popupItems(p)
                        if (p.popupIndex in items.indices) listener?.onChar(items[p.popupIndex], true)
                        popupPtr = null
                    } else if (!p.committed) {
                        when (k.type) {
                            KeyType.CHAR, KeyType.ZWNJ -> { commitChar(p); learnTouch(p) }
                            KeyType.SPACE -> if (!p.longFired) listener?.onSpace()
                            KeyType.ENTER -> if (!p.longFired) listener?.onEnter()
                            KeyType.SYMBOLS, KeyType.SYMBOLS2, KeyType.LETTERS -> listener?.onSwitchPage(k.type)
                            KeyType.LANG -> if (!p.longFired) listener?.onLang()
                            KeyType.EMOJI -> if (!p.longFired) listener?.onToolbar(Toolbar.EMOJI)
                            else -> {}
                        }
                    }
                }
                p.previewKey = null
            }
            PMode.GLIDE -> finishGlide(p)
            PMode.SLIDE -> {
                val k = p.key
                p.previewKey = null
                if (k != null) {
                    k.pressTarget = 0f
                    if (k.type == KeyType.CHAR) {
                        listener?.onChar(k.output, false)
                        // back where the slide started (shift turns itself off after one letter)
                        when (p.slideFrom) {
                            KeyType.SYMBOLS -> listener?.onSwitchPage(KeyType.LETTERS)
                            KeyType.SYMBOLS2 -> listener?.onSwitchPage(KeyType.SYMBOLS)
                            else -> {}
                        }
                    }
                }
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
            PMode.PANEL -> {
                val v = velocity
                var vx = 0f; var vy = 0f
                if (v != null) { v.computeCurrentVelocity(1000); vx = v.getXVelocity(p.id); vy = v.getYVelocity(p.id) }
                panel?.up(p.x, p.y, vx, vy)
            }
            PMode.NONE -> {}
        }
        invalidate()
        kick()
    }

    private fun commitChar(p: Ptr) {
        var k = p.key ?: return
        // the layout may have been swapped under the finger (Persian shift switched itself off when the
        // previous finger's letter was typed): type what's under the finger now, not the old layer
        val l = layout
        if (l != null && l.allKeys.none { it === k }) {
            k = keyAt(p.x, p.y)?.takeIf { it.type == k.type } ?: k
            p.key = k
        }
        var text = if (p.flick && k.hint != null) k.hint else k.output
        p.typedKey = k
        var decoded = false
        if (!p.flick && k === p.downKey && isLetterKey(k) && !noDecodeNext) {
            val d = decode(k, p.downX, p.downY)
            if (d !== k) { text = d.output; p.typedKey = d; decoded = true }
        }
        noDecodeNext = false
        lastDecoded = decoded
        listener?.onChar(text, p.flick)
    }

    private fun isLetterKey(k: Key) = k.type == KeyType.CHAR && k.output.length == 1 && k.output[0].isLetter()

    /** the letter the decoder last picked over the one hit (deleting it turns decoding off for one tap) */
    private var lastDecoded = false
    private var noDecodeNext = false

    /**
     * Which letter a tap most likely meant: a soft "where did the thumb land" model (a bell curve around
     * each key, shifted by what's been learned about this person's thumbs) times "which letter makes a
     * word" from the IME. The hit key gets a head start and an unknown word never outweighs a tap
     * well inside a key, so deliberate spellings still come out exactly as tapped.
     */
    private fun decode(hit: Key, x: Float, y: Float): Key {
        val l = layout ?: return hit
        val scorer = letterScorer ?: return hit
        val kw = hit.rect.width() + gapX
        val kh = hit.rect.height() + gapY
        if (kw <= 0f || kh <= 0f) return hit
        val tx = correctedX(x, y); val ty = correctedY(x, y)
        val cands = ArrayList<Key>(6)
        val spatial = ArrayList<Float>(6)
        for (o in l.allKeys) {
            if (o !== hit && !isLetterKey(o)) continue
            val dx = (tx - o.rect.centerX()) / kw
            val dy = (ty - o.rect.centerY()) / kh
            if (o !== hit && dx * dx + dy * dy > TapDecoder.REACH * TapDecoder.REACH) continue
            val s = TapDecoder.spatial(dx, dy, o === hit)
            if (o === hit) { cands.add(0, o); spatial.add(0, s) } else { cands += o; spatial += s }
        }
        if (cands.size < 2) return hit
        val lm = scorer(cands.map { it.output }) ?: return hit
        return cands[TapDecoder.pick(spatial.toFloatArray(), lm)]
    }

    private fun onLongPress(p: Ptr) {
        if (!pointers.containsKey(p.id)) return
        p.longFired = true
        if (p.mode == PMode.STRIP) {
            val s = suggestions.slots.getOrNull(p.stripIndex) ?: return
            listener?.onSuggestionLongPress(s)
            p.stripIndex = -1
            invalidate()
            return
        }
        val k = p.key ?: return
        when (k.type) {
            KeyType.SPACE -> startTrackpad(p)
            KeyType.LANG -> { k.pressTarget = 0f; listener?.onLangLongPress() }
            // holding enter always makes a new line, even where a tap would send
            KeyType.ENTER -> { listener?.onKeyFeedback(null, FEEDBACK_HEAVY); listener?.onChar("\n", true) }
            KeyType.CHAR, KeyType.ZWNJ, KeyType.EMOJI -> {
                if (p.flick) return
                val items = popupItems(p)
                if (items.isEmpty()) return
                p.pathN = 0
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
                popupT.snap(0f)
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

    // --- space trackpad

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

    // Two thumbs miss in different ways: the left one lands off to one side, the right one to the other,
    // and both drift differently on the top and bottom rows. So the keyboard is split into a grid of
    // zones (4 bands × 3 columns) and each zone learns its own offset, in key sizes so it survives
    // height changes. Taps are shifted back by their zone's offset before anything looks at them.

    private val zoneX = FloatArray(ZONE_ROWS * ZONE_COLS)
    private val zoneY = FloatArray(ZONE_ROWS * ZONE_COLS)
    private val zoneN = IntArray(ZONE_ROWS * ZONE_COLS)
    var onTouchModelChanged: ((String) -> Unit)? = null
    private var touchSamples = 0

    /** Restore a model saved by [onTouchModelChanged]. */
    fun setTouchModel(saved: String?) {
        zoneX.fill(0f); zoneY.fill(0f); zoneN.fill(0)
        val parts = saved?.split(',') ?: return
        if (parts.size != ZONE_ROWS * ZONE_COLS * 3) return
        for (i in 0 until ZONE_ROWS * ZONE_COLS) {
            zoneX[i] = parts[i * 3].toFloatOrNull()?.coerceIn(-ZONE_CAP_X, ZONE_CAP_X) ?: 0f
            zoneY[i] = parts[i * 3 + 1].toFloatOrNull()?.coerceIn(-ZONE_CAP_Y, ZONE_CAP_Y) ?: 0f
            zoneN[i] = parts[i * 3 + 2].toIntOrNull() ?: 0
        }
    }

    private fun zoneOf(x: Float, y: Float): Int {
        if (keysArea.isEmpty) return 0
        val c = ((x - keysArea.left) / keysArea.width() * ZONE_COLS).toInt().coerceIn(0, ZONE_COLS - 1)
        val r = ((y - keysArea.top) / keysArea.height() * ZONE_ROWS).toInt().coerceIn(0, ZONE_ROWS - 1)
        return r * ZONE_COLS + c
    }

    private fun keyUnitW(): Float = (keysArea.width() - 6 * dp) / (layout?.units ?: 10f)

    /** The learned offset at a point, blended smoothly between zone centres so no key edge jumps. */
    private fun zoneOffset(arr: FloatArray, x: Float, y: Float): Float {
        if (keysArea.isEmpty) return 0f
        val fc = ((x - keysArea.left) / keysArea.width() * ZONE_COLS - 0.5f).coerceIn(0f, ZONE_COLS - 1f)
        val fr = ((y - keysArea.top) / keysArea.height() * ZONE_ROWS - 0.5f).coerceIn(0f, ZONE_ROWS - 1f)
        val c0 = fc.toInt().coerceAtMost(ZONE_COLS - 2); val r0 = fr.toInt().coerceAtMost(ZONE_ROWS - 2)
        val tc = fc - c0; val tr = fr - r0
        val a = arr[r0 * ZONE_COLS + c0] * (1 - tc) + arr[r0 * ZONE_COLS + c0 + 1] * tc
        val b = arr[(r0 + 1) * ZONE_COLS + c0] * (1 - tc) + arr[(r0 + 1) * ZONE_COLS + c0 + 1] * tc
        return a * (1 - tr) + b * tr
    }

    private fun correctedX(x: Float, y: Float): Float =
        if (settings?.adaptiveTouch == true) x - zoneOffset(zoneX, x, y) * keyUnitW() else x

    private fun correctedY(x: Float, y: Float): Float =
        if (settings?.adaptiveTouch == true) y - zoneOffset(zoneY, x, y) * rowPitch else y

    private fun learnTouch(p: Ptr) {
        val k = p.typedKey ?: return
        if (settings?.adaptiveTouch != true || p.flick || !isLetterKey(k)) return
        if (layout?.id == "num") return
        // only clean taps: a finger that slid around says nothing about where it aims
        if (hypot(p.x - p.downX, p.y - p.downY) > 10 * dp) return
        val ox = ((p.downX - k.rect.centerX()) / keyUnitW()).coerceIn(-0.5f, 0.5f)
        val oy = ((p.downY - k.rect.centerY()) / rowPitch).coerceIn(-0.5f, 0.5f)
        val z = zoneOf(p.downX, p.downY)
        // learn quickly at first, then settle into a slow average
        val n = ++zoneN[z]
        val rate = max(0.025f, 1f / (n + 8))
        zoneX[z] = (zoneX[z] + (ox - zoneX[z]) * rate).coerceIn(-ZONE_CAP_X, ZONE_CAP_X)
        zoneY[z] = (zoneY[z] + (oy - zoneY[z]) * rate).coerceIn(-ZONE_CAP_Y, ZONE_CAP_Y)
        if (zoneN[z] > 100000) zoneN[z] = 100000
        if (++touchSamples % 40 == 0) onTouchModelChanged?.invoke(touchModelString())
    }

    private fun touchModelString(): String {
        val sb = StringBuilder()
        for (i in 0 until ZONE_ROWS * ZONE_COLS) {
            if (i > 0) sb.append(',')
            sb.append(String.format(java.util.Locale.US, "%.4f,%.4f,%d", zoneX[i], zoneY[i], zoneN[i]))
        }
        return sb.toString()
    }

    // --- strip

    private fun stripUp(p: Ptr) {
        val t = p.toolbar
        if (t != null) {
            val r = toolbarBtns.firstOrNull { it.first == t }?.second
            if (r == null || r.contains(p.x, p.y) || abs(p.y - p.downY) < 30 * dp) {
                if (t == Toolbar.TOGGLE) { toolbarOpen = !toolbarOpen; updateStripTarget() }
                else listener?.onToolbar(t)
            }
            return
        }
        if (p.longFired) return
        if (searchQuery != null) {
            if (!p.stripScrolled && p.stripIndex >= SEARCH_HIT) searchResults.getOrNull(p.stripIndex - SEARCH_HIT)?.let { listener?.onEmoji(it) }
            return
        }
        if (p.stripIndex == 100) { clipChip?.let { listener?.onPasteClip(it) }; return }
        val s = suggestions.slots.getOrNull(p.stripIndex) ?: return
        listener?.onSuggestion(s)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllPointers()
        Choreographer.getInstance().removeFrameCallback(frame)
        animating = false
        updateLiveLight()
    }

    companion object {
        const val FEEDBACK_DOWN = 0
        const val FEEDBACK_TICK = 1
        const val FEEDBACK_LIGHT = 2
        const val FEEDBACK_HEAVY = 3

        /** strip index of the first emoji search match (indexes below are other strip targets) */
        private const val SEARCH_HIT = 1000
        private const val TRAIL_MS = 380f

        private const val ZONE_ROWS = 4
        private const val ZONE_COLS = 3
        private const val ZONE_CAP_X = 0.3f
        private const val ZONE_CAP_Y = 0.3f
    }
}
