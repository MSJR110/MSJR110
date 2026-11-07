package dev.msjr.prism.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Clipboard history with pinned items. Stored only on the device. */
class ClipboardStore(private val ctx: Context) {

    class Item(val text: String, var pinned: Boolean, val time: Long)

    private val items = ArrayList<Item>()
    private val file get() = File(ctx.filesDir, "clipboard.json")
    var lastAddedAt = 0L
        private set

    init { load() }

    fun all(): List<Item> = items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time })

    fun latest(): Item? = items.maxByOrNull { it.time }

    fun add(text: String) {
        val t = text.trim('\u0000')
        if (t.isBlank() || t.length > 20000) return
        val existing = items.firstOrNull { it.text == t }
        val pinned = existing?.pinned ?: false
        if (existing != null) items.remove(existing)
        val now = System.currentTimeMillis()
        items += Item(t, pinned, now)
        lastAddedAt = now
        // keep 40 unpinned
        val unpinned = items.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > 40) items.removeAll(unpinned.drop(40).toSet())
        save()
    }

    fun togglePin(item: Item) { item.pinned = !item.pinned; save() }

    fun remove(item: Item) { items.remove(item); save() }

    fun clearUnpinned() { items.removeAll { !it.pinned }; save() }

    private fun load() {
        try {
            val f = file
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += Item(o.getString("t"), o.optBoolean("p"), o.optLong("ts"))
            }
        } catch (_: Throwable) { }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (it in items) arr.put(JSONObject().put("t", it.text).put("p", it.pinned).put("ts", it.time))
            file.writeText(arr.toString())
        } catch (_: Throwable) { }
    }

    companion object {
        fun clearAll(ctx: Context) { File(ctx.filesDir, "clipboard.json").delete() }
    }
}
