package dev.msjr.prism.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** What's painted behind the glass keys (the keys refract it, so it's what gives a theme its character). */
enum class Backdrop {
    /** soft drifting light blobs */
    AURORA,
    /** deep space: glowing gas clouds, a faint milky band and stars */
    NEBULA,
    /** flowing ribbons of light, like folded silk */
    SILK,
    /** out-of-focus circles of light */
    BOKEH,
    /** iridescent holographic sheen */
    PRISM,
    /** a few big, saturated colour fields melting into each other */
    MESH,
}

/**
 * A liquid-glass theme: an artwork backdrop (see [Backdrop]) that the glass keys refract, plus the
 * colours the keys and labels take on top of it.
 */
class Theme(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val dark: Boolean,
    val base: IntArray,        // vertical gradient for the backdrop
    val blobs: IntArray,       // light colours used by the artwork
    val accent: Int,
    val style: Backdrop = Backdrop.AURORA,
    /** colour the glass of letter keys is tinted with (alpha is chosen by the theme's darkness) */
    keyColor: Int? = null,
    labelColor: Int? = null,
    /** how strong the artwork shows (1 = normal) */
    val vivid: Float = 1f,
) {
    val label: Int = labelColor ?: if (dark) Color.WHITE else Color.rgb(18, 20, 28)
    val labelDim: Int = Color.argb(if (dark) 150 else 140, Color.red(label), Color.green(label), Color.blue(label))
    /** glass tint mixed over refracted backdrop (alpha = amount) */
    val keyTint: Int = (keyColor ?: Color.WHITE).let { Color.argb(if (dark) 36 else 140, Color.red(it), Color.green(it), Color.blue(it)) }
    val modTint: Int = if (dark) (keyColor ?: Color.WHITE).let { Color.argb(14, Color.red(it), Color.green(it), Color.blue(it)) }
    else Color.argb(64, 236, 240, 248)
    /** wash laid over the backdrop so labels stay readable */
    val veil: Int = if (dark) Color.argb(46, 4, 6, 14) else Color.argb(56, 255, 255, 255)
    val shadow: Int = if (dark) Color.argb(80, 0, 0, 0) else Color.argb(30, 30, 40, 80)
    val rimTop: Int = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(255, 255, 255, 255)
    val rimBottom: Int = if (dark) Color.argb(36, 255, 255, 255) else Color.argb(70, 120, 130, 160)
    /** body of the key-preview balloon and long-press popup */
    val balloon: Int = if (dark) blend(Color.rgb(104, 106, 122), accent, 0.18f) else Color.WHITE

    companion object {
        private fun c(v: Long) = v.toInt()

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        val ALL = listOf(
            // the first two are what "auto" picks for night and day
            Theme("aurora_night", "شفق شب", "Aurora Night", true,
                intArrayOf(c(0xFF0B0F1E), c(0xFF070912)),
                intArrayOf(c(0xFF6C5CE7), c(0xFF00C2FF), c(0xFFFF5E9C), c(0xFF2EE59D)),
                c(0xFF8B7CFF), Backdrop.AURORA),
            Theme("aurora_day", "شفق روز", "Aurora Day", false,
                intArrayOf(c(0xFFEFF2FA), c(0xFFE3E8F4)),
                intArrayOf(c(0xFFB9A8FF), c(0xFF8FE3FF), c(0xFFFFB3CF), c(0xFFA6F4D2)),
                c(0xFF6C5CE7), Backdrop.AURORA),
            Theme("nebula", "کهکشان", "Nebula", true,
                intArrayOf(c(0xFF0A0618), c(0xFF04020B)),
                intArrayOf(c(0xFF7B2FF7), c(0xFFF72585), c(0xFF4CC9F0), c(0xFF3A0CA3)),
                c(0xFFC77DFF), Backdrop.NEBULA, keyColor = c(0xFFE9D8FF)),
            Theme("neon", "نئون", "Neon", true,
                intArrayOf(c(0xFF06040C), c(0xFF000000)),
                intArrayOf(c(0xFFFF2E97), c(0xFF00E5FF), c(0xFF7A00FF), c(0xFFFF6B3D)),
                c(0xFF00E5FF), Backdrop.SILK, keyColor = c(0xFFCFFAFF), vivid = 1.15f),
            Theme("midnight", "نیمه‌شب", "Midnight", true,
                intArrayOf(c(0xFF04060C), c(0xFF0A0D16)),
                intArrayOf(c(0xFF1E40AF), c(0xFF0EA5E9), c(0xFF4338CA), c(0xFF38BDF8)),
                c(0xFF38BDF8), Backdrop.SILK),
            Theme("sunset", "غروب", "Sunset", true,
                intArrayOf(c(0xFF1C0A1E), c(0xFF120612)),
                intArrayOf(c(0xFFFF7A45), c(0xFFFF3D7F), c(0xFF9B5DE5), c(0xFFFFC145)),
                c(0xFFFF7A45), Backdrop.MESH, keyColor = c(0xFFFFE3D6)),
            Theme("ocean", "اقیانوس", "Ocean", true,
                intArrayOf(c(0xFF031620), c(0xFF010A10)),
                intArrayOf(c(0xFF00B4D8), c(0xFF0077B6), c(0xFF48CAE4), c(0xFF2EC4B6)),
                c(0xFF48CAE4), Backdrop.BOKEH, keyColor = c(0xFFD6F6FF)),
            Theme("emerald", "زمرد", "Emerald", true,
                intArrayOf(c(0xFF03140F), c(0xFF010806)),
                intArrayOf(c(0xFF10B981), c(0xFF2DD4BF), c(0xFF047857), c(0xFFA7F3D0)),
                c(0xFF34D399), Backdrop.SILK, keyColor = c(0xFFD5FBEA)),
            Theme("gold", "طلایی", "Gold", true,
                intArrayOf(c(0xFF140E06), c(0xFF070502)),
                intArrayOf(c(0xFFF5B942), c(0xFFD4892A), c(0xFFFFE29A), c(0xFF9A6420)),
                c(0xFFF5B942), Backdrop.BOKEH, keyColor = c(0xFFFFE9BF), labelColor = c(0xFFFFF6E3)),
            Theme("amoled", "مشکی", "Black", true,
                intArrayOf(c(0xFF000000), c(0xFF000000)),
                intArrayOf(c(0xFF2A2D3A), c(0xFF1C2333), c(0xFF30283C), c(0xFF161A22)),
                c(0xFF0A84FF), Backdrop.AURORA, vivid = 0.6f),
            Theme("mono_dark", "شیشه دودی", "Smoke", true,
                intArrayOf(c(0xFF141518), c(0xFF0A0A0B)),
                intArrayOf(c(0xFF4A4E58), c(0xFF2A2C31), c(0xFF60646E), c(0xFF1C1D21)),
                c(0xFFE5E7EB), Backdrop.MESH),
            Theme("holo", "هولوگرام", "Holo", false,
                intArrayOf(c(0xFFF7F4FF), c(0xFFEDF5FF)),
                intArrayOf(c(0xFFFFB5E8), c(0xFFB5F2FF), c(0xFFFFF0A8), c(0xFFC5B5FF)),
                c(0xFF7C5CFF), Backdrop.PRISM),
            Theme("lavender", "یاس", "Lilac", false,
                intArrayOf(c(0xFFF4EFFF), c(0xFFE9E4FA)),
                intArrayOf(c(0xFFC4B5FD), c(0xFFF0ABFC), c(0xFFA5B4FC), c(0xFFDDD6FE)),
                c(0xFF8B5CF6), Backdrop.SILK),
            Theme("rose", "رز", "Rose Quartz", false,
                intArrayOf(c(0xFFFFF1F4), c(0xFFF6E6F0)),
                intArrayOf(c(0xFFFFADC6), c(0xFFFFD6A5), c(0xFFCDB4FF), c(0xFFFFC8DD)),
                c(0xFFE85D8A), Backdrop.BOKEH),
            Theme("peach", "هلو", "Peach", false,
                intArrayOf(c(0xFFFFF4EC), c(0xFFFFE8DA)),
                intArrayOf(c(0xFFFFB4A2), c(0xFFFFCDB2), c(0xFFFFD6A5), c(0xFFE5989B)),
                c(0xFFF0806C), Backdrop.MESH),
            Theme("mint", "نعنا", "Mint", false,
                intArrayOf(c(0xFFF0FBF7), c(0xFFE3F5EF)),
                intArrayOf(c(0xFF99F6E4), c(0xFFBBF7D0), c(0xFFA5F3FC), c(0xFFD9F99D)),
                c(0xFF0D9488), Backdrop.AURORA),
            Theme("mono_light", "شیشه یخی", "Frost", false,
                intArrayOf(c(0xFFF4F5F7), c(0xFFE7E9EE)),
                intArrayOf(c(0xFFD9DEE8), c(0xFFEEF1F6), c(0xFFCBD3E1), c(0xFFF9FAFB)),
                c(0xFF3B82F6), Backdrop.AURORA),
        )

        fun resolve(ctx: Context, id: String): Theme {
            if (id == "auto") {
                val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (night) ALL[0] else ALL[1]
            }
            return ALL.firstOrNull { it.id == id } ?: ALL[0]
        }
    }
}
