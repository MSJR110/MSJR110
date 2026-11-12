package dev.msjr.prism.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Every emoji the phone can draw, in Unicode's own order, with skin tones.
 * `assets/emoji/all.txt` (built by tools/gen-emoji.js from Unicode's emoji-test.txt):
 * "@Group" lines, then one emoji per line; those that come in skin tones list the five after a tab.
 */
object Emoji {
    class Item(val base: String, val tones: Array<String>?)
    class Group(val icon: String, val nameFa: String, val nameEn: String, val items: List<Item>)

    @Volatile var groups: List<Group> = emptyList()
        private set
    @Volatile private var byBase: Map<String, Item> = emptyMap()
    /** any form (yellow or toned) → its item, so "recents" can be re-toned too */
    @Volatile private var byAny: Map<String, Item> = emptyMap()
    @Volatile private var loading = false

    private val meta = mapOf(
        "Smileys & Emotion" to Triple("😀", "صورتک و احساس", "Smileys"),
        "People & Body" to Triple("👋", "آدم‌ها و دست‌ها", "People"),
        "Animals & Nature" to Triple("🐻", "حیوانات و طبیعت", "Nature"),
        "Food & Drink" to Triple("🍔", "خوردنی و نوشیدنی", "Food"),
        "Travel & Places" to Triple("🚗", "سفر و مکان‌ها", "Travel"),
        "Activities" to Triple("⚽", "ورزش و سرگرمی", "Activities"),
        "Objects" to Triple("💡", "اشیا", "Objects"),
        "Symbols" to Triple("💟", "نمادها", "Symbols"),
        "Flags" to Triple("🏁", "پرچم‌ها", "Flags"),
    )

    /** Load once, off the main thread (checking ~3500 glyphs takes a moment). */
    fun ensureLoaded(ctx: Context, onReady: (() -> Unit)? = null) {
        if (groups.isNotEmpty() || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread({
            try {
                val probe = Paint()
                val out = ArrayList<Group>()
                val all = HashMap<String, Item>()
                val any = HashMap<String, Item>()
                var name: String? = null
                var items = ArrayList<Item>()
                fun flush() {
                    val n = name ?: return
                    val m = meta[n] ?: Triple(items.firstOrNull()?.base ?: "•", n, n)
                    if (items.isNotEmpty()) out += Group(m.first, m.second, m.third, items)
                }
                BufferedReader(InputStreamReader(app.assets.open("emoji/all.txt"), Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isEmpty()) continue
                        if (line[0] == '@') { flush(); name = line.substring(1); items = ArrayList(); continue }
                        val tab = line.indexOf('\t')
                        val base = if (tab < 0) line else line.substring(0, tab)
                        if (!probe.hasGlyph(base)) continue
                        var tones: Array<String>? = null
                        if (tab > 0) {
                            val t = line.substring(tab + 1).split(' ')
                            if (t.size == 5 && probe.hasGlyph(t[2])) tones = t.toTypedArray()
                        }
                        val item = Item(base, tones)
                        items += item
                        all[base] = item
                        any[base] = item
                        tones?.forEach { any[it] = item }
                    }
                }
                flush()
                byBase = all; byAny = any
                groups = out
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
            onReady?.let { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }
        }, "prism-emoji").start()
    }

    fun item(emoji: String): Item? = byAny[emoji]

    // ------------------------------------------------------------------ skin tones

    private var toneDefault = 0
    private val tonePer = HashMap<String, Int>()
    private var tonesLoaded = false

    fun loadTones(p: SharedPreferences) {
        toneDefault = p.getInt(Prefs.EMOJI_TONE, 0).coerceIn(0, 5)
        if (tonesLoaded) return
        tonesLoaded = true
        tonePer.clear()
        try {
            val o = JSONObject(p.getString(Prefs.EMOJI_TONES, "{}") ?: "{}")
            for (k in o.keys()) tonePer[k] = o.getInt(k).coerceIn(0, 5)
        } catch (_: Throwable) { }
    }

    /** Which tone this emoji is shown in: its own remembered one, else the general choice (0 = yellow). */
    fun toneOf(item: Item): Int = if (item.tones == null) 0 else tonePer[item.base] ?: toneDefault

    /** The emoji as it should be shown and typed. */
    fun shown(item: Item): String = variant(item, toneOf(item))

    fun variant(item: Item, tone: Int): String = if (tone <= 0 || item.tones == null) item.base else item.tones[tone - 1]

    fun rememberTone(p: SharedPreferences, item: Item, tone: Int) {
        tonePer[item.base] = tone
        val o = JSONObject()
        for ((k, v) in tonePer) o.put(k, v)
        p.edit().putString(Prefs.EMOJI_TONES, o.toString()).apply()
    }

    // ------------------------------------------------------------------ recents

    fun recents(p: SharedPreferences): List<String> = try {
        val a = JSONArray(p.getString(Prefs.RECENT_EMOJI, "[]") ?: "[]")
        List(a.length()) { a.getString(it) }
    } catch (_: Throwable) { emptyList() }

    fun addRecent(p: SharedPreferences, emoji: String) {
        val rec = recents(p).toMutableList()
        // one slot per emoji whatever its tone
        val it = item(emoji)
        rec.removeAll { r -> r == emoji || (it != null && item(r) === it) }
        rec.add(0, emoji)
        val arr = JSONArray()
        rec.take(40).forEach { arr.put(it) }
        p.edit().putString(Prefs.RECENT_EMOJI, arr.toString()).apply()
    }
}
