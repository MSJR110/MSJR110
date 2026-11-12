package dev.msjr.prism.data

import android.content.Context
import android.content.SharedPreferences

/**
 * All user-facing options live here. Read once into an immutable [Settings] snapshot
 * so the hot typing path never touches SharedPreferences.
 */
object Prefs {
    const val FILE = "prism_settings"

    // keys
    const val THEME = "theme"
    const val BG_MODE = "bg_mode"              // "aurora" | "image"
    const val GLASS_STRENGTH = "glass_strength" // 0..100
    const val REFRACTION = "refraction"
    const val HEIGHT = "height"                // 80..130 (%)
    const val NUMBER_ROW = "number_row"
    const val KEY_PREVIEW = "key_preview"
    const val LONG_PRESS_MS = "long_press_ms"
    const val HAPTIC = "haptic"                // 0..100 (0 = off)
    const val SOUND = "sound"                  // "off" | "iphone" | "click" | "soft" | "glass" | "mech" | "system"
    const val SOUND_VOLUME = "sound_volume"    // 0..100
    const val SOUND_RESPECT_SILENT = "sound_respect_silent"
    const val SUGGESTIONS = "suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val AUTOCORRECT_FA = "autocorrect_fa"
    const val AUTOCAP = "autocap"
    const val DOUBLE_SPACE = "double_space"
    const val SMART_ZWNJ = "smart_zwnj"
    const val PERSIAN_DIGITS = "persian_digits"
    const val LEARN = "learn"
    const val CLIPBOARD = "clipboard"
    const val TRACKPAD_SPEED = "trackpad_speed" // 50..200
    const val ADAPTIVE_TOUCH = "adaptive_touch"
    const val ONE_HAND = "one_hand"            // 0 none, 1 left, 2 right
    const val LANG = "lang"                    // "fa" | "en"
    const val FONT = "font"                    // "vazir" | "system"
    const val SHORTCUTS = "shortcuts"          // json object
    const val RECENT_EMOJI = "recent_emoji"
    const val TOUCH_OFFSET_X = "touch_off_x"
    const val TOUCH_OFFSET_Y = "touch_off_y"
    /** per-zone learned thumb offsets (see KeyboardView.setTouchModel) */
    const val TOUCH_ZONES = "touch_zones"
    /** signal from the settings screen: forget the learned thumb offsets */
    const val TOUCH_RESET = "touch_reset"
    const val SWIPE_DELETE = "swipe_delete"
    const val GLOW = "glow"
    const val GLIDE = "glide"
    const val FINGLISH = "finglish"
    const val LIVE_LIGHT = "live_light"
    const val SMART_EXTRAS = "smart_extras"     // calculator, today’s date
    const val APP_LANG = "app_lang"             // remember the language per app
    const val APP_LANGS = "app_langs"           // json: package -> "fa" | "en"
    const val EMOJI_TONE = "emoji_tone"         // 0 = yellow, 1..5 = skin tones
    const val EMOJI_TONES = "emoji_tones"       // json: emoji -> its own tone
    /** Signals from the settings screen to the running keyboard (value = time of the request). */
    const val USER_DICT_RESET = "user_dict_reset"
    const val CLIPBOARD_RESET = "clipboard_reset"

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bumped when a default changes in a way existing installs should pick up once. */
    private const val MIGRATION = "migration"

    private fun migrate(p: SharedPreferences) {
        val v = p.getInt(MIGRATION, 0)
        if (v >= 2) return
        val e = p.edit()
        if (v < 1) {
            // v1: the new iPhone-style sound replaces the old default "click"
            val s = p.getString(SOUND, null)
            if (s == null || s == "click") e.putString(SOUND, "iphone")
        }
        // v2 (Prism 2): the glass is built around real refraction now, so it starts switched on
        e.putBoolean(REFRACTION, true)
        e.putInt(MIGRATION, 2).apply()
    }

    fun load(ctx: Context): Settings {
        val p = get(ctx)
        migrate(p)
        return Settings(
            theme = p.getString(THEME, "auto") ?: "auto",
            bgMode = p.getString(BG_MODE, "aurora") ?: "aurora",
            glassStrength = p.getInt(GLASS_STRENGTH, 70),
            refraction = p.getBoolean(REFRACTION, true),
            heightPct = p.getInt(HEIGHT, 100),
            numberRow = p.getBoolean(NUMBER_ROW, false),
            keyPreview = p.getBoolean(KEY_PREVIEW, true),
            longPressMs = p.getInt(LONG_PRESS_MS, 400),
            haptic = p.getInt(HAPTIC, 55),
            sound = p.getString(SOUND, "iphone") ?: "iphone",
            soundVolume = p.getInt(SOUND_VOLUME, 55),
            soundRespectSilent = p.getBoolean(SOUND_RESPECT_SILENT, true),
            suggestions = p.getBoolean(SUGGESTIONS, true),
            autocorrect = p.getBoolean(AUTOCORRECT, true),
            autocorrectFa = p.getBoolean(AUTOCORRECT_FA, true),
            autocap = p.getBoolean(AUTOCAP, true),
            doubleSpace = p.getBoolean(DOUBLE_SPACE, true),
            smartZwnj = p.getBoolean(SMART_ZWNJ, true),
            persianDigits = p.getBoolean(PERSIAN_DIGITS, true),
            learn = p.getBoolean(LEARN, true),
            clipboard = p.getBoolean(CLIPBOARD, true),
            trackpadSpeed = p.getInt(TRACKPAD_SPEED, 100),
            adaptiveTouch = p.getBoolean(ADAPTIVE_TOUCH, true),
            oneHand = p.getInt(ONE_HAND, 0),
            lang = p.getString(LANG, "fa") ?: "fa",
            font = p.getString(FONT, "vazir") ?: "vazir",
            swipeDelete = p.getBoolean(SWIPE_DELETE, true),
            glow = p.getBoolean(GLOW, true),
            glide = p.getBoolean(GLIDE, true),
            finglish = p.getBoolean(FINGLISH, true),
            liveLight = p.getBoolean(LIVE_LIGHT, true),
            smartExtras = p.getBoolean(SMART_EXTRAS, true),
            appLang = p.getBoolean(APP_LANG, true),
            emojiTone = p.getInt(EMOJI_TONE, 0),
        )
    }
}

data class Settings(
    val theme: String,
    val bgMode: String,
    val glassStrength: Int,
    val refraction: Boolean,
    val heightPct: Int,
    val numberRow: Boolean,
    val keyPreview: Boolean,
    val longPressMs: Int,
    val haptic: Int,
    val sound: String,
    val soundVolume: Int,
    val soundRespectSilent: Boolean,
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val autocorrectFa: Boolean,
    val autocap: Boolean,
    val doubleSpace: Boolean,
    val smartZwnj: Boolean,
    val persianDigits: Boolean,
    val learn: Boolean,
    val clipboard: Boolean,
    val trackpadSpeed: Int,
    val adaptiveTouch: Boolean,
    val oneHand: Int,
    val lang: String,
    val font: String,
    val swipeDelete: Boolean,
    val glow: Boolean,
    val glide: Boolean,
    val finglish: Boolean,
    val liveLight: Boolean,
    val smartExtras: Boolean,
    val appLang: Boolean,
    val emojiTone: Int,
)
