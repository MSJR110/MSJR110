package android.graphics

/** JVM stand-in for the framework class (the benchmark only needs it to exist). */
class RectF {
    @JvmField var left = 0f
    @JvmField var top = 0f
    @JvmField var right = 0f
    @JvmField var bottom = 0f
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
}
