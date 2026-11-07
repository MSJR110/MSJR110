package dev.msjr.prism.keyboard

import android.graphics.RectF

enum class KeyType {
    CHAR, SHIFT, DELETE, SPACE, ENTER, SYMBOLS, SYMBOLS2, LETTERS, LANG, EMOJI, ZWNJ
}

/**
 * One key. [label] is what's drawn, [output] is what's typed (may differ, e.g. ZWNJ).
 * [hint] is the flick-up / quick long-press character shown small in the corner.
 */
class Key(
    val type: KeyType,
    val label: String,
    val output: String = label,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String? = null,
) {
    /** Visual rect (with gaps) in view coordinates. */
    val rect = RectF()
    /** Touch rect (gap-free, covers its share of the row) in view coordinates. */
    val hit = RectF()

    // animation state
    var press = 0f
    var pressVel = 0f
    var pressTarget = 0f

    val isModifier: Boolean
        get() = type != KeyType.CHAR && type != KeyType.SPACE

    fun copyWith(label: String = this.label, output: String = this.output, width: Float = this.width,
                 popup: List<String> = this.popup, hint: String? = this.hint, type: KeyType = this.type) =
        Key(type, label, output, width, popup, hint)
}

class Row(val keys: List<Key>, val indent: Float = 0f, val heightWeight: Float = 1f)

class KeyboardLayout(
    val id: String,
    val rows: List<Row>,
    val rtl: Boolean,
    /** total width in key units */
    val units: Float,
) {
    val allKeys: List<Key> = rows.flatMap { it.keys }
}
