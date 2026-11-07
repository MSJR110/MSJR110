package dev.msjr.prism.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.msjr.prism.data.ClipboardStore
import dev.msjr.prism.data.Prefs
import dev.msjr.prism.text.UserDictionary
import dev.msjr.prism.ui.GlassRenderer
import dev.msjr.prism.ui.Theme
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : Activity() {

    private val prefs by lazy { Prefs.get(this) }
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var font: Typeface
    private lateinit var fontBold: Typeface
    private lateinit var content: LinearLayout
    private var setupCard: View? = null
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (night) Color.WHITE else Color.rgb(18, 20, 28)
    private val fgDim get() = if (night) Color.argb(160, 255, 255, 255) else Color.argb(150, 18, 20, 28)
    private val accent get() = if (night) 0xFF8B7CFF.toInt() else 0xFF6C5CE7.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        font = try { Typeface.createFromAsset(assets, "fonts/Vazirmatn-Regular.ttf") } catch (_: Throwable) { Typeface.DEFAULT }
        fontBold = try { Typeface.createFromAsset(assets, "fonts/Vazirmatn-Bold.ttf") } catch (_: Throwable) { Typeface.DEFAULT_BOLD }
        Prefs.load(this) // applies one-time default migrations before the controls read them
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup()
    }

    // ------------------------------------------------------------------ building

    private fun build() {
        val root = FrameLayout(this)
        root.background = AuroraDrawable(Theme.resolve(this, "auto"))
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (24 * dp).toInt(), (18 * dp).toInt(), (40 * dp).toInt())
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = i.top; bottom = i.bottom
            } else {
                @Suppress("DEPRECATION") run { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom }
            }
            scroll.setPadding(0, top, 0, bottom)
            insets
        }

        // header
        content.addView(text("Prism", 34f, bold = true).apply { gravity = Gravity.CENTER })
        content.addView(text("منشور · کیبورد شیشه‌ای خودت", 15f, color = fgDim).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, (18 * dp).toInt())
        })

        setupCard = setupSection()
        content.addView(setupCard)

        // try it
        card("امتحانش کن") { c ->
            val et = EditText(this).apply {
                hint = "اینجا بنویس…"
                typeface = font
                textSize = 16f
                setTextColor(fg)
                setHintTextColor(fgDim)
                background = pill(if (night) Color.argb(40, 255, 255, 255) else Color.argb(170, 255, 255, 255), 16f)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
            }
            c.addView(et)
        }

        card("ظاهر") { c ->
            c.addView(label("تم"))
            c.addView(themePicker())
            c.addView(label("پس‌زمینه"))
            c.addView(chips(Prefs.BG_MODE, listOf("aurora" to "نورهای شفق", "image" to "عکس خودم"), "aurora") { v ->
                if (v == "image") pickImage()
            })
            c.addView(button("انتخاب عکس پس‌زمینه") { pickImage() })
            c.addView(slider("شدت شیشه", Prefs.GLASS_STRENGTH, 70, 0, 100))
            c.addView(toggle("شکست نور (لیکوئید گلس واقعی)", "دکمه‌ها پس‌زمینه رو مثل شیشه خم می‌کنن", Prefs.REFRACTION, true))
            c.addView(toggle("نور زیر انگشت", "یه هاله‌ی نرم دنبال انگشتت میاد", Prefs.GLOW, false))
            c.addView(slider("ارتفاع کیبورد", Prefs.HEIGHT, 100, 80, 130, suffix = "٪"))
            c.addView(toggle("ردیف اعداد", "یه ردیف عدد بالای حروف", Prefs.NUMBER_ROW, false))
            c.addView(button("برداشتن دکمه‌ی ⌨ زیر کیبورد") {
                toast("بقیه‌ی کیبوردها (مثل کیبورد سامسونگ) رو خاموش کن تا فقط Prism بمونه")
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                } catch (_: Throwable) { }
            })
            c.addView(toggle("پیش‌نمایش حباب کلید", "حباب بزرگ بالای کلید موقع لمس", Prefs.KEY_PREVIEW, true))
            c.addView(label("فونت کلیدها"))
            c.addView(chips(Prefs.FONT, listOf("vazir" to "وزیرمتن", "system" to "فونت گوشی"), "vazir"))
        }

        card("حس و صدا") { c ->
            c.addView(slider("قدرت لرزش", Prefs.HAPTIC, 55, 0, 100, zeroLabel = "خاموش"))
            c.addView(label("صدای کلیدها"))
            c.addView(chips(Prefs.SOUND, listOf("iphone" to "آیفونی", "click" to "کلیک", "soft" to "نرم", "glass" to "شیشه‌ای", "mech" to "مکانیکی", "system" to "سیستم", "off" to "خاموش"), "iphone"))
            c.addView(slider("بلندی صدا", Prefs.SOUND_VOLUME, 55, 0, 100))
            c.addView(toggle("بی‌صدا با حالت سکوت گوشی", "وقتی گوشی روی سایلنت یا ویبره‌ست صدا نده", Prefs.SOUND_RESPECT_SILENT, true))
            c.addView(slider("مکث نگه‌داشتن کلید", Prefs.LONG_PRESS_MS, 400, 180, 650, suffix = " ms"))
            c.addView(slider("سرعت ترک‌پد اسپیس", Prefs.TRACKPAD_SPEED, 100, 50, 200, suffix = "٪"))
            c.addView(toggle("کشیدن روی پاک‌کن", "انگشت رو از ⌫ به چپ بکش تا کلمه‌ها پاک بشن", Prefs.SWIPE_DELETE, true))
            c.addView(toggle("لمس هوشمند", "کیبورد یاد می‌گیره انگشتت دقیقاً کجا می‌خوره", Prefs.ADAPTIVE_TOUCH, true))
        }

        card("نوشتن") { c ->
            c.addView(toggle("پیشنهاد کلمه", "نوار پیشنهاد بالای کیبورد", Prefs.SUGGESTIONS, true))
            c.addView(toggle("تصحیح خودکار انگلیسی", "با زدن فاصله غلط‌ها درست می‌شن (⌫ برمی‌گردونه)", Prefs.AUTOCORRECT, true))
            c.addView(toggle("تصحیح خودکار فارسی", "غلط تایپی رو موقع اسپیس درست می‌کنه؛ با ⌫ برمی‌گرده", Prefs.AUTOCORRECT_FA, true))
            c.addView(toggle("حرف بزرگ خودکار", "اول جمله‌های انگلیسی", Prefs.AUTOCAP, true))
            c.addView(toggle("دو بار فاصله = نقطه", "مثل آیفون", Prefs.DOUBLE_SPACE, true))
            c.addView(toggle("نیم‌فاصله‌ی هوشمند", "«می خواهم» ← «می‌خواهم» و «کتاب ها» ← «کتاب‌ها»", Prefs.SMART_ZWNJ, true))
            c.addView(toggle("اعداد فارسی", "۱۲۳ به جای 123 در کیبورد فارسی", Prefs.PERSIAN_DIGITS, true))
            c.addView(toggle("یادگیری کلمات من", "فقط روی همین گوشی ذخیره می‌شه", Prefs.LEARN, true))
        }

        card("کلیپ‌بورد") { c ->
            c.addView(toggle("تاریخچه‌ی کلیپ‌بورد", "متن‌های کپی‌شده توی کیبورد در دسترس باشن", Prefs.CLIPBOARD, true))
            c.addView(button("پاک کردن تاریخچه") {
                ClipboardStore.clearAll(this)
                // tell the running keyboard too, or it would write its copy back
                prefs.edit().putLong(Prefs.CLIPBOARD_RESET, System.currentTimeMillis()).apply()
                toast("پاک شد")
            })
        }

        card("میان‌برهای متنی") { c ->
            c.addView(text("یه کلمه‌ی کوتاه بنویس و فاصله بزن تا به متن کامل تبدیل بشه. مثلاً «;adr» ← آدرس کاملت", 13f, color = fgDim))
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(list)
            renderShortcuts(list)
            c.addView(button("+ میان‌بر جدید") { addShortcut(list) })
        }

        card("حریم خصوصی") { c ->
            c.addView(text("Prism هیچ دسترسی‌ای به اینترنت نداره. هرچی می‌نویسی و یاد می‌گیره فقط روی گوشی خودت می‌مونه.", 13f, color = fgDim))
            c.addView(button("فراموش کردن کلمات یادگرفته‌شده") {
                UserDictionary.clearAll(this)
                // tell the running keyboard too, or it would write its copy back
                prefs.edit().putLong(Prefs.USER_DICT_RESET, System.currentTimeMillis()).apply()
                toast("پاک شد")
            })
            c.addView(button("ریست لمس هوشمند") {
                prefs.edit().putFloat(Prefs.TOUCH_OFFSET_X, 0f).putFloat(Prefs.TOUCH_OFFSET_Y, 0f).apply()
                toast("ریست شد")
            })
        }

        card("ترفندها") { c ->
            val tips = listOf(
                "اسپیس رو نگه دار ← کیبورد تبدیل به ترک‌پد می‌شه و مکان‌نما رو جابه‌جا می‌کنی",
                "روی اسپیس به چپ و راست بکش ← سریع مکان‌نما رو حرکت بده",
                "از ⌫ به سمت چپ بکش ← کلمه‌به‌کلمه پاک کن، برگردی لغو می‌شه",
                "حرف کوچیک گوشه‌ی هر کلید (مثل ض روی ص) ← کلید رو نگه دار یا رو به بالا بکش",
                "شیفت (⇧) توی فارسی ← ض غ ظ ژ ذ آ و اعراب؛ اسپیس هم نیم‌فاصله می‌شه",
                "کلید 🙂 رو نگه دار ← نقطه، ویرگول، علامت سؤال و بقیه‌ی علامت‌ها",
                "دو بار اسپیس ← نقطه",
                "توی انگلیسی روی ردیف بالا به سمت بالا بکش ← عدد",
                "کره‌ی 🌐 ← عوض کردن فارسی/انگلیسی؛ نگهش داری ← کیبوردهای دیگه",
                "بعد از تصحیح خودکار، یه ⌫ بزن ← همونی که نوشتی برمی‌گرده",
                "اگه یادت رفت زبان رو عوض کنی (مثلاً sghl) ← «سلام» توی پیشنهادها میاد",
                "روی پیشنهاد نگه دار ← از کلمات یادگرفته‌شده حذف می‌شه",
                "کارت کلیپ‌بورد رو نگه دار ← سنجاق می‌شه",
            )
            for (t in tips) c.addView(text("•  $t", 13.5f).apply { setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt()) })
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }
        content.addView(text("Prism $version · ساخته‌شده برای خودت", 12f, color = fgDim).apply {
            gravity = Gravity.CENTER; setPadding(0, (12 * dp).toInt(), 0, 0)
        })
    }

    // ------------------------------------------------------------------ setup

    private fun setupSection(): View {
        val c = cardView()
        c.addView(text("راه‌اندازی", 18f, bold = true))
        c.addView(text("دو قدم کوتاه تا Prism کیبورد اصلیت بشه:", 13.5f, color = fgDim))
        c.addView(button("۱. فعال کردن Prism در تنظیمات") {
            startActivity(Intent(SysSettings.ACTION_INPUT_METHOD_SETTINGS))
        }.apply { tag = "step1" })
        c.addView(button("۲. انتخاب Prism به عنوان کیبورد") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }.apply { tag = "step2" })
        return c
    }

    private fun refreshSetup() {
        val card = setupCard ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        card.visibility = if (enabled && current) View.GONE else View.VISIBLE
        (card as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) {
                val v = g.getChildAt(i)
                if (v.tag == "step1") v.alpha = if (enabled) 0.45f else 1f
                if (v.tag == "step2") v.alpha = if (enabled) 1f else 0.45f
            }
        }
    }

    // ------------------------------------------------------------------ widgets

    private fun card(title: String, fill: (LinearLayout) -> Unit) {
        val c = cardView()
        c.addView(text(title, 18f, bold = true).apply { setPadding(0, 0, 0, (6 * dp).toInt()) })
        fill(c)
        content.addView(c)
    }

    private fun cardView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = 26 * dp
            setColor(if (night) Color.argb(34, 255, 255, 255) else Color.argb(150, 255, 255, 255))
            setStroke((1 * dp).toInt(), if (night) Color.argb(60, 255, 255, 255) else Color.argb(220, 255, 255, 255))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (14 * dp).toInt()
        }
        elevation = 0f
    }

    private fun pill(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(color)
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = fg) = TextView(this).apply {
        text = s
        textSize = size
        typeface = if (bold) fontBold else font
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun label(s: String) = text(s, 14f, color = fgDim).apply { setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt()) }

    private fun button(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14.5f
        typeface = fontBold
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = pill(accent, 16f)
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * dp).toInt()
        }
        setOnClickListener { onClick() }
    }

    private fun toggle(title: String, sub: String?, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f))
        if (sub != null) texts.addView(text(sub, 12f, color = fgDim))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.WHITE))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, if (night) Color.argb(90, 255, 255, 255) else Color.argb(70, 0, 0, 0)))
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun slider(title: String, key: String, def: Int, min: Int, max: Int, suffix: String = "", zeroLabel: String? = null): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt()) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(title, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = text("", 13f, color = fgDim)
        head.addView(value)
        box.addView(head)
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        fun show(v: Int) { value.text = if (v == 0 && zeroLabel != null) zeroLabel else fa("$v$suffix") }
        show(cur)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt(key, (s?.progress ?: 0) + min).apply() }
            })
        }
        box.addView(sb)
        return box
    }

    private fun chips(key: String, options: List<Pair<String, String>>, def: String, onPick: ((String) -> Unit)? = null): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(row)
        val views = ArrayList<TextView>()
        fun paint(sel: String) {
            for ((i, v) in views.withIndex()) {
                val on = options[i].first == sel
                v.background = pill(if (on) accent else (if (night) Color.argb(36, 255, 255, 255) else Color.argb(160, 255, 255, 255)), 14f)
                v.setTextColor(if (on) Color.WHITE else fg)
            }
        }
        for ((value, title) in options) {
            val v = text(title, 14f).apply {
                val p = (14 * dp).toInt()
                setPadding(p, (8 * dp).toInt(), p, (8 * dp).toInt())
                setOnClickListener {
                    prefs.edit().putString(key, value).apply()
                    paint(value)
                    onPick?.invoke(value)
                }
            }
            row.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * dp).toInt()
            })
            views += v
        }
        paint(prefs.getString(key, def) ?: def)
        return hs
    }

    private fun themePicker(): View {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(row)
        val items = listOf<Pair<String, Theme?>>("auto" to null) + Theme.ALL.map { it.id to it }
        val frames = ArrayList<View>()
        fun mark(sel: String) {
            for ((i, f) in frames.withIndex()) {
                f.background = if (items[i].first == sel) GradientDrawable().apply {
                    cornerRadius = 22 * dp; setStroke((2.5f * dp).toInt(), accent)
                } else null
            }
        }
        for ((id, th) in items) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            }
            val sw = View(this).apply {
                background = if (th == null) AuroraDrawable(Theme.ALL[0], split = Theme.ALL[1]) else AuroraDrawable(th)
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val swHolder = FrameLayout(this).apply {
                background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.TRANSPARENT) }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 18 * dp)
                    }
                }
                addView(sw, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            col.addView(swHolder, LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()))
            col.addView(text(th?.nameFa ?: "خودکار", 12f).apply { gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, 0) })
            col.setOnClickListener { prefs.edit().putString(Prefs.THEME, id).apply(); mark(id) }
            row.addView(col)
            frames += col
        }
        mark(prefs.getString(Prefs.THEME, "auto") ?: "auto")
        return hs
    }

    // ------------------------------------------------------------------ shortcuts

    private fun loadShortcuts(): JSONObject = try { JSONObject(prefs.getString(Prefs.SHORTCUTS, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    private fun renderShortcuts(list: LinearLayout) {
        list.removeAllViews()
        val o = loadShortcuts()
        for (k in o.keys()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(k, 15f, bold = true))
            t.addView(text(o.getString(k), 13f, color = fgDim).apply { maxLines = 2 })
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text("حذف", 13f, color = accent).apply {
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener {
                    val n = loadShortcuts(); n.remove(k)
                    prefs.edit().putString(Prefs.SHORTCUTS, n.toString()).apply()
                    renderShortcuts(list)
                }
            })
            list.addView(row)
        }
    }

    private fun addShortcut(list: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0)
        }
        val key = EditText(this).apply { hint = "میان‌بر (مثلاً ;adr)"; typeface = font; isSingleLine = true }
        val value = EditText(this).apply { hint = "متن کامل"; typeface = font; minLines = 2 }
        box.addView(key); box.addView(value)
        AlertDialog.Builder(this)
            .setTitle("میان‌بر جدید")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val k = key.text.toString().trim()
                val v = value.text.toString()
                if (k.isNotEmpty() && !k.contains(' ') && v.isNotEmpty()) {
                    val o = loadShortcuts(); o.put(k, v)
                    prefs.edit().putString(Prefs.SHORTCUTS, o.toString()).apply()
                    renderShortcuts(list)
                } else toast("میان‌بر باید یه کلمه‌ی بدون فاصله باشه")
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    // ------------------------------------------------------------------ background photo

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try { startActivityForResult(i, REQ_IMAGE) } catch (_: Throwable) { toast("انتخاب عکس ممکن نشد") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Thread {
            val ok = try { saveBackground(uri) } catch (_: Throwable) { false }
            runOnUiThread {
                if (ok) {
                    prefs.edit().putString(Prefs.BG_MODE, "image").putLong("bg_version", System.currentTimeMillis()).apply()
                    toast("پس‌زمینه عوض شد ✨")
                    recreate()
                } else toast("عکس باز نشد")
            }
        }.start()
    }

    private fun saveBackground(uri: Uri): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1440 || opts.outHeight / (sample * 2) >= 1440) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        FileOutputStream(File(filesDir, GlassRenderer.BACKGROUND_FILE)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return true
    }

    // ------------------------------------------------------------------ misc

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun fa(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(if (c in '0'..'9') ('۰' + (c - '0')) else c)
        return sb.toString()
    }

    /** Soft aurora gradient used as page background and theme swatches. */
    private class AuroraDrawable(private val t: Theme, private val split: Theme? = null) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat(); val h = b.height().toFloat()
            if (split != null) {
                c.save(); c.clipRect(b.left.toFloat(), b.top.toFloat(), b.left + w / 2f, b.bottom.toFloat())
                paintTheme(c, t, w, h); c.restore()
                c.save(); c.clipRect(b.left + w / 2f, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                paintTheme(c, split, w, h); c.restore()
            } else paintTheme(c, t, w, h)
        }

        private fun paintTheme(c: Canvas, th: Theme, w: Float, h: Float) {
            p.shader = LinearGradient(0f, 0f, 0f, h, th.base[0], th.base[1], Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            val spots = arrayOf(floatArrayOf(0.1f, 0.12f, 0.7f), floatArrayOf(0.95f, 0.3f, 0.6f), floatArrayOf(0.3f, 0.75f, 0.7f), floatArrayOf(0.85f, 0.95f, 0.6f))
            for ((i, s) in spots.withIndex()) {
                val col = th.blobs[i % th.blobs.size]
                val r = s[2] * maxOf(w, h * 0.6f)
                p.shader = RadialGradient(s[0] * w, s[1] * h, r,
                    intArrayOf(Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    companion object {
        private const val REQ_IMAGE = 42
    }
}
