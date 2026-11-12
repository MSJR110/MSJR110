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
import dev.msjr.prism.data.Emoji
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
import dev.msjr.prism.text.Extras
import dev.msjr.prism.text.GlideDecoder
import dev.msjr.prism.text.Pt
import dev.msjr.prism.text.Suggestion
import dev.msjr.prism.text.SuggestionEngine
import dev.msjr.prism.text.SuggestionResult
import dev.msjr.prism.text.TextTools
import dev.msjr.prism.ui.GlassRenderer
import dev.msjr.prism.ui.KeyboardView
import dev.msjr.prism.ui.Theme
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
    /** the language each app was last typed in (package → "fa" | "en") */
    private val appLangs = HashMap<String, String>()

    // text tracking (no composing underline)
    private val word = StringBuilder()
    private var prevWord: String? = null
    private var cursor = -1
    /** Last selection the app reported (-1 = unknown). */
    private var selStart = -1
    private var selEnd = -1
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
    /** emoji search: while not null, the keys type into this query instead of the app */
    private var emojiQuery: StringBuilder? = null
    private val rejected = HashSet<String>()
    private var swipeBase = -1
    private var trackpadActive = false
    private var shortcuts: Map<String, String> = emptyMap()
    /** the word in [word] was drawn in one glide: the next letter starts a new word, one ⌫ takes it back */
    private var glided = false
    /** the answer to the sum just finished with "=" (offered in the strip) */
    private var calcAnswer: String? = null
    /** the editing panel is extending a selection with the arrows */
    private var selecting = false

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        settings = Prefs.load(this)
        lang = settings.lang
        glass = GlassRenderer(this)
        haptics = Haptics(this)
        sound = SoundEngine(this)
        engine = SuggestionEngine(this)
        engine.finglish = settings.finglish
        clipStore = ClipboardStore(this)
        loadFonts()
        applyFeedbackSettings()
        loadShortcuts()
        loadAppLangs()
        Emoji.ensureLoaded(this)
        Emoji.loadTones(Prefs.get(this))
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
        haptics.shutdown()
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

    private fun loadAppLangs() {
        appLangs.clear()
        try {
            val o = JSONObject(Prefs.get(this).getString(Prefs.APP_LANGS, "{}") ?: "{}")
            for (k in o.keys()) appLangs[k] = o.getString(k)
        } catch (_: Throwable) { }
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
        v.letterScorer = { cands -> scoreLetters(cands) }
        v.clipboardStore = clipStore
        v.setTouchModel(Prefs.get(this).getString(Prefs.TOUCH_ZONES, null))
        v.onTouchModelChanged = { model -> Prefs.get(this).edit().putString(Prefs.TOUCH_ZONES, model).apply() }
        view = v
        applyLook()
        return v
    }

    private fun applyLook() {
        val v = view ?: return
        glass.configure(Theme.resolve(this, settings.theme), settings.glassStrength, settings.refraction)
        v.applySettings(settings, fontRegular, fontBold)
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
        // email / url / password fields want latin letters; elsewhere, the language this app was last typed in
        if ((fieldKind == FieldKind.EMAIL || fieldKind == FieldKind.URL || password) && lang == "fa") lang = "en"
        else if (fieldKind == FieldKind.TEXT && !password) {
            lang = (if (settings.appLang) appLangs[editorInfo.packageName ?: ""] else null) ?: settings.lang
        }

        shift = 0
        word.setLength(0); prevWord = null
        cursor = editorInfo.initialSelStart
        selStart = editorInfo.initialSelStart; selEnd = editorInfo.initialSelEnd
        recentCursors.fill(-2)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null
        glided = false; calcAnswer = null
        setSelecting(false)
        suggestions = SuggestionResult.EMPTY
        trackpadActive = false
        emojiQuery = null
        view?.setEmojiSearch(null, emptyList())

        view?.let {
            it.resetState()
            it.setMode(KeyboardView.Mode.KEYS)
            it.setEnterAction(enterAction)
            it.setSuggestions(SuggestionResult.EMPTY)
            it.voiceAvailable = voiceIme() != null
            refreshClipChip()
        }
        rebuildLayout()
        resync()
        updateShift()
        updateSuggestions()
        view?.resetState()
        sound.warmUp()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        engine.save(true)
        sound.sleep()
        view?.resetState()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        engine.save(true)
        sound.sleep()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        when (key) {
            // written by the keyboard itself
            Prefs.TOUCH_OFFSET_X, Prefs.TOUCH_OFFSET_Y, Prefs.TOUCH_ZONES, Prefs.RECENT_EMOJI,
            Prefs.EMOJI_TONES, Prefs.APP_LANGS, Prefs.LANG -> return
            Prefs.TOUCH_RESET -> { view?.setTouchModel(null); return }
            Prefs.USER_DICT_RESET -> { engine.clearUser(); rejected.clear(); return }
            Prefs.CLIPBOARD_RESET -> {
                clipStore.clearAllItems()
                view?.refreshPanel()
                refreshClipChip()
                return
            }
        }
        val oldFont = settings.font
        settings = Prefs.load(this)
        engine.finglish = settings.finglish
        Emoji.loadTones(Prefs.get(this))
        if (settings.font != oldFont) loadFonts()
        if (key == Prefs.SHORTCUTS) loadShortcuts()
        if (key == "bg_version") glass.resetBackdrop()
        applyFeedbackSettings()
        applyLook()
        view?.refreshPanel()
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
        v.setLangLabel(if (lang == "fa") "FA" else "EN")
        v.setLayout(l)
        v.setShift(if (page == Page.LETTERS) shift else 0)
        syncGlide()
    }

    private fun syncGlide() {
        view?.glideEnabled = settings.glide && allowSuggestions && page == Page.LETTERS && emojiQuery == null &&
            !(lang == "fa" && shift != 0)
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
        selStart = newSelStart; selEnd = newSelEnd
        if (swipeBase >= 0) return
        if (newSelStart == newSelEnd && (newSelStart == cursor || recentCursors.contains(newSelStart))) return
        // the cursor moved for a reason we didn't cause (tap, app edit, trackpad…)
        cursor = newSelStart
        glided = false; calcAnswer = null
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

    private fun show(r: SuggestionResult) {
        suggestions = r
        view?.setSuggestions(r)
    }

    private fun updateSuggestions() {
        if (view == null) return
        if (!allowSuggestions || page == Page.NUMPAD) { show(SuggestionResult.EMPTY); return }
        val q = word.toString()
        val l = if (q.isNotEmpty()) wordLang(q) else lang
        if (midWord && q.isNotEmpty()) { show(SuggestionResult.EMPTY); return }
        engine.request(q, prevWord, l, capitalize = q.isEmpty() && shift == 1 && lang == "en") { r ->
            if (r.query == word.toString()) show(withExtras(r))
        }
    }

    /** Things the keyboard simply knows, put in the strip: a sum's answer, the date after «امروز»… */
    private fun withExtras(r: SuggestionResult): SuggestionResult {
        if (!settings.smartExtras) return emojiToned(r)
        val calc = calcAnswer
        if (r.query.isEmpty() && calc != null) {
            val slots = r.slots.copyOf()
            // the answer takes the middle; what was there moves aside
            slots[2] = slots[2] ?: slots[1]
            slots[1] = Suggestion(calc, Suggestion.Kind.EXTRA, 0f)
            return emojiToned(SuggestionResult(r.query, slots, r.autoCorrect, r.typedValid))
        }
        val extra = if (r.query.isNotEmpty()) Extras.forWord(r.query, lang == "fa" && settings.persianDigits) else null
        if (extra != null) {
            val slots = r.slots.copyOf()
            slots[2] = Suggestion(extra, Suggestion.Kind.EXTRA, 0f)
            return emojiToned(SuggestionResult(r.query, slots, r.autoCorrect, r.typedValid))
        }
        return emojiToned(r)
    }

    /** An emoji offered in the strip wears the skin tone you chose. */
    private fun emojiToned(r: SuggestionResult): SuggestionResult {
        val i = r.slots.indexOfFirst { it?.kind == Suggestion.Kind.EMOJI }
        if (i < 0) return r
        val toned = toned(r.slots[i]!!.text)
        if (toned == r.slots[i]!!.text) return r
        val slots = r.slots.copyOf()
        slots[i] = Suggestion(toned, Suggestion.Kind.EMOJI, 0f)
        return SuggestionResult(r.query, slots, r.autoCorrect, r.typedValid)
    }

    private fun toned(emoji: String): String = Emoji.item(emoji)?.let { Emoji.shown(it) } ?: emoji

    private fun finishWord(sentenceEnd: Boolean) {
        val w = word.toString()
        if (w.isNotEmpty() && settings.learn && !privateField && w.length >= 2) {
            engine.learn(wordLang(w), w, prevWord)
        }
        prevWord = if (sentenceEnd || w.isEmpty()) (if (sentenceEnd) null else prevWord) else w
        word.setLength(0)
        midWord = false
        glided = false
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
        if (searchKey(text)) return
        val c = ic ?: return
        undoCorrection = null
        calcAnswer = null
        var out = text
        if (lang == "en" && shift != 0 && page == Page.LETTERS && out.length == 1 && out[0].isLetter()) {
            out = out.uppercase()
        }
        val ch = out.firstOrNull() ?: return
        var sentence = !TextTools.isWordChar(ch)
        c.beginBatchEdit()
        try {
            // a letter right after a glided word starts the next word
            if (glided && TextTools.isWordChar(ch) && word.isNotEmpty()) {
                commit(" ")
                finishWord(false)
            }
            glided = false
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
            // "12*3=" → the answer waits in the strip
            if (out == "=" && settings.smartExtras) {
                calcAnswer = c.getTextBeforeCursor(48, 0)?.toString()?.let { Extras.calc(it) }
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
        if (searchKey(" ")) return
        val c = ic ?: return
        val now = SystemClock.uptimeMillis()
        var doubled = false
        calcAnswer = null
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
        } else if (w.isNotEmpty() && allowAutocorrect && !midWord && !glided) {
            val wl = wordLang(w)
            val ac = suggestions.autoCorrect
            // putting back a missing half-space is always on with smart half-space
            val zwnjOnly = ac != null && ac.replace(TextTools.ZWNJ.toString(), "") == w.replace(TextTools.ZWNJ.toString(), "")
            val enabled = if (wl == "fa") settings.autocorrectFa || (settings.smartZwnj && zwnjOnly) else settings.autocorrect
            if (enabled && ac != null && suggestions.query == w && ac != w && w.lowercase() !in rejected) {
                deleteBefore(w.length)
                commit(ac)
                undoCorrection = w to ac
                finalWord = ac
            } else if (suggestions.query != w && w.lowercase() !in rejected &&
                (if (wl == "fa") settings.autocorrectFa || settings.smartZwnj else settings.autocorrect)
            ) {
                // space came faster than the suggestions: finish the check and fix the word right after
                val prev = prevWord
                engine.requestOnce(w, prev, wl) { r -> applyLateCorrection(w, r) }
            }
        }
        // smart half-space for suffixes: "کتاب ها" → "کتاب‌ها"
        // (the cheap word check comes first: reading text back from the app is a blocking call)
        if (settings.smartZwnj && finalWord.isNotEmpty() && TextTools.shouldJoinSuffix(prevWord ?: "", finalWord)) {
            val before = c.getTextBeforeCursor(finalWord.length + 2, 0)?.toString()
            if (before != null && before.length == finalWord.length + 2 && before.endsWith(finalWord) &&
                before[0].let { TextTools.isPersianLetter(it) } && before[1] == ' '
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

    /**
     * For the letters a tap could have meant: how well each continues the word being typed
     * ("سلا" + م → "سلام" beats "سلا" + ن), as log weights. Null = no opinion, type what was hit.
     */
    private fun scoreLetters(cands: List<String>): FloatArray? {
        if (emojiQuery != null) return null
        if (!allowSuggestions || page != Page.LETTERS || midWord) return null
        // a letter after a glided word starts a new word
        return engine.letterWeights(if (glided) "" else word.toString(), if (glided) word.toString() else prevWord, cands)
    }

    private fun applyLateCorrection(w: String, r: SuggestionResult) {
        val c = ic ?: return
        val ac = r.autoCorrect ?: return
        if (ac == w || w.lowercase() in rejected || word.isNotEmpty() || trackpadActive) return
        val zwnjOnly = ac.replace(TextTools.ZWNJ.toString(), "") == w.replace(TextTools.ZWNJ.toString(), "")
        val enabled = if (wordLang(w) == "fa") settings.autocorrectFa || (settings.smartZwnj && zwnjOnly) else settings.autocorrect
        if (!enabled) return
        // only while the cursor is still right after "word " (the person hasn't moved on)
        if (c.getTextBeforeCursor(w.length + 1, 0)?.toString() != "$w ") return
        c.beginBatchEdit()
        try {
            deleteBefore(w.length + 1)
            commit("$ac ")
        } finally {
            c.endBatchEdit()
        }
        undoCorrection = w to ac
        if (prevWord == w) prevWord = ac
    }

    // ------------------------------------------------------------------ glide typing

    override fun onGlide(path: FloatArray, centers: Map<Char, Pt>, aspect: Float, fallback: String?) {
        val c = ic
        if (c == null || !allowSuggestions) { fallback?.let { onChar(it, false) }; return }
        // decoded right here (a few milliseconds) so the word lands before the next touch is handled
        val prev = if (word.isNotEmpty()) word.toString() else prevWord
        val cands = try { engine.glideNow(path, centers, aspect, prev, lang) } catch (_: Throwable) { emptyList() }
        if (cands.isEmpty()) { fallback?.let { onChar(it, false) }; return }
        undoCorrection = null; calcAnswer = null
        c.beginBatchEdit()
        try {
            if (word.isNotEmpty()) {
                // gliding right after a word (tapped or glided): that word is done
                commit(" ")
                finishWord(false)
            } else {
                // after "word." or "word," a new word wants a space; right after a space or an opening bracket it doesn't
                val before = c.getTextBeforeCursor(1, 0)
                if (!before.isNullOrEmpty() && (TextTools.isWordChar(before[0]) || before[0].isDigit() || ".,!?;:،؟؛)»\"".indexOf(before[0]) >= 0)) commit(" ")
            }
            if (pendingPrefixZwnj && lang == "fa") {
                val before = c.getTextBeforeCursor(1, 0)
                if (before != null && before.toString() == " ") { deleteBefore(1); commit(TextTools.ZWNJ.toString()) }
            }
            pendingPrefixZwnj = false
            lastAutoSpace = false
            val text = glideCase(cands[0].word)
            commit(text)
            word.setLength(0); word.append(text)
            glided = true
        } finally {
            c.endBatchEdit()
        }
        if (shift == 1) setShiftState(0)
        // the drawn word in the middle, the next-best readings beside it
        val slots = arrayOfNulls<Suggestion>(3)
        slots[1] = Suggestion(word.toString(), Suggestion.Kind.WORD, 1f)
        cands.getOrNull(1)?.let { slots[0] = Suggestion(glideCase(it.word), Suggestion.Kind.WORD, it.score) }
        cands.getOrNull(2)?.let { slots[2] = Suggestion(glideCase(it.word), Suggestion.Kind.WORD, it.score) }
        show(SuggestionResult(word.toString(), slots, null, true))
        haptics.tick()
    }

    private fun glideCase(w: String): String = when {
        lang != "en" -> w
        w == "i" -> "I"
        shift == 2 -> w.uppercase()
        shift == 1 -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    override fun onEnter() {
        if (emojiQuery != null) { stopEmojiSearch(backToPanel = true); return }
        val c = ic ?: return
        finishWord(true)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null; calcAnswer = null
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
        if (searchKey(null)) return
        val c = ic ?: return
        pendingPrefixZwnj = false
        lastAutoSpace = false
        calcAnswer = null
        // one ⌫ takes back a whole glided word
        if (glided && repeat == 0 && word.isNotEmpty()) {
            val w = word.toString()
            if (c.getTextBeforeCursor(w.length, 0)?.toString() == w) {
                deleteBefore(w.length)
                word.setLength(0)
                glided = false
                resync(); updateShift(); updateSuggestions()
                return
            }
        }
        glided = false
        // undo autocorrect: backspace right after a correction restores what you typed
        val undo = undoCorrection
        undoCorrection = null
        if (undo != null && repeat == 0) {
            val (orig, corr) = undo
            val before = c.getTextBeforeCursor(corr.length + 1, 0)?.toString()
            if (before == "$corr ") {
                c.beginBatchEdit()
                try {
                    deleteBefore(corr.length + 1)
                    commit(orig)
                } finally {
                    c.endBatchEdit()
                }
                rejected += orig.lowercase()
                // you meant it: remember the word so it's never "corrected" again
                if (!privateField && settings.learn) engine.learn(wordLang(orig), orig, null)
                word.setLength(0); word.append(orig)
                updateSuggestions()
                return
            }
        }
        // asking the app for the selection is a blocking call: only do it when there may be one
        if (selStart < 0 || selStart != selEnd) {
            val selected = c.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                c.commitText("", 1)
                cursor = -1
                setSelecting(false)
                resync(); updateShift(); updateSuggestions()
                return
            }
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
            // long enough for the longest emoji (family / couple sequences with skin tones)
            val before = c.getTextBeforeCursor(48, 0)?.toString()
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
        emojiQuery?.let { if (commit && count > 0) { it.setLength(0); showEmojiSearch() }; return }
        val c = ic ?: return
        glided = false
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
        syncGlide()
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
        if (fieldKind == FieldKind.TEXT && !privateField) {
            // the general choice, and this app's own
            val e = Prefs.get(this).edit().putString(Prefs.LANG, lang)
            info?.packageName?.let { pkg ->
                appLangs[pkg] = lang
                val o = JSONObject()
                for ((k, v) in appLangs) o.put(k, v)
                e.putString(Prefs.APP_LANGS, o.toString())
            }
            e.apply()
            settings = settings.copy(lang = lang)
        }
        rebuildLayout()
        updateShift()
        updateSuggestions()
    }

    override fun onLangLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursor(dx: Int, dy: Int) {
        if (ic == null) return
        val stepsX = dx.coerceIn(-30, 30)
        val stepsY = dy.coerceIn(-10, 10)
        repeat(kotlin.math.abs(stepsX)) {
            sendDownUpKeyEvents(if (stepsX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        }
        repeat(kotlin.math.abs(stepsY)) {
            sendDownUpKeyEvents(if (stepsY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        // a light tick per step while moving the cursor (not while extending a selection)
        if (selStart == selEnd) haptics.tick()
    }

    override fun onTrackpad(active: Boolean) {
        trackpadActive = active
        if (!active) {
            cursor = -1
            word.setLength(0)
            lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null; glided = false
            resync(); updateShift(); updateSuggestions()
        }
    }

    override fun onSuggestion(s: Suggestion) {
        if (s.kind == Suggestion.Kind.EMOJI) { onEmoji(s.text); return }
        val c = ic ?: return
        val w = word.toString()
        if (s.kind == Suggestion.Kind.EXTRA && w.isEmpty()) {
            // the answer to a sum goes right after the "="
            calcAnswer = null
            commit(s.text)
            cursor = -1
            resync(); updateSuggestions()
            return
        }
        calcAnswer = null
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
            word.setLength(0)
            // a date or a time is not a word to learn
            if (s.kind != Suggestion.Kind.EXTRA) word.append(text)
            commit(" ")
            finishWord(false)
            pendingPrefixZwnj = settings.smartZwnj && TextTools.isJoiningPrefix(text)
            lastAutoSpace = true
            undoCorrection = null
        } finally {
            c.endBatchEdit()
        }
        // a sentence-start prediction was shown capitalized: the capital is used up
        if (shift == 1) setShiftState(0)
        if (s.kind == Suggestion.Kind.LAYOUT_FIX) {
            // you were typing in the other language — switch for the next word
            onLang()
            return
        }
        afterCommit(false)
    }

    override fun onSuggestionLongPress(s: Suggestion) {
        if (s.kind != Suggestion.Kind.WORD && s.kind != Suggestion.Kind.PREDICTION && s.kind != Suggestion.Kind.CORRECTION) return
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
            KeyboardView.Toolbar.EDIT -> view?.setMode(KeyboardView.Mode.EDIT)
            KeyboardView.Toolbar.VOICE -> startVoice()
            KeyboardView.Toolbar.EMOJI -> { stopEmojiSearch(backToPanel = true) }
            KeyboardView.Toolbar.EMOJI_SEARCH -> startEmojiSearch()
            KeyboardView.Toolbar.CLOSE_SEARCH -> stopEmojiSearch(backToPanel = true)
            KeyboardView.Toolbar.CLOSE_PANEL -> { setSelecting(false); view?.setMode(KeyboardView.Mode.KEYS) }
            KeyboardView.Toolbar.HIDE -> requestHideSelf(0)
            KeyboardView.Toolbar.CLEAR_CLIPBOARD -> { clipStore.clearUnpinned(); view?.refreshPanel(); refreshClipChip() }
            KeyboardView.Toolbar.ONE_HAND -> p.edit().putInt(Prefs.ONE_HAND, if (settings.oneHand == 0) 2 else 0).apply()
            KeyboardView.Toolbar.ONE_HAND_SWITCH -> p.edit().putInt(Prefs.ONE_HAND, if (settings.oneHand == 1) 2 else 1).apply()
            KeyboardView.Toolbar.ONE_HAND_OFF -> p.edit().putInt(Prefs.ONE_HAND, 0).apply()
            KeyboardView.Toolbar.TOGGLE -> {}
        }
    }

    override fun onEmoji(e: String) {
        val c = ic ?: return
        finishWord(false)
        lastAutoSpace = false; pendingPrefixZwnj = false; undoCorrection = null; calcAnswer = null
        c.commitText(e, 1)
        if (cursor >= 0) { cursor += e.length; remember(cursor) }
        Emoji.addRecent(Prefs.get(this), e)
        updateSuggestions()
    }

    // ------------------------------------------------------------------ editing panel

    private fun setSelecting(on: Boolean) {
        selecting = on
        view?.selecting = on
    }

    private fun sendKey(code: Int, meta: Int = 0) {
        val c = ic ?: return
        val now = SystemClock.uptimeMillis()
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    override fun onEdit(action: KeyboardView.EditAction) {
        val c = ic ?: return
        val shiftMeta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        glided = false; undoCorrection = null; calcAnswer = null
        when (action) {
            KeyboardView.EditAction.LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT, shiftMeta)
            KeyboardView.EditAction.RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, shiftMeta)
            KeyboardView.EditAction.UP -> sendKey(KeyEvent.KEYCODE_DPAD_UP, shiftMeta)
            KeyboardView.EditAction.DOWN -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN, shiftMeta)
            KeyboardView.EditAction.HOME -> sendKey(KeyEvent.KEYCODE_MOVE_HOME, shiftMeta)
            KeyboardView.EditAction.END -> sendKey(KeyEvent.KEYCODE_MOVE_END, shiftMeta)
            KeyboardView.EditAction.SELECT -> {
                // switching it off also lets go of what was selected
                if (selecting && selEnd >= 0 && selStart != selEnd) c.setSelection(selEnd, selEnd)
                setSelecting(!selecting)
            }
            KeyboardView.EditAction.SELECT_ALL -> c.performContextMenuAction(android.R.id.selectAll)
            KeyboardView.EditAction.COPY -> { c.performContextMenuAction(android.R.id.copy); setSelecting(false) }
            KeyboardView.EditAction.CUT -> { c.performContextMenuAction(android.R.id.cut); setSelecting(false) }
            KeyboardView.EditAction.PASTE -> { c.performContextMenuAction(android.R.id.paste); setSelecting(false) }
            KeyboardView.EditAction.UNDO -> sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            KeyboardView.EditAction.REDO -> sendKey(KeyEvent.KEYCODE_Z,
                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        cursor = -1
    }

    // ------------------------------------------------------------------ voice typing

    private fun voiceIme(): String? = try {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.enabledInputMethodList.firstOrNull { imi ->
            imi.packageName != packageName && (0 until imi.subtypeCount).any { imi.getSubtypeAt(it).mode == "voice" }
        }?.id
    } catch (_: Throwable) { null }

    private fun startVoice() {
        val id = voiceIme() ?: return
        try {
            if (Build.VERSION.SDK_INT >= 28) switchInputMethod(id)
            else @Suppress("DEPRECATION") (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .setInputMethod(window?.window?.attributes?.token, id)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ emoji search

    private fun startEmojiSearch() {
        emojiQuery = StringBuilder()
        if (page != Page.LETTERS) { page = Page.LETTERS; rebuildLayout() }
        view?.setMode(KeyboardView.Mode.KEYS)
        syncGlide()
        showEmojiSearch()
    }

    private fun showEmojiSearch() {
        val q = emojiQuery ?: return
        val results = if (q.isBlank()) Emoji.recents(Prefs.get(this)).ifEmpty { STARTER_EMOJI }
        else engine.searchEmoji(q.toString()).map { toned(it) }
        view?.setEmojiSearch(q.toString(), results, if (lang == "fa") "جستجوی ایموجی" else "Search emoji")
    }

    private fun stopEmojiSearch(backToPanel: Boolean) {
        emojiQuery = null
        view?.setEmojiSearch(null, emptyList())
        syncGlide()
        if (backToPanel) {
            view?.refreshPanel()
            view?.setMode(KeyboardView.Mode.EMOJI)
        } else updateSuggestions()
    }

    /** While searching emoji, a key edits the query ([text] null = delete). True if it was used. */
    private fun searchKey(text: String?): Boolean {
        val q = emojiQuery ?: return false
        if (text == null) {
            if (q.isNotEmpty()) q.setLength(q.length - Character.charCount(q.codePointBefore(q.length)))
        } else if (q.length < 40) {
            if (q.isNotEmpty() || text != " ") q.append(text)
            if (shift == 1) setShiftState(0)
        }
        showEmojiSearch()
        return true
    }

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

    companion object {
        /** shown in emoji search before anything is typed, if nothing has been used yet */
        private val STARTER_EMOJI = "😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 🌹 👌 💯 ✨".split(' ')
    }
}
