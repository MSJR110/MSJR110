package dev.msjr.prism.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.msjr.prism.data.ClipboardStore
import dev.msjr.prism.data.Prefs
import dev.msjr.prism.data.Settings
import dev.msjr.prism.feedback.Haptics
import dev.msjr.prism.feedback.SoundEngine
import dev.msjr.prism.keyboard.FieldKind
import dev.msjr.prism.keyboard.Key
import dev.msjr.prism.keyboard.KeyType
import dev.msjr.prism.keyboard.Layouts
import dev.msjr.prism.keyboard.Page
import dev.msjr.prism.settings.SettingsActivity
import dev.msjr.prism.text.Suggestion
import dev.msjr.prism.text.SuggestionEngine
import dev.msjr.prism.text.SuggestionResult
import dev.msjr.prism.text.TextTools
import dev.msjr.prism.ui.GlassRenderer
import dev.msjr.prism.ui.KeyboardView
import dev.msjr.prism.ui.Theme
import org.json.JSONArray
import org.json.JSONObject

class PrismIME : InputMethodService(), KeyboardView.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: Settings
    private lateinit var glass: GlassRenderer
    private lateinit var haptics: Haptics
    private lateinit var sound: SoundEngine
    private lateinit var engine: SuggestionEngine
    private lateinit var clipStore: ClipboardStore
    private var view: KeyboardView? = null
    private val ui = Handler(Looper.getMainLooper())

    private var fontRegular: Typeface = Typeface.DEFAULT
    private var fontBold: Typeface = Typeface.DEFAULT_BOLD

    // keyboard state
    private var lang = "fa"
    private var page = Page.LETTERS
    private var fieldKind = FieldKind.TEXT
    private var shift = 0
    private var lastShiftTap = 0L
    private var info: EditorInfo? = null
    private var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED

    // text tracking (no composing underline — iOS-like)
    private val word = StringBuilder()
    private var prevWord: String? = null
    private var cursor = -1
    private val recentCursors = IntArray(24) { -2 }
    private var recentIdx = 0
    private var midWord = false
    private var privateField = false
    private var allowSuggestions = true
    private var allowAutocorrect = true
    private var suggestions: SuggestionResult = SuggestionResult.EMPTY

    // smart behaviours
    private var lastAutoSpace = false
    private var pendingPrefixZwnj = false
    private var lastSpaceAt = 0L
    private var undoCorrection: Pair<String, String>? = null // original -> corrected
    private val rejected = HashSet<String>()
    private var swipeBase = -1
    private var trackpadActive = false
    private var shortcuts: Map<String, String> = emptyMap()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        settings = Prefs.load(this)
        lang = settings.lang
        glass = GlassRenderer(this)
        haptics = Haptics(this)
        sound = SoundEngine(this)
        engine = SuggestionEngine(this)
        clipStore = ClipboardStore(this)
        loadFonts()
        applyFeedbackSettings()
        loadShortcuts()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) { }
        engine.shutdown()
        sound.release()
        super.onDestroy()
    }

    private fun loadFonts() {
        if (settings.font == "vazir") {
            try {
                fontRegular = Typeface.createFromAsset(assets, "fonts/Vazirmatn-Medium.ttf")
                fontBold = Typeface.createFromAsset(assets, "fonts/Vazirmatn-Bold.ttf")
                return
            } catch (_: Throwable) { }
        }
        fontRegular = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontBold = Typeface.DEFAULT_BOLD
    }

    private fun applyFeedbackSettings() {
        haptics.configure(settings.haptic)
        sound.configure(settings.sound, settings.soundVolume, settings.soundRespectSilent)
    }

    private fun loadShortcuts() {
        val map = HashMap<String, String>()
        try {
            val o = JSONObject(Prefs.get(this).getString(Prefs.SHORTCUTS, "{}") ?: "{}")
            for (k in o.keys()) map[k.lowercase()] = o.getString(k)
        } catch (_: Throwable) { }
        shortcuts = map
        engine.shortcuts = map
    }

    override fun onCreateInputView(): View {
        window?.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        }
        val v = KeyboardView(this, glass)
        v.listener = this
        v.clipboardStore = clipStore
        v.setTouchModel(Prefs.get(this).getFloat(Prefs.TOUCH_OFFSET_X, 0f), Prefs.get(this).getFloat(Prefs.TOUCH_OFFSET_Y, 0f))
        v.onTouchModelChanged = { x, y ->
            Prefs.get(this).edit().putFloat(Prefs.TOUCH_OFFSET_X, x).putFloat(Prefs.TOUCH_OFFSET_Y, y).apply()
        }
        view = v
        applyLook()
        return v
    }

    private fun applyLook() {
        val v = view ?: return
        glass.configure(Theme.resolve(this, settings.theme), settings.glassStrength, settings.refraction)
        v.applySettings(settings, fontRegular, fontBold)
        v.setRecentEmoji(recentEmoji())
        rebuildLayout()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (!v.isShown || v.height == 0) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = loc[1] + v.visibleTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, v.width, loc[1] + v.height)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        info = editorInfo
        val cls = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        fieldKind = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            else -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                else -> FieldKind.TEXT
            }
        }
        val password = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noPersonal = (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        privateField = password || noPersonal
        val noSugFlag = (editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        allowSuggestions = settings.suggestions && !password && fieldKind == FieldKind.TEXT
        allowAutocorrect = allowSuggestions && !noSugFlag

        val noEnterAction = (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        enterAction = if (noEnterAction) EditorInfo.IME_ACTION_NONE else editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION

        page = when (fieldKind) {
            FieldKind.NUMBER, FieldKind.PHONE -> Page.NUMPAD
            else -> Page.LETTERS
        }
        // email / url fields want latin letters
        if ((fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.URL) && lang == "fa") lang = "en"
        else if (fieldKind == FieldKind.TEXT) lang = settings.lang

        shift = 0
        word.setLength(0); prevWord = null
        cursor = editorInfo.initialSelStart
        recentCursors.fill(-2)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
        suggestions = SuggestionResult.EMPTY
        trackpadActive = false

        view?.let {
            it.resetState()
            it.setMode(KeyboardView.Mode.KEYS)
            it.setEnterAction(enterAction)
            it.setSuggestions(SuggestionResult.EMPTY)
            refreshClipChip()
        }
        rebuildLayout()
        resync()
        updateShift()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.save(true)
        view?.resetState()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        engine.save(true)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.TOUCH_OFFSET_X || key == Prefs.TOUCH_OFFSET_Y || key == Prefs.RECENT_EMOJI) return
        val oldFont = settings.font
        settings = Prefs.load(this)
        if (key == Prefs.LANG) lang = settings.lang
        if (settings.font != oldFont) loadFonts()
        if (key == Prefs.SHORTCUTS) loadShortcuts()
        if (key == "bg_version") glass.resetBackdrop()
        applyFeedbackSettings()
        applyLook()
    }

    // ------------------------------------------------------------------ layout

    private fun rebuildLayout() {
        val v = view ?: return
        val l = when (page) {
            Page.LETTERS -> Layouts.letters(lang, fieldKind, settings.numberRow, settings.persianDigits, faShift = lang == "fa" && shift != 0)
            Page.SYMBOLS -> Layouts.symbols(lang, fieldKind, settings.persianDigits, false)
            Page.SYMBOLS2 -> Layouts.symbols(lang, fieldKind, settings.persianDigits, true)
            Page.NUMPAD -> Layouts.numpad(fieldKind == FieldKind.PHONE, lang == "fa" && settings.persianDigits && fieldKind != FieldKind.PHONE)
        }
        v.setLayout(l)
        v.setShift(if (page == Page.LETTERS) shift else 0)
        v.setLangLabel(if (lang == "fa") "FA" else "EN")
    }

    // ------------------------------------------------------------------ text tracking

    private val ic: InputConnection? get() = currentInputConnection

    private fun remember(pos: Int) {
        recentCursors[recentIdx] = pos
        recentIdx = (recentIdx + 1) % recentCursors.size
    }

    private fun commit(text: String) {
        val c = ic ?: return
        c.commitText(text, 1)
        if (cursor >= 0) { cursor += text.length; remember(cursor) }
    }

    private fun deleteBefore(n: Int) {
        if (n <= 0) return
        val c = ic ?: return
        c.deleteSurroundingText(n, 0)
        if (cursor >= 0) { cursor = maxOf(0, cursor - n); remember(cursor) }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (swipeBase >= 0) return
        if (newSelStart == newSelEnd && (newSelStart == cursor || recentCursors.contains(newSelStart))) return
        // the cursor moved for a reason we didn't cause (tap, app edit, trackpad…)
        cursor = newSelStart
        if (newSelStart != newSelEnd) {
            word.setLength(0)
            suggestions = SuggestionResult.EMPTY
            view?.setSuggestions(suggestions)
            return
        }
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
        if (!trackpadActive) {
            resync()
            updateShift()
            updateSuggestions()
        }
    }

    /** Re-read the word around the cursor from the app. */
    private fun resync() {
        val c = ic
        word.setLength(0); prevWord = null; midWord = false
        if (c == null) return
        val before = c.getTextBeforeCursor(64, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && TextTools.isWordChar(before[i - 1])) i--
        word.append(before, i, before.length)
        var j = i
        while (j > 0 && before[j - 1] == ' ') j--
        if (j > 0 && TextTools.isWordChar(before[j - 1])) {
            var k = j
            while (k > 0 && TextTools.isWordChar(before[k - 1])) k--
            prevWord = before.substring(k, j)
        }
        val after = c.getTextAfterCursor(1, 0)
        midWord = !after.isNullOrEmpty() && TextTools.isWordChar(after[0])
    }

    private fun wordLang(w: CharSequence): String = if (TextTools.isPersianText(w)) "fa" else "en"

    private fun updateSuggestions() {
        val v = view ?: return
        if (!allowSuggestions || page == Page.NUMPAD) {
            suggestions = SuggestionResult.EMPTY
            v.setSuggestions(suggestions)
            return
        }
        val q = word.toString()
        val l = if (q.isNotEmpty()) wordLang(q) else lang
        if (midWord && q.isNotEmpty()) {
            suggestions = SuggestionResult.EMPTY
            v.setSuggestions(suggestions)
            return
        }
        engine.request(q, prevWord, l) { r ->
            if (r.query == word.toString()) {
                suggestions = r
                view?.setSuggestions(r)
            }
        }
    }

    private fun finishWord(sentenceEnd: Boolean) {
        val w = word.toString()
        if (w.isNotEmpty() && settings.learn && !privateField && w.length >= 2) {
            engine.learn(wordLang(w), w, prevWord)
        }
        prevWord = if (sentenceEnd || w.isEmpty()) (if (sentenceEnd) null else prevWord) else w
        word.setLength(0)
        midWord = false
    }

    private fun updateShift() {
        if (lang != "en" || page != Page.LETTERS) { setShiftState(if (shift == 2) 2 else 0); return }
        if (shift == 2) return
        val i = info
        var caps = false
        if (settings.autocap && i != null && i.inputType != InputType.TYPE_NULL) {
            caps = (ic?.getCursorCapsMode(i.inputType) ?: 0) != 0
        }
        setShiftState(if (caps) 1 else 0)
    }

    private fun setShiftState(s: Int) {
        val layerChanged = lang == "fa" && page == Page.LETTERS && (shift != 0) != (s != 0)
        shift = s
        // Persian shift swaps in a second layer of letters (ض غ ظ ژ ذ آ…, diacritics, half-space on the space bar)
        if (layerChanged) rebuildLayout()
        else view?.setShift(if (page == Page.LETTERS) s else 0)
    }

    // ------------------------------------------------------------------ KeyboardView.Listener

    override fun onChar(text: String, fromPopup: Boolean) {
        val c = ic ?: return
        undoCorrection = null
        var out = text
        if (lang == "en" && shift != 0 && page == Page.LETTERS && out.length == 1 && out[0].isLetter()) {
            out = out.uppercase()
        }
        val ch = out.firstOrNull() ?: return
        var sentence = !TextTools.isWordChar(ch)
        c.beginBatchEdit()
        try {
            // "می " + letter → "می‌" + letter
            if (pendingPrefixZwnj && TextTools.isPersianLetter(ch)) {
                val before = c.getTextBeforeCursor(1, 0)
                if (before != null && before.toString() == " ") {
                    deleteBefore(1)
                    commit(TextTools.ZWNJ.toString())
                }
            }
            pendingPrefixZwnj = false

            val isPunct = out.length == 1 && ".,!?;:،؟؛)»".indexOf(ch) >= 0
            var handled = false
            if (lastAutoSpace && isPunct) {
                // "word |" + "." → "word. "
                val before = c.getTextBeforeCursor(1, 0)
                if (before != null && before.toString() == " ") {
                    deleteBefore(1)
                    commit(out)
                    commit(" ")
                    finishWord(".!?؟".indexOf(ch) >= 0)
                    lastAutoSpace = true
                    sentence = true
                    handled = true
                }
            }
            if (!handled) {
                lastAutoSpace = false
                commit(out)
                if (out.all { TextTools.isWordChar(it) }) {
                    word.append(out)
                } else {
                    finishWord(".!?؟\n".indexOf(ch) >= 0)
                }
            }
        } finally {
            c.endBatchEdit()
        }
        if (shift == 1) setShiftState(0)
        afterCommit(sentencePunct = sentence)
    }

    private fun afterCommit(sentencePunct: Boolean) {
        if (sentencePunct) updateShift()
        updateSuggestions()
    }

    override fun onSpace() {
        val c = ic ?: return
        val now = SystemClock.uptimeMillis()
        var doubled = false
        c.beginBatchEdit()
        try {
            val w = word.toString()
            // double-space → ". "
            if (settings.doubleSpace && w.isEmpty() && now - lastSpaceAt < 450 && page == Page.LETTERS) {
                val before = c.getTextBeforeCursor(2, 0)?.toString()
                if (before != null && before.length == 2 && before[1] == ' ' && (TextTools.isWordChar(before[0]) || before[0].isDigit())) {
                    deleteBefore(1)
                    commit(". ")
                    lastSpaceAt = 0
                    lastAutoSpace = false
                    pendingPrefixZwnj = false
                    prevWord = null
                    doubled = true
                }
            }
            if (!doubled) spaceAfterWord(c, w, now)
        } finally {
            c.endBatchEdit()
        }
        if (page == Page.SYMBOLS || page == Page.SYMBOLS2) { page = Page.LETTERS; rebuildLayout() }
        afterCommit(true)
    }

    private fun spaceAfterWord(c: InputConnection, w: String, now: Long) {
            var finalWord = w
            val sc = shortcuts[w.lowercase()]
            if (w.isNotEmpty() && sc != null) {
                deleteBefore(w.length)
                commit(sc)
                finalWord = sc
            } else if (w.isNotEmpty() && allowAutocorrect && !midWord) {
                val wl = wordLang(w)
                val enabled = if (wl == "fa") settings.autocorrectFa else settings.autocorrect
                val ac = suggestions.autoCorrect
                if (enabled && ac != null && suggestions.query == w && ac != w && w.lowercase() !in rejected) {
                    deleteBefore(w.length)
                    commit(ac)
                    undoCorrection = w to ac
                    finalWord = ac
                }
            }
            // smart half-space for suffixes: "کتاب ها" → "کتاب‌ها"
            if (settings.smartZwnj && finalWord.isNotEmpty() && TextTools.isPersianText(finalWord)) {
                val before = c.getTextBeforeCursor(finalWord.length + 2, 0)?.toString()
                if (before != null && before.length == finalWord.length + 2 && before.endsWith(finalWord) &&
                    before[0].let { TextTools.isPersianLetter(it) } && before[1] == ' ' &&
                    TextTools.shouldJoinSuffix(prevWord ?: "", finalWord)
                ) {
                    deleteBefore(finalWord.length + 1)
                    commit(TextTools.ZWNJ + finalWord)
                    // the joined word continues the previous one
                    finalWord = (prevWord ?: "") + TextTools.ZWNJ + finalWord
                    prevWord = null
                }
            }
            word.setLength(0); word.append(finalWord)
            commit(" ")
            pendingPrefixZwnj = settings.smartZwnj && TextTools.isJoiningPrefix(finalWord)
            finishWord(false)
            lastAutoSpace = false
            lastSpaceAt = now
    }

    override fun onEnter() {
        val c = ic ?: return
        finishWord(true)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
        val i = info
        val action = enterAction
        if (i != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            c.performEditorAction(action)
        } else {
            sendKeyChar('\n')
            cursor = -1
        }
        afterCommit(true)
    }

    override fun onDelete(repeat: Int) {
        val c = ic ?: return
        pendingPrefixZwnj = false
        lastAutoSpace = false
        // undo autocorrect: backspace right after a correction restores what you typed
        val undo = undoCorrection
        undoCorrection = null
        if (undo != null && repeat == 0) {
            val (orig, corr) = undo
            val before = c.getTextBeforeCursor(corr.length + 1, 0)?.toString()
            if (before == "$corr ") {
                c.beginBatchEdit()
                deleteBefore(corr.length + 1)
                commit(orig)
                c.endBatchEdit()
                rejected += orig.lowercase()
                word.setLength(0); word.append(orig)
                updateSuggestions()
                return
            }
        }
        val selected = c.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            c.commitText("", 1)
            cursor = -1
            resync(); updateShift(); updateSuggestions()
            return
        }
        if (repeat >= 14) {
            // word-at-a-time when holding delete
            val before = c.getTextBeforeCursor(64, 0)?.toString() ?: ""
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && TextTools.isWordChar(before[i - 1])) i--
            if (i == before.length && i > 0) i--
            val n = before.length - i
            if (n > 0) deleteBefore(n) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            resync()
        } else {
            val before = c.getTextBeforeCursor(12, 0)?.toString()
            if (before.isNullOrEmpty()) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                cursor = -1
                word.setLength(0)
            } else {
                val bi = BreakIterator.getCharacterInstance()
                bi.setText(before)
                val end = before.length
                val start = bi.preceding(end).let { if (it == BreakIterator.DONE) end - 1 else it }
                val n = (end - start).coerceAtLeast(1)
                deleteBefore(n)
                if (word.length >= n) word.setLength(word.length - n) else resync()
                if (word.isEmpty() && n == 1) resync()
            }
        }
        if (repeat == 0 || repeat % 4 == 0) updateShift()
        updateSuggestions()
    }

    private var swipeText = ""

    override fun onDeleteWords(count: Int, commit: Boolean) {
        val c = ic ?: return
        if (swipeBase < 0) {
            if (commit && count == 0) return
            val et = c.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            swipeBase = if (et != null) et.startOffset + et.selectionStart else cursor
            if (swipeBase < 0) { swipeBase = -1; if (commit) deleteWordsDirect(count); return }
            swipeText = c.getTextBeforeCursor(400, 0)?.toString() ?: ""
        }
        val base = swipeBase
        if (!commit) {
            val n = charsForWords(swipeText, count)
            c.setSelection(maxOf(0, base - n), base)
        } else {
            if (count > 0) c.commitText("", 1) else c.setSelection(base, base)
            swipeBase = -1
            cursor = -1
            resync(); updateShift(); updateSuggestions()
        }
    }

    private fun charsForWords(text: String, count: Int): Int {
        var i = text.length
        var words = 0
        fun inWord(ch: Char) = TextTools.isWordChar(ch) || ch.isDigit()
        while (i > 0 && words < count) {
            while (i > 0 && !inWord(text[i - 1])) i--
            while (i > 0 && inWord(text[i - 1])) i--
            words++
        }
        return text.length - i
    }

    private fun deleteWordsDirect(count: Int) {
        val c = ic ?: return
        val text = c.getTextBeforeCursor(400, 0)?.toString() ?: return
        deleteBefore(charsForWords(text, count))
        resync(); updateSuggestions()
    }

    override fun onShiftDown() {
        val now = SystemClock.uptimeMillis()
        val newState = when (shift) {
            0 -> 1
            1 -> if (now - lastShiftTap < 380) 2 else 0
            else -> 0
        }
        lastShiftTap = now
        setShiftState(newState)
    }

    override fun onSwitchPage(type: KeyType) {
        page = when (type) {
            KeyType.SYMBOLS -> Page.SYMBOLS
            KeyType.SYMBOLS2 -> Page.SYMBOLS2
            else -> if (fieldKind == FieldKind.NUMBER || fieldKind == FieldKind.PHONE) Page.NUMPAD else Page.LETTERS
        }
        rebuildLayout()
        if (page == Page.LETTERS) updateShift()
    }

    override fun onLang() {
        shift = 0
        lang = if (lang == "fa") "en" else "fa"
        if (page == Page.NUMPAD) page = Page.LETTERS
        if (fieldKind == FieldKind.TEXT) Prefs.get(this).edit().putString(Prefs.LANG, lang).apply()
        else rebuildLayout()
        updateShift()
        updateSuggestions()
    }

    override fun onLangLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursor(dx: Int, dy: Int) {
        val c = ic ?: return
        val stepsX = dx.coerceIn(-30, 30)
        val stepsY = dy.coerceIn(-10, 10)
        repeat(kotlin.math.abs(stepsX)) {
            sendDownUpKeyEvents(if (stepsX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        }
        repeat(kotlin.math.abs(stepsY)) {
            sendDownUpKeyEvents(if (stepsY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        if (c.getSelectedText(0).isNullOrEmpty()) haptics.tick()
    }

    override fun onTrackpad(active: Boolean) {
        trackpadActive = active
        if (!active) {
            cursor = -1
            word.setLength(0)
            lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
            resync(); updateShift(); updateSuggestions()
        }
    }

    override fun onSuggestion(s: Suggestion) {
        val c = ic ?: return
        val w = word.toString()
        c.beginBatchEdit()
        try {
            if (w.isNotEmpty()) {
                val before = c.getTextBeforeCursor(w.length, 0)?.toString()
                if (before == w) deleteBefore(w.length)
            }
            var text = s.text
            if (s.kind == Suggestion.Kind.TYPED) {
                rejected += w.lowercase()
                if (settings.learn && !privateField) { engine.learn(wordLang(w), w, prevWord) }
                text = w
            }
            commit(text)
            word.setLength(0); word.append(text)
            commit(" ")
            finishWord(false)
            pendingPrefixZwnj = settings.smartZwnj && TextTools.isJoiningPrefix(text)
            lastAutoSpace = true
            undoCorrection = null
        } finally {
            c.endBatchEdit()
        }
        if (s.kind == Suggestion.Kind.LAYOUT_FIX) {
            // you were typing in the other language — switch for the next word
            onLang()
            return
        }
        afterCommit(false)
    }

    override fun onSuggestionLongPress(s: Suggestion) {
        if (s.kind == Suggestion.Kind.TYPED || s.kind == Suggestion.Kind.LAYOUT_FIX) return
        engine.forget(wordLang(s.text), s.text)
        haptics.heavy()
        Toast.makeText(this, "«${s.text}» از کلمات یادگرفته‌شده حذف شد", Toast.LENGTH_SHORT).show()
    }

    override fun onToolbar(action: KeyboardView.Toolbar) {
        val p = Prefs.get(this)
        when (action) {
            KeyboardView.Toolbar.SETTINGS -> {
                val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
            KeyboardView.Toolbar.CLIPBOARD -> { view?.refreshPanel(); view?.setMode(KeyboardView.Mode.CLIPBOARD) }
            KeyboardView.Toolbar.EMOJI -> { view?.setRecentEmoji(recentEmoji()); view?.setMode(KeyboardView.Mode.EMOJI) }
            KeyboardView.Toolbar.CLOSE_PANEL -> view?.setMode(KeyboardView.Mode.KEYS)
            KeyboardView.Toolbar.HIDE -> requestHideSelf(0)
            KeyboardView.Toolbar.CLEAR_CLIPBOARD -> { clipStore.clearUnpinned(); view?.refreshPanel(); refreshClipChip() }
            KeyboardView.Toolbar.ONE_HAND -> p.edit().putInt(Prefs.ONE_HAND, if (settings.oneHand == 0) 2 else 0).apply()
            KeyboardView.Toolbar.ONE_HAND_SWITCH -> p.edit().putInt(Prefs.ONE_HAND, if (settings.oneHand == 1) 2 else 1).apply()
            KeyboardView.Toolbar.ONE_HAND_OFF -> p.edit().putInt(Prefs.ONE_HAND, 0).apply()
        }
    }

    override fun onEmoji(e: String) {
        val c = ic ?: return
        finishWord(false)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
        c.commitText(e, 1)
        if (cursor >= 0) { cursor += e.length; remember(cursor) }
        val rec = recentEmoji().toMutableList()
        rec.remove(e); rec.add(0, e)
        val arr = JSONArray()
        rec.take(32).forEach { arr.put(it) }
        Prefs.get(this).edit().putString(Prefs.RECENT_EMOJI, arr.toString()).apply()
        updateSuggestions()
    }

    private fun recentEmoji(): List<String> = try {
        val a = JSONArray(Prefs.get(this).getString(Prefs.RECENT_EMOJI, "[]") ?: "[]")
        List(a.length()) { a.getString(it) }
    } catch (_: Throwable) { emptyList() }

    override fun onPasteClip(text: String) {
        val c = ic ?: return
        finishWord(false)
        c.commitText(text, 1)
        cursor = -1
        clipShownFor = clipStore.lastAddedAt
        view?.clipChip = null
        if (view?.mode == KeyboardView.Mode.CLIPBOARD) view?.setMode(KeyboardView.Mode.KEYS)
        resync(); updateSuggestions()
    }

    override fun onKeyFeedback(key: Key?, kind: Int) {
        when (kind) {
            KeyboardView.FEEDBACK_DOWN -> {
                when (key?.type) {
                    KeyType.CHAR, KeyType.ZWNJ, null -> { haptics.key(); sound.play(SoundEngine.Kind.KEY) }
                    KeyType.DELETE -> { haptics.key(); sound.play(SoundEngine.Kind.DELETE) }
                    else -> { haptics.modifier(); sound.play(SoundEngine.Kind.MOD) }
                }
            }
            KeyboardView.FEEDBACK_TICK -> haptics.tick()
            KeyboardView.FEEDBACK_LIGHT -> { haptics.tick(); sound.play(SoundEngine.Kind.KEY) }
            KeyboardView.FEEDBACK_HEAVY -> haptics.heavy()
        }
    }

    // ------------------------------------------------------------------ clipboard

    private var clipShownFor = 0L

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!settings.clipboard) return@OnPrimaryClipChangedListener
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (Build.VERSION.SDK_INT >= 33) {
                val sensitive = clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
                if (sensitive) return@OnPrimaryClipChangedListener
            }
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
            if (text.isBlank()) return@OnPrimaryClipChangedListener
            clipStore.add(text)
            refreshClipChip()
            view?.refreshPanel()
        } catch (_: Throwable) { }
    }

    private fun refreshClipChip() {
        val v = view ?: return
        if (!settings.clipboard) { v.clipChip = null; return }
        val latest = clipStore.latest()
        val fresh = latest != null && System.currentTimeMillis() - latest.time < 90_000 && latest.time != clipShownFor
        v.clipChip = if (fresh) latest!!.text else null
        if (fresh) {
            ui.removeCallbacks(clearChip)
            ui.postDelayed(clearChip, 90_000)
        }
    }

    private val clearChip = Runnable { view?.clipChip = null }
}
