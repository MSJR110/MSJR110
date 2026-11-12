package dev.msjr.prism.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import dev.msjr.prism.data.ClipboardStore
import dev.msjr.prism.data.Emoji
import dev.msjr.prism.data.Prefs
import dev.msjr.prism.keyboard.Key
import dev.msjr.prism.keyboard.KeyType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Something that takes the keyboard's place for a while (emoji, clipboard, editing). It owns the strip
 * area ([header]) and the keys area ([body]), follows one finger, and keeps a small bar of real keys at
 * the bottom so letters, space and delete are never more than a thumb away.
 */
internal abstract class Panel(protected val host: KeyboardView) {
    val header = RectF()
    val body = RectF()
    protected val content = RectF()
    protected val dp get() = host.dp
    protected val sp get() = host.sp

    // --- the bottom bar: [ABC] [ space ] [⌫]
    private val barKeys = ArrayList<Key>()
    private var barKey: Key? = null
    private var repeat = 0
    private val repeater = object : Runnable {
        override fun run() {
            if (barKey?.type != KeyType.DELETE) return
            repeat++
            host.listener?.onDelete(repeat)
            host.ui.postDelayed(this, if (repeat < 6) 70L else if (repeat < 14) 50L else 110L)
        }
    }

    open fun onShow() {}

    open fun layout() {
        barKeys.clear()
        val barH = 46 * dp
        val y0 = body.bottom - barH - 4 * dp
        val unit = (body.width() - 6 * dp) / 10f
        val abc = Key(KeyType.LETTERS, if (host.persianUi) "ابپ" else "ABC", width = 1.6f)
        val space = Key(KeyType.SPACE, if (host.persianUi) "فاصله" else "space", " ", 6.4f)
        val del = Key(KeyType.DELETE, "del", width = 2f)
        var x = body.left + 3 * dp
        for (k in listOf(abc, space, del)) {
            val kw = k.width * unit
            k.rect.set(x + host.gapX / 2, y0, x + kw - host.gapX / 2, y0 + barH - 6 * dp)
            k.hit.set(x, y0 - 4 * dp, x + kw, body.bottom + host.bottomInset)
            x += kw
            barKeys += k
        }
        content.set(body.left + 4 * dp, body.top + 2 * dp, body.right - 4 * dp, y0 - 4 * dp)
    }

    abstract fun draw(c: Canvas, th: Theme, alpha: Float)

    open fun step(dt: Float): Boolean {
        var busy = false
        for (k in barKeys) if (host.stepKey(k, dt)) busy = true
        return busy
    }

    abstract fun down(x: Float, y: Float)
    abstract fun move(x: Float, y: Float)
    abstract fun up(x: Float, y: Float, vx: Float, vy: Float)

    open fun cancel() {
        barKey?.pressTarget = 0f
        barKey = null
        host.ui.removeCallbacks(repeater)
    }

    protected fun drawBar(c: Canvas, th: Theme, alpha: Float) {
        for (k in barKeys) {
            val (tint, ta) = host.tintFor(k, th)
            host.glass.drawGlass(c, k.rect, host.radius, k.press, tint, ta, alpha = alpha)
            host.iconPaint.color = th.label; host.iconPaint.alpha = (255 * alpha).toInt(); host.iconPaint.strokeWidth = 1.8f * dp
            val lp = host.labelPaint
            when (k.type) {
                KeyType.DELETE -> Icons.delete(c, k.rect.centerX(), k.rect.centerY(), k.rect.height(), host.iconPaint, false)
                KeyType.SPACE -> {
                    lp.typeface = host.typeface; lp.textSize = 13f * sp
                    lp.color = th.labelDim; lp.alpha = (220 * alpha).toInt()
                    host.drawCentered(c, k.label, k.rect.centerX(), k.rect.centerY(), lp)
                }
                else -> {
                    lp.typeface = host.typefaceBold; lp.textSize = 15f * sp
                    lp.color = th.label; lp.alpha = (255 * alpha).toInt()
                    host.drawCentered(c, k.label, k.rect.centerX(), k.rect.centerY(), lp)
                }
            }
        }
    }

    /** True if the touch landed on the bar (and is handled). */
    protected fun barDown(x: Float, y: Float): Boolean {
        val k = barKeys.firstOrNull { it.hit.contains(x, y) } ?: return false
        barKey = k
        k.pressTarget = 1f
        k.press = max(k.press, 0.85f)
        host.listener?.onKeyFeedback(k, KeyboardView.FEEDBACK_DOWN)
        if (k.type == KeyType.DELETE) {
            repeat = 0
            host.listener?.onDelete(0)
            host.ui.postDelayed(repeater, 380)
        }
        host.kick()
        return true
    }

    /** True if the lifted finger was on the bar. */
    protected fun barUp(): Boolean {
        val k = barKey ?: return false
        barKey = null
        k.pressTarget = 0f
        host.ui.removeCallbacks(repeater)
        when (k.type) {
            KeyType.LETTERS -> host.listener?.onToolbar(KeyboardView.Toolbar.CLOSE_PANEL)
            KeyType.SPACE -> host.listener?.onSpace()
            else -> {}
        }
        host.kick()
        return true
    }

    protected val onBar: Boolean get() = barKey != null

    protected fun title(c: Canvas, th: Theme, alpha: Float, text: String) {
        val p = host.stripPaint
        p.typeface = host.typefaceBold
        p.textSize = 15f * sp
        p.color = th.label; p.alpha = (255 * alpha).toInt()
        host.drawCentered(c, text, header.centerX(), header.centerY(), p)
    }
}

// ============================================================================================ emoji

internal class EmojiPanel(host: KeyboardView) : Panel(host) {

    private class Section(val icon: String, val title: String, val shown: List<String>, val items: List<Emoji.Item?>, var top: Float = 0f)

    private var sections: List<Section>? = null
    private var builtFor: Any? = null
    private var cols = 8
    private var cell = 44f
    private val titleH get() = 28 * dp
    private val scroll = ScrollPhysics()
    private val tmp = RectF()

    // tabs
    private var tabsLeft = 0f
    private var tabW = 1f
    private val bubble = Spring(0f, stiffness = 420f, damping = 0.82f)
    private val searchBtn = RectF()

    // touch
    private var downX = 0f
    private var downY = 0f
    private var scrolling = false
    private var onHeader = false
    private var pressed: Pair<Section, Int>? = null
    private val pop = Spring(0f, stiffness = 1400f, damping = 0.6f)
    private var popSection: Section? = null
    private var popIndex = -1
    private val longPress = Runnable { openTones() }

    // skin-tone picker
    private var toneItem: Emoji.Item? = null
    private var toneIndex = 0
    private val toneRect = RectF()
    private var toneCell = 0f
    private val toneT = Spring(0f, stiffness = 1300f, damping = 0.74f)

    fun refresh() { sections = null }

    override fun onShow() {
        sections = null
        scroll.jumpTo(0f)
        bubble.snap(0f)
        toneItem = null
        Emoji.ensureLoaded(host.context) { sections = null; host.invalidate() }
        Emoji.loadTones(Prefs.get(host.context))
    }

    override fun layout() {
        super.layout()
        cols = max(7, (content.width() / (46 * dp)).toInt())
        cell = content.width() / cols
        sections = null
    }

    private fun sections(): List<Section> {
        val groups = Emoji.groups
        sections?.let { if (builtFor === groups) return it }
        val fa = host.persianUi
        val list = ArrayList<Section>()
        val recents = Emoji.recents(Prefs.get(host.context)).take(cols * 3)
        if (recents.isNotEmpty()) list += Section("🕘", if (fa) "پرکاربردهای اخیر" else "Recent", recents, recents.map { Emoji.item(it) })
        for (g in groups) list += Section(g.icon, if (fa) g.nameFa else g.nameEn, g.items.map { Emoji.shown(it) }, g.items)
        var y = 0f
        for (s in list) {
            s.top = y
            y += titleH + ceil(s.shown.size / cols.toFloat()) * cell + 6 * dp
        }
        scroll.max = max(0f, y - content.height())
        sections = list
        builtFor = groups
        return list
    }

    private fun currentSection(secs: List<Section>): Int {
        var cur = 0
        for ((i, s) in secs.withIndex()) if (s.top <= scroll.pos + cell) cur = i
        return cur
    }

    override fun step(dt: Float): Boolean {
        var busy = super.step(dt)
        if (scroll.step(dt)) busy = true
        if (bubble.step(dt)) busy = true
        if (pop.step(dt)) busy = true
        toneT.target = if (toneItem != null) 1f else 0f
        if (toneT.step(dt)) busy = true
        return busy
    }

    override fun draw(c: Canvas, th: Theme, alpha: Float) {
        val secs = sections()
        drawTabs(c, th, alpha, secs)

        c.save()
        c.clipRect(content)
        val ep = host.emojiPaint
        ep.textSize = 27f * sp
        val tp = host.stripPaint
        val top = content.top - scroll.pos
        for (s in secs) {
            val sTop = top + s.top
            val rows = ceil(s.shown.size / cols.toFloat()).toInt()
            if (sTop > content.bottom || sTop + titleH + rows * cell < content.top) continue
            // section title, on the side the language reads from
            tp.typeface = host.typeface
            tp.textSize = 12f * sp
            tp.color = th.labelDim; tp.alpha = (220 * alpha).toInt()
            val tw = tp.measureText(s.title)
            val tx = if (host.persianUi) content.right - 8 * dp - tw / 2f else content.left + 8 * dp + tw / 2f
            host.drawCentered(c, s.title, tx, sTop + titleH / 2f + 2 * dp, tp)
            val gridTop = sTop + titleH
            val r0 = max(0, ((content.top - gridTop) / cell).toInt())
            val r1 = min(rows - 1, ((content.bottom - gridTop) / cell).toInt())
            for (r in r0..r1) for (col in 0 until cols) {
                val i = r * cols + col
                if (i >= s.shown.size) break
                val cx = content.left + col * cell + cell / 2f
                val cy = gridTop + r * cell + cell / 2f
                val isPop = popSection === s && popIndex == i && pop.value > 0.002f
                if (isPop) {
                    val pv = pop.value
                    tmp.set(cx - cell / 2 + 2 * dp, cy - cell / 2 + 2 * dp, cx + cell / 2 - 2 * dp, cy + cell / 2 - 2 * dp)
                    host.accentPaint.color = GlassRenderer.adjustAlpha(th.accent, 0.30f * min(1f, pv) * alpha)
                    c.drawRoundRect(tmp, 13 * dp, 13 * dp, host.accentPaint)
                    c.save()
                    val sc = 1f + 0.22f * pv
                    c.scale(sc, sc, cx, cy)
                }
                ep.alpha = (255 * alpha).toInt()
                host.drawCentered(c, s.shown[i], cx, cy, ep)
                if (isPop) c.restore()
                // a small corner mark on emoji that come in skin tones
                if (s.items[i]?.tones != null) {
                    host.accentPaint.color = GlassRenderer.adjustAlpha(th.labelDim, 0.5f * alpha)
                    c.drawCircle(cx + cell * 0.36f, cy + cell * 0.36f, 1.5f * dp, host.accentPaint)
                }
            }
        }
        if (secs.isEmpty() || (secs.size == 1 && Emoji.groups.isEmpty())) {
            tp.typeface = host.typeface; tp.textSize = 14f * sp; tp.color = th.labelDim; tp.alpha = (255 * alpha).toInt()
            host.drawCentered(c, "…", content.centerX(), content.centerY(), tp)
        }
        c.restore()
        drawBar(c, th, alpha)
        drawTones(c, th)
    }

    private fun drawTabs(c: Canvas, th: Theme, alpha: Float, secs: List<Section>) {
        val n = secs.size
        val sb = min(header.height(), header.width() / (n + 1f))
        searchBtn.set(header.left, header.top, header.left + sb, header.bottom)
        host.iconPaint.color = th.label; host.iconPaint.alpha = (220 * alpha).toInt(); host.iconPaint.strokeWidth = 1.7f * dp
        Icons.search(c, searchBtn.centerX(), searchBtn.centerY(), min(sb, header.height()) * 0.8f, host.iconPaint)
        if (n == 0) return
        tabsLeft = header.left + sb
        tabW = (header.width() - sb) / n
        val cur = currentSection(secs)
        if (bubble.target != cur.toFloat()) { bubble.target = cur.toFloat(); host.kick() }
        // one glass bubble that slides (and stretches a little while it moves) to the current section
        val sz = min(tabW, header.height()) - 8 * dp
        val bx = tabsLeft + (bubble.value + 0.5f) * tabW
        val stretch = min(sz * 0.6f, abs(bubble.velocity) * tabW * 0.012f)
        tmp.set(bx - sz / 2 - stretch / 2, header.centerY() - sz / 2, bx + sz / 2 + stretch / 2, header.centerY() + sz / 2)
        host.glass.drawGlass(c, tmp, sz / 2, 0f, th.accent, 0.38f, shadow = false, alpha = alpha)
        val ep = host.emojiPaint
        ep.textSize = 18f * sp
        for ((i, s) in secs.withIndex()) {
            val near = 1f - min(1f, abs(bubble.value - i))
            ep.alpha = ((150 + 105 * near) * alpha).toInt()
            host.drawCentered(c, s.icon, tabsLeft + (i + 0.5f) * tabW, header.centerY(), ep)
        }
    }

    private fun drawTones(c: Canvas, th: Theme) {
        val item = toneItem ?: return
        val t = toneT.value
        if (t <= 0.01f) return
        c.save()
        val sc = 0.7f + 0.3f * t
        c.scale(sc, sc, toneRect.centerX(), toneRect.bottom)
        host.glass.drawGlass(c, toneRect, toneRect.height() / 2f, 0.15f, th.balloon, if (th.dark) 0.8f else 0.95f, alpha = min(1f, t * 1.5f))
        val ep = host.emojiPaint
        ep.textSize = 26f * sp
        ep.alpha = (255 * min(1f, t * 1.5f)).toInt()
        for (i in 0..5) {
            val cx = toneRect.left + 6 * dp + toneCell * (i + 0.5f)
            if (i == toneIndex) {
                host.accentPaint.color = GlassRenderer.adjustAlpha(th.accent, 0.85f)
                c.drawCircle(cx, toneRect.centerY(), toneCell * 0.46f, host.accentPaint)
            }
            host.drawCentered(c, Emoji.variant(item, i), cx, toneRect.centerY(), ep)
        }
        c.restore()
    }

    private fun cellAt(x: Float, y: Float): Pair<Section, Int>? {
        if (!content.contains(x, y)) return null
        val cy = y - content.top + scroll.pos
        for (s in sections()) {
            val gridTop = s.top + titleH
            val rows = ceil(s.shown.size / cols.toFloat()).toInt()
            if (cy >= gridTop && cy < gridTop + rows * cell) {
                val r = ((cy - gridTop) / cell).toInt()
                val col = ((x - content.left) / cell).toInt().coerceIn(0, cols - 1)
                val idx = r * cols + col
                return if (idx < s.shown.size) s to idx else null
            }
        }
        return null
    }

    private fun cellCenter(s: Section, i: Int): Pair<Float, Float> {
        val cx = content.left + (i % cols) * cell + cell / 2f
        val cy = content.top - scroll.pos + s.top + titleH + (i / cols) * cell + cell / 2f
        return cx to cy
    }

    private fun openTones() {
        val (s, i) = pressed ?: return
        val item = s.items[i] ?: return
        if (item.tones == null) return
        val (cx, cy) = cellCenter(s, i)
        toneCell = min(46 * dp, (body.width() - 20 * dp) / 6f)
        val w = toneCell * 6 + 12 * dp
        val h = 54 * dp
        var left = cx - w / 2f
        left = left.coerceIn(body.left + 4 * dp, body.right - 4 * dp - w)
        var top = cy - cell / 2f - h - 2 * dp
        if (top < header.top + 2 * dp) top = cy + cell / 2f + 2 * dp
        toneRect.set(left, top, left + w, top + h)
        toneItem = item
        toneIndex = Emoji.toneOf(item)
        toneT.snap(0f)
        host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_HEAVY)
        host.kick()
    }

    override fun down(x: Float, y: Float) {
        downX = x; downY = y
        scrolling = false
        onHeader = false
        pressed = null
        if (barDown(x, y)) return
        if (header.contains(x, y)) { onHeader = true; host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_LIGHT); return }
        scroll.startDrag()
        pressed = cellAt(x, y)
        pressed?.let { (s, i) ->
            popSection = s; popIndex = i
            pop.target = 1f
            if (s.items[i]?.tones != null) host.ui.postDelayed(longPress, 330)
        }
        host.kick()
    }

    private var lastY = 0f

    override fun move(x: Float, y: Float) {
        if (onBar || onHeader) return
        if (toneItem != null) {
            val i = ((x - toneRect.left - 6 * dp) / toneCell).toInt().coerceIn(0, 5)
            if (i != toneIndex) { toneIndex = i; host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_TICK); host.invalidate() }
            return
        }
        if (!scrolling && abs(y - downY) > 8 * dp) {
            scrolling = true
            lastY = y
            pressed = null
            pop.target = 0f
            host.ui.removeCallbacks(longPress)
        }
        if (scrolling) {
            scroll.dragBy(lastY - y)
            lastY = y
            host.invalidate()
        }
    }

    override fun up(x: Float, y: Float, vx: Float, vy: Float) {
        host.ui.removeCallbacks(longPress)
        if (barUp()) return
        if (onHeader) {
            onHeader = false
            if (abs(y - downY) > 30 * dp) return
            if (searchBtn.contains(x, y)) { host.listener?.onToolbar(KeyboardView.Toolbar.EMOJI_SEARCH); return }
            val secs = sections()
            if (secs.isNotEmpty() && x >= tabsLeft) {
                val i = ((x - tabsLeft) / tabW).toInt().coerceIn(0, secs.size - 1)
                scroll.animateTo(secs[i].top)
                host.kick()
            }
            return
        }
        val item = toneItem
        if (item != null) {
            // picked a tone: this emoji keeps it from now on
            Emoji.rememberTone(Prefs.get(host.context), item, toneIndex)
            host.listener?.onEmoji(Emoji.variant(item, toneIndex))
            toneItem = null
            pressed = null
            pop.target = 0f
            scroll.endDrag(0f)
            sections = null
            host.kick()
            return
        }
        if (scrolling) scroll.endDrag(-vy) else scroll.endDrag(0f)
        pressed?.let { (s, i) -> host.listener?.onEmoji(s.shown[i]) }
        pressed = null
        pop.target = 0f
        host.kick()
    }

    override fun cancel() {
        super.cancel()
        host.ui.removeCallbacks(longPress)
        pressed = null; toneItem = null; pop.target = 0f
        scroll.endDrag(0f)
    }
}

// ============================================================================================ clipboard

internal class ClipboardPanel(host: KeyboardView) : Panel(host) {

    private class Card(val item: ClipboardStore.Item, val rect: RectF, val layout: StaticLayout)

    private val cards = ArrayList<Card>()
    private var built = false
    private val cardText = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val scroll = ScrollPhysics()
    private val tmp = RectF()
    private val closeBtn = RectF()
    private val clearBtn = RectF()

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var scrolling = false
    private var swiping = false
    private var onHeader = false
    private var pressed: Card? = null
    private var swipeX = 0f
    private var longFired = false
    private val longPress = Runnable {
        // holding a card pins it (pinned cards stay when the history is cleared)
        pressed?.let {
            host.clipboardStore?.togglePin(it.item)
            built = false
            longFired = true
            host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_HEAVY)
            host.invalidate()
        }
    }

    fun refresh() { built = false }

    override fun onShow() { built = false; scroll.jumpTo(0f) }

    override fun layout() { super.layout(); built = false }

    private fun build(th: Theme) {
        if (built) return
        built = true
        cards.clear()
        val items = host.clipboardStore?.all() ?: emptyList()
        val colsN = 2
        val gap = 8 * dp
        val cw = (content.width() - gap) / colsN
        val ch = 72 * dp
        cardText.textSize = 13.5f * sp
        cardText.typeface = host.typeface
        cardText.color = th.label
        for ((i, it) in items.withIndex()) {
            val col = i % colsN; val r = i / colsN
            val rect = RectF(col * (cw + gap), r * (ch + gap), col * (cw + gap) + cw, r * (ch + gap) + ch)
            val text = it.text.take(300)
            val sl = StaticLayout.Builder.obtain(text, 0, text.length, cardText, (cw - 22 * dp).toInt())
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            cards += Card(it, rect, sl)
        }
        val h = if (items.isEmpty()) 0f else ceil(items.size / colsN.toFloat()) * (ch + gap)
        scroll.max = max(0f, h - content.height())
        if (scroll.pos > scroll.max) scroll.jumpTo(scroll.max)
    }

    override fun step(dt: Float): Boolean {
        var busy = super.step(dt)
        if (scroll.step(dt)) busy = true
        return busy
    }

    override fun draw(c: Canvas, th: Theme, alpha: Float) {
        build(th)
        title(c, th, alpha, if (host.persianUi) "کلیپ‌بورد" else "Clipboard")
        val s = 34 * dp
        val cy = header.centerY()
        closeBtn.set(8 * dp, cy - s / 2, 8 * dp + s, cy + s / 2)
        clearBtn.set(header.right - 8 * dp - s, cy - s / 2, header.right - 8 * dp, cy + s / 2)
        host.drawRoundButton(c, closeBtn, th, alpha)
        Icons.close(c, closeBtn.centerX(), closeBtn.centerY(), s, host.iconPaint)
        host.drawRoundButton(c, clearBtn, th, alpha)
        Icons.trash(c, clearBtn.centerX(), clearBtn.centerY(), s, host.iconPaint)

        c.save()
        c.clipRect(content)
        if (cards.isEmpty()) {
            val p = host.stripPaint
            p.typeface = host.typeface; p.textSize = 14f * sp
            p.color = th.labelDim; p.alpha = (255 * alpha).toInt()
            host.drawCentered(c, "هنوز چیزی کپی نکردی", content.centerX(), content.centerY() - 10 * dp, p)
            p.textSize = 12f * sp
            host.drawCentered(c, "متن‌هایی که کپی می‌کنی اینجا جمع می‌شن (فقط روی گوشی خودت)", content.centerX(), content.centerY() + 14 * dp, p)
        }
        val (tint, ta) = host.tintOf(th.keyTint)
        for (card in cards) {
            tmp.set(card.rect)
            tmp.offset(content.left, content.top - scroll.pos)
            if (tmp.bottom < content.top || tmp.top > content.bottom) continue
            val isPressed = pressed === card
            var a = alpha
            if (isPressed && swiping) {
                tmp.offset(swipeX, 0f)
                a *= (1f - min(1f, abs(swipeX) / (tmp.width() * 0.9f))).coerceIn(0.15f, 1f)
            }
            host.glass.drawGlass(c, tmp, 16 * dp, if (isPressed && !swiping) 0.8f else 0f, if (card.item.pinned) th.accent else tint,
                if (card.item.pinned) 0.3f else ta, shadow = false, alpha = a)
            c.save()
            c.translate(tmp.left + 11 * dp, tmp.top + 9 * dp)
            cardText.color = th.label
            cardText.alpha = (255 * a).toInt()
            card.layout.draw(c)
            c.restore()
            if (card.item.pinned) {
                host.iconPaint.color = th.label; host.iconPaint.alpha = (220 * a).toInt(); host.iconPaint.strokeWidth = 1.4f * dp
                Icons.pin(c, tmp.right - 12 * dp, tmp.bottom - 14 * dp, 28 * dp, host.iconPaint)
            }
        }
        c.restore()
        drawBar(c, th, alpha)
    }

    private fun cardAt(x: Float, y: Float): Card? {
        if (!content.contains(x, y)) return null
        val lx = x - content.left
        val ly = y - content.top + scroll.pos
        return cards.firstOrNull { it.rect.contains(lx, ly) }
    }

    override fun down(x: Float, y: Float) {
        downX = x; downY = y
        scrolling = false; swiping = false; onHeader = false; longFired = false
        pressed = null
        swipeX = 0f
        if (barDown(x, y)) return
        if (header.contains(x, y)) { onHeader = true; host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_LIGHT); return }
        scroll.startDrag()
        pressed = cardAt(x, y)
        if (pressed != null) host.ui.postDelayed(longPress, 460)
        host.invalidate()
    }

    override fun move(x: Float, y: Float) {
        if (onBar || onHeader || longFired) return
        if (!scrolling && !swiping) {
            val dx = abs(x - downX); val dy = abs(y - downY)
            if (dy > 8 * dp && dy >= dx) { scrolling = true; lastY = y; pressed = null; host.ui.removeCallbacks(longPress) }
            // a sideways drag on a card throws it away
            else if (dx > 12 * dp && pressed != null) { swiping = true; host.ui.removeCallbacks(longPress) }
        }
        if (scrolling) { scroll.dragBy(lastY - y); lastY = y }
        if (swiping) swipeX = x - downX
        host.invalidate()
    }

    override fun up(x: Float, y: Float, vx: Float, vy: Float) {
        host.ui.removeCallbacks(longPress)
        if (barUp()) return
        if (onHeader) {
            onHeader = false
            if (closeBtn.contains(x, y)) host.listener?.onToolbar(KeyboardView.Toolbar.CLOSE_PANEL)
            else if (clearBtn.contains(x, y)) host.listener?.onToolbar(KeyboardView.Toolbar.CLEAR_CLIPBOARD)
            return
        }
        val card = pressed
        if (swiping && card != null) {
            if (abs(swipeX) > card.rect.width() * 0.4f || abs(vx) > 1400f) {
                host.clipboardStore?.remove(card.item)
                built = false
                host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_TICK)
            }
        } else if (scrolling) {
            scroll.endDrag(-vy)
            pressed = null
            host.kick()
            return
        } else if (card != null && !longFired) {
            host.listener?.onPasteClip(card.item.text)
        }
        scroll.endDrag(0f)
        pressed = null; swiping = false; swipeX = 0f
        host.invalidate()
        host.kick()
    }

    override fun cancel() {
        super.cancel()
        host.ui.removeCallbacks(longPress)
        pressed = null; swiping = false
        scroll.endDrag(0f)
    }
}

// ============================================================================================ editing

/** Cursor keys, selection, copy / cut / paste, undo / redo — for when the text needs surgery. */
internal class EditPanel(host: KeyboardView) : Panel(host) {

    private class Btn(val action: KeyboardView.EditAction, val labelFa: String, val labelEn: String, val repeats: Boolean = false) {
        val rect = RectF()
        val press = Spring(0f, stiffness = 1600f, damping = 0.7f)
    }

    // 4 columns × 3 rows, laid out left to right
    private val buttons = listOf(
        Btn(KeyboardView.EditAction.HOME, "اول خط", "Home"), Btn(KeyboardView.EditAction.UP, "", "", true),
        Btn(KeyboardView.EditAction.END, "آخر خط", "End"), Btn(KeyboardView.EditAction.SELECT_ALL, "همه", "All"),
        Btn(KeyboardView.EditAction.LEFT, "", "", true), Btn(KeyboardView.EditAction.SELECT, "انتخاب", "Select"),
        Btn(KeyboardView.EditAction.RIGHT, "", "", true), Btn(KeyboardView.EditAction.COPY, "کپی", "Copy"),
        Btn(KeyboardView.EditAction.UNDO, "برگرد", "Undo"), Btn(KeyboardView.EditAction.DOWN, "", "", true),
        Btn(KeyboardView.EditAction.REDO, "دوباره", "Redo"), Btn(KeyboardView.EditAction.PASTE, "چسباندن", "Paste"),
    )
    private val closeBtn = RectF()
    private val cutBtn = RectF()
    private var active: Btn? = null
    private var onHeader = false
    private var repeats = 0
    private val repeater = object : Runnable {
        override fun run() {
            val b = active ?: return
            repeats++
            host.listener?.onEdit(b.action)
            host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_TICK)
            host.ui.postDelayed(this, if (repeats < 8) 80L else 45L)
        }
    }

    override fun layout() {
        super.layout()
        val gap = 7 * dp
        val bw = (content.width() - gap * 3) / 4f
        val bh = (content.height() - gap * 2 - 4 * dp) / 3f
        for ((i, b) in buttons.withIndex()) {
            val col = i % 4; val row = i / 4
            val l = content.left + col * (bw + gap)
            val t = content.top + 2 * dp + row * (bh + gap)
            b.rect.set(l, t, l + bw, t + bh)
        }
    }

    override fun step(dt: Float): Boolean {
        var busy = super.step(dt)
        for (b in buttons) if (b.press.step(dt)) busy = true
        return busy
    }

    override fun draw(c: Canvas, th: Theme, alpha: Float) {
        val fa = host.persianUi
        title(c, th, alpha, if (fa) "ویرایش متن" else "Edit text")
        val s = 34 * dp
        val cy = header.centerY()
        closeBtn.set(8 * dp, cy - s / 2, 8 * dp + s, cy + s / 2)
        cutBtn.set(header.right - 8 * dp - s, cy - s / 2, header.right - 8 * dp, cy + s / 2)
        host.drawRoundButton(c, closeBtn, th, alpha)
        Icons.close(c, closeBtn.centerX(), closeBtn.centerY(), s, host.iconPaint)
        host.drawRoundButton(c, cutBtn, th, alpha)
        Icons.cut(c, cutBtn.centerX(), cutBtn.centerY(), s, host.iconPaint)

        val ip = host.iconPaint
        val lp = host.labelPaint
        for (b in buttons) {
            val on = b.action == KeyboardView.EditAction.SELECT && host.selecting
            val arrow = b.repeats
            val (tint, ta) = if (on) th.accent to 0.6f else if (arrow) host.tintOf(th.keyTint) else host.tintOf(th.modTint)
            host.glass.drawGlass(c, b.rect, 16 * dp, b.press.value, tint, ta, alpha = alpha)
            ip.color = if (on && th.dark) Color.WHITE else th.label
            ip.alpha = (255 * alpha).toInt(); ip.strokeWidth = (if (arrow) 2.2f else 1.7f) * dp
            val cx = b.rect.centerX()
            val label = if (fa) b.labelFa else b.labelEn
            val icy = if (label.isEmpty()) b.rect.centerY() else b.rect.centerY() - 8 * dp
            val sz = min(b.rect.height(), 52 * dp)
            when (b.action) {
                KeyboardView.EditAction.LEFT -> Icons.arrow(c, cx, icy, sz, ip, -1, 0)
                KeyboardView.EditAction.RIGHT -> Icons.arrow(c, cx, icy, sz, ip, 1, 0)
                KeyboardView.EditAction.UP -> Icons.arrow(c, cx, icy, sz, ip, 0, -1)
                KeyboardView.EditAction.DOWN -> Icons.arrow(c, cx, icy, sz, ip, 0, 1)
                KeyboardView.EditAction.HOME -> Icons.arrowToEdge(c, cx, icy, sz, ip, if (fa) 1 else -1)
                KeyboardView.EditAction.END -> Icons.arrowToEdge(c, cx, icy, sz, ip, if (fa) -1 else 1)
                KeyboardView.EditAction.SELECT -> Icons.select(c, cx, icy, sz, ip)
                KeyboardView.EditAction.SELECT_ALL -> Icons.selectAll(c, cx, icy, sz, ip)
                KeyboardView.EditAction.COPY -> Icons.copy(c, cx, icy, sz, ip)
                KeyboardView.EditAction.PASTE -> Icons.clipboard(c, cx, icy, sz, ip)
                KeyboardView.EditAction.UNDO -> Icons.undo(c, cx, icy, sz, ip, false)
                KeyboardView.EditAction.REDO -> Icons.undo(c, cx, icy, sz, ip, true)
                KeyboardView.EditAction.CUT -> Icons.cut(c, cx, icy, sz, ip)
            }
            if (label.isNotEmpty()) {
                lp.typeface = host.typeface; lp.textSize = 11f * sp
                lp.color = if (on && th.dark) Color.WHITE else th.labelDim
                lp.alpha = (235 * alpha).toInt()
                host.drawCentered(c, label, cx, b.rect.centerY() + 13 * dp, lp)
            }
        }
        drawBar(c, th, alpha)
    }

    override fun down(x: Float, y: Float) {
        onHeader = false
        active = null
        if (barDown(x, y)) return
        if (header.contains(x, y)) { onHeader = true; host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_LIGHT); return }
        val b = buttons.firstOrNull { it.rect.contains(x, y) } ?: buttons.minByOrNull {
            val dx = it.rect.centerX() - x; val dy = it.rect.centerY() - y; dx * dx + dy * dy
        }?.takeIf { content.contains(x, y) } ?: return
        active = b
        b.press.target = 1f
        host.listener?.onKeyFeedback(null, KeyboardView.FEEDBACK_LIGHT)
        // arrows act at once and keep going while held; the rest act when released
        if (b.repeats) {
            repeats = 0
            host.listener?.onEdit(b.action)
            host.ui.postDelayed(repeater, 320)
        }
        host.kick()
    }

    override fun move(x: Float, y: Float) {}

    override fun up(x: Float, y: Float, vx: Float, vy: Float) {
        host.ui.removeCallbacks(repeater)
        if (barUp()) return
        if (onHeader) {
            onHeader = false
            if (closeBtn.contains(x, y)) host.listener?.onToolbar(KeyboardView.Toolbar.CLOSE_PANEL)
            else if (cutBtn.contains(x, y)) host.listener?.onEdit(KeyboardView.EditAction.CUT)
            return
        }
        val b = active ?: return
        active = null
        b.press.target = 0f
        if (!b.repeats) host.listener?.onEdit(b.action)
        host.kick()
    }

    override fun cancel() {
        super.cancel()
        host.ui.removeCallbacks(repeater)
        active?.press?.target = 0f
        active = null
    }
}
