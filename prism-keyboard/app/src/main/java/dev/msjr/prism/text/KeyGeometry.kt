package dev.msjr.prism.text

import dev.msjr.prism.keyboard.FieldKind
import dev.msjr.prism.keyboard.Layouts

/** A point in key units (x: key widths from the left edge, y: row index). */
class Pt(val x: Float, val y: Float)

/** Where each letter sits on the letter layout, in key units — plain JVM code so it can be tested. */
object KeyGeometry {
    class Letter(val ch: Char, val center: Pt, val width: Float, /** typed with a long-press / flick, not a tap */ val secondary: Boolean)

    /** Every letter of the layout (tapped letters first, then the long-press ones sitting on the same key). */
    fun letters(lang: String): List<Letter> {
        val layout = Layouts.letters(lang, FieldKind.TEXT, numberRow = false, persianDigits = true)
        val out = ArrayList<Letter>()
        val secondary = ArrayList<Letter>()
        layout.rows.forEachIndexed { r, row ->
            val total = row.keys.sumOf { it.width.toDouble() }.toFloat() + row.indent * 2
            var x = (layout.units - total) / 2f + row.indent
            for (k in row.keys) {
                val c = Pt(x + k.width / 2f, r.toFloat())
                if (k.label.length == 1 && k.label[0].isLetter()) out += Letter(k.label[0], c, k.width, false)
                // letters that live on a key's long-press (ض on ص, غ on ع…) sit on that same key
                k.hint?.let { h -> if (h.length == 1 && h[0].isLetter()) secondary += Letter(h[0], c, k.width, true) }
                x += k.width
            }
        }
        for (s in secondary) if (out.none { it.ch == s.ch }) out += s
        return out
    }

    fun centers(lang: String): Map<Char, Pt> {
        val out = HashMap<Char, Pt>()
        for (l in letters(lang)) out.putIfAbsent(l.ch, l.center)
        return out
    }
}
