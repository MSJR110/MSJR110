package dev.msjr.prism.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * A liquid-glass theme: an "ambient" background made of soft light blobs that the glass keys refract.
 */
class Theme(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val dark: Boolean,
    val base: IntArray,        // vertical gradient for the backdrop
    val blobs: IntArray,       // ambient light colors
    val accent: Int,
) {
    val label: Int = if (dark) Color.WHITE else Color.rgb(18, 20, 28)
    val labelDim: Int = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(140, 18, 20, 28)
    /** glass tint mixed over refracted backdrop (alpha = amount) */
    val keyTint: Int = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(150, 255, 255, 255)
    val modTint: Int = if (dark) Color.argb(22, 255, 255, 255) else Color.argb(80, 236, 240, 248)
    /** wash laid over the backdrop so labels stay readable */
    val veil: Int = if (dark) Color.argb(70, 4, 6, 14) else Color.argb(80, 255, 255, 255)
    val shadow: Int = if (dark) Color.argb(90, 0, 0, 0) else Color.argb(40, 30, 40, 80)
    val rimTop: Int = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(255, 255, 255, 255)
    val rimBottom: Int = if (dark) Color.argb(36, 255, 255, 255) else Color.argb(70, 120, 130, 160)

    companion object {
        val ALL = listOf(
            Theme("aurora_night", "شفق شب", "Aurora Night", true,
                intArrayOf(0xFF0B0F1E.toInt(), 0xFF0A0C16.toInt()),
                intArrayOf(0xFF6C5CE7.toInt(), 0xFF00C2FF.toInt(), 0xFFFF5E9C.toInt(), 0xFF2EE59D.toInt()),
                0xFF8B7CFF.toInt()),
            Theme("aurora_day", "شفق روز", "Aurora Day", false,
                intArrayOf(0xFFEFF2FA.toInt(), 0xFFE3E8F4.toInt()),
                intArrayOf(0xFFB9A8FF.toInt(), 0xFF8FE3FF.toInt(), 0xFFFFB3CF.toInt(), 0xFFA6F4D2.toInt()),
                0xFF6C5CE7.toInt()),
            Theme("midnight", "نیمه‌شب", "Midnight", true,
                intArrayOf(0xFF050608.toInt(), 0xFF0B0D12.toInt()),
                intArrayOf(0xFF1E3A8A.toInt(), 0xFF0EA5E9.toInt(), 0xFF312E81.toInt(), 0xFF0F172A.toInt()),
                0xFF38BDF8.toInt()),
            Theme("sunset", "غروب", "Sunset", true,
                intArrayOf(0xFF1A0B1E.toInt(), 0xFF120712.toInt()),
                intArrayOf(0xFFFF7A45.toInt(), 0xFFFF3D7F.toInt(), 0xFF9B5DE5.toInt(), 0xFFFFC145.toInt()),
                0xFFFF7A45.toInt()),
            Theme("ocean", "اقیانوس", "Ocean", true,
                intArrayOf(0xFF03141F.toInt(), 0xFF020B12.toInt()),
                intArrayOf(0xFF00B4D8.toInt(), 0xFF0077B6.toInt(), 0xFF48CAE4.toInt(), 0xFF2EC4B6.toInt()),
                0xFF48CAE4.toInt()),
            Theme("rose", "رز", "Rose Quartz", false,
                intArrayOf(0xFFFFF1F4.toInt(), 0xFFF6E6F0.toInt()),
                intArrayOf(0xFFFFADC6.toInt(), 0xFFFFD6A5.toInt(), 0xFFCDB4FF.toInt(), 0xFFFFC8DD.toInt()),
                0xFFE85D8A.toInt()),
            Theme("mono_dark", "شیشه دودی", "Smoke", true,
                intArrayOf(0xFF111214.toInt(), 0xFF0A0A0B.toInt()),
                intArrayOf(0xFF3A3D45.toInt(), 0xFF2A2C31.toInt(), 0xFF50545E.toInt(), 0xFF1C1D21.toInt()),
                0xFFE5E7EB.toInt()),
            Theme("mono_light", "شیشه یخی", "Frost", false,
                intArrayOf(0xFFF4F5F7.toInt(), 0xFFE7E9EE.toInt()),
                intArrayOf(0xFFD9DEE8.toInt(), 0xFFEEF1F6.toInt(), 0xFFCBD3E1.toInt(), 0xFFF9FAFB.toInt()),
                0xFF3B82F6.toInt()),
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
