package dev.msjr.prism.keyboard

/** Which kind of field we're typing into — changes a few keys on the bottom row. */
enum class FieldKind { TEXT, EMAIL, URL, NUMBER, PHONE }

enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }

object Layouts {

    private val FA_DIGITS = "۱۲۳۴۵۶۷۸۹۰"
    private val EN_DIGITS = "1234567890"

    fun digits(persian: Boolean) = if (persian) FA_DIGITS else EN_DIGITS

    // ---------- letter pages ----------

    private val enPopups = mapOf(
        "a" to "àáâäãåāæ", "c" to "çćč", "e" to "èéêëēę", "i" to "ìíîïī",
        "n" to "ñń", "o" to "òóôöõøōœ", "s" to "ßśš", "u" to "ùúûüū",
        "y" to "ýÿ", "z" to "žźż", "l" to "ł", "d" to "ð", "g" to "ğ",
    )

    /** Extra long-press characters (the Samsung-style hint, if any, always comes first). */
    private val faPopups = mapOf(
        "ا" to listOf("أ", "إ", "ء", "اً"),
        "ی" to listOf("ي", "ى"),
        "ه" to listOf("ۀ", "ة", "هٔ"),
        "و" to listOf("ؤ"),
        "ک" to listOf("ك"),
        "ت" to listOf("ة"),
        "ر" to listOf("ژ"),
        "ل" to listOf("لا"),
        "گ" to listOf("ٔ"),
        "ق" to listOf("ـ"),
        "ث" to listOf("ٌ", "ٍ"),
        "ب" to listOf("ْ"),
    )

    /**
     * Samsung Keyboard's Persian layout, left to right as it appears on screen.
     * Each pair is (letter, small corner hint). The hint is typed by long-press or a flick up,
     * and becomes the main letter while shift is on.
     */
    private val FA_ROWS: List<List<Pair<String, String?>>> = listOf(
        listOf("ص" to "ض", "ث" to "ً", "ق" to null, "ف" to null, "ع" to "غ",
            "ه" to null, "خ" to null, "ح" to null, "ج" to null, "چ" to null),
        listOf("ش" to "َ", "س" to "ُ", "ی" to "ئ", "ب" to "ّ", "ل" to "ِ",
            "ا" to "آ", "ت" to null, "ن" to null, "م" to null, "پ" to null),
        listOf("ط" to "ظ", "ز" to "ژ", "ر" to null, "د" to "ذ", "و" to null, "ک" to null, "گ" to "ء"),
    )

    /** What a few hint-less keys become on the shift layer. */
    private val faShiftExtra = mapOf("ه" to "ۀ", "ت" to "ة", "و" to "ؤ", "ک" to "ك", "ر" to "ٔ", "ق" to "ٌ", "ف" to "ٍ")

    fun letters(lang: String, kind: FieldKind, numberRow: Boolean, persianDigits: Boolean, faShift: Boolean = false): KeyboardLayout {
        val fa = lang == "fa"
        val rows = ArrayList<Row>()
        val digits = digits(fa && persianDigits)
        if (numberRow) {
            rows += Row(digits.map { ch(it.toString()) }, heightWeight = 0.82f)
        }
        if (!fa) {
            val r1 = "qwertyuiop"
            rows += Row(r1.mapIndexed { i, c -> enKey(c.toString(), if (numberRow) null else EN_DIGITS[i].toString()) })
            rows += Row("asdfghjkl".map { enKey(it.toString(), null) }, indent = 0.5f)
            rows += Row(
                listOf(Key(KeyType.SHIFT, "shift", width = 1.5f)) +
                    "zxcvbnm".map { enKey(it.toString(), null) } +
                    listOf(Key(KeyType.DELETE, "del", width = 1.5f)),
            )
            rows += bottomRow(fa = false, kind = kind, units = 10f, page = Page.LETTERS)
            return KeyboardLayout("en", rows, rtl = false, units = 10f)
        } else {
            fun row(i: Int) = FA_ROWS[i].map { (s, h) -> if (faShift) faShiftKey(s, h) else faKey(s, h) }
            rows += Row(row(0))
            rows += Row(row(1))
            rows += Row(
                listOf(Key(KeyType.SHIFT, "shift", width = 1.5f)) + row(2) +
                    listOf(Key(KeyType.DELETE, "del", width = 1.5f)),
            )
            rows += bottomRow(fa = true, kind = kind, units = 10f, page = Page.LETTERS, faShift = faShift)
            return KeyboardLayout("fa", rows, rtl = true, units = 10f)
        }
    }

    private fun ch(s: String, hint: String? = null, popup: List<String> = emptyList()) =
        Key(KeyType.CHAR, s, s, 1f, popup, hint)

    private fun enKey(s: String, hint: String?): Key {
        val pop = ArrayList<String>()
        if (hint != null) pop += hint
        enPopups[s]?.forEach { pop += it.toString() }
        return Key(KeyType.CHAR, s, s, 1f, pop, hint)
    }

    private fun faKey(s: String, hint: String?): Key {
        val pop = ArrayList<String>()
        if (hint != null) pop += hint
        faPopups[s]?.let { pop += it }
        return Key(KeyType.CHAR, s, s, 1f, pop, hint)
    }

    /** Shift layer: the hint (or a related character) becomes the key; the plain letter moves to long-press. */
    private fun faShiftKey(s: String, hint: String?): Key {
        val main = hint ?: faShiftExtra[s] ?: return faKey(s, null)
        // a lone diacritic is drawn on a tatweel so it doesn't float in the air
        val mark = Character.getType(main[0]) == Character.NON_SPACING_MARK.toInt()
        return Key(KeyType.CHAR, if (mark) "ـ$main" else main, main, 1f, listOf(s), null)
    }

    // ---------- bottom row ----------

    /**
     * Samsung-style bottom row: [!#1] [language] [emoji] [ space ] [ enter ].
     * The emoji key's long-press holds the punctuation. Email/URL fields swap in "@" or "/" and a "." key.
     * With Persian shift on, space types a half-space (نیم‌فاصله).
     */
    private fun bottomRow(fa: Boolean, kind: FieldKind, units: Float, page: Page, faShift: Boolean = false): Row {
        val u = units / 10f
        val keys = ArrayList<Key>()
        val switchLabel = when (page) {
            Page.LETTERS -> if (fa) "!#۱" else "!#1"
            else -> if (fa) "ابپ" else "ABC"
        }
        val switchType = if (page == Page.LETTERS) KeyType.SYMBOLS else KeyType.LETTERS
        keys += Key(switchType, switchLabel, width = 1.3f * u)
        keys += Key(KeyType.LANG, "lang", width = 1.25f * u)

        val web = kind == FieldKind.EMAIL || kind == FieldKind.URL
        when (kind) {
            FieldKind.EMAIL -> keys += Key(KeyType.CHAR, "@", width = 1.0f * u)
            FieldKind.URL -> keys += Key(KeyType.CHAR, "/", width = 1.0f * u)
            else -> keys += Key(KeyType.EMOJI, "emoji", width = 1.0f * u, popup = if (fa)
                listOf(".", "،", "؟", "!", ":", "؛", "«", "»", "-")
            else listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-"))
        }

        val spaceW = (if (web) 3.0f else 4.0f) * u
        keys += if (fa && faShift) Key(KeyType.ZWNJ, "نیم‌فاصله", "\u200C", spaceW)
        else Key(KeyType.SPACE, if (fa) "فارسی" else "English", " ", spaceW)

        if (web) keys += Key(KeyType.CHAR, ".", ".", 1.0f * u, listOf(".com", ".ir", ".net", ".org", "-", "_"))
        keys += Key(KeyType.ENTER, "enter", width = 2.45f * u)
        return Row(keys)
    }

    // ---------- symbols ----------

    private val symPopups = mapOf(
        "$" to listOf("€", "£", "¥", "₿", "﷼", "₹", "¢"),
        "-" to listOf("–", "—", "·", "_"),
        "?" to listOf("¿", "؟"),
        "!" to listOf("¡"),
        "'" to listOf("‘", "’", "‚", "`"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "." to listOf("…"),
        "0" to listOf("°", "∅"),
        "%" to listOf("‰", "٪"),
        "&" to listOf("§"),
        "/" to listOf("\\"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "*" to listOf("★", "†", "‡"),
        "=" to listOf("≠", "≈", "∞"),
        "+" to listOf("±"),
        "<" to listOf("≤", "«"),
        ">" to listOf("≥", "»"),
    )

    private fun sym(s: String, w: Float = 1f) = Key(KeyType.CHAR, s, s, w, symPopups[s] ?: emptyList())

    fun symbols(lang: String, kind: FieldKind, persianDigits: Boolean, page2: Boolean): KeyboardLayout {
        val fa = lang == "fa"
        val units = 10f
        val rows = ArrayList<Row>()
        if (!page2) {
            val d = digits(fa && persianDigits)
            rows += Row(d.mapIndexed { i, c ->
                val s = c.toString()
                val alt = ArrayList<String>()
                // offer the other digit system on long-press
                val other = digits(!(fa && persianDigits))[i].toString()
                alt += other
                symPopups[EN_DIGITS[i].toString()]?.let { alt += it }
                Key(KeyType.CHAR, s, s, 1f, alt)
            })
            val r2 = if (fa) listOf("-", "/", ":", "؛", "(", ")", "٪", "&", "@", "\"")
            else listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"")
            rows += Row(r2.map { sym(it) })
            val r3 = if (fa) listOf(".", "،", "؟", "!", "'") else listOf(".", ",", "?", "!", "'")
            rows += Row(listOf(Key(KeyType.SYMBOLS2, "#+=", width = 1.5f)) + r3.map { sym(it, 1.4f) } +
                listOf(Key(KeyType.DELETE, "del", width = 1.5f)))
        } else {
            rows += Row(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { sym(it) })
            val r2 = if (fa) listOf("_", "\\", "|", "~", "<", ">", "﷼", "€", "$", "•")
            else listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•")
            rows += Row(r2.map { sym(it) })
            val r3 = if (fa) listOf(".", "،", "؟", "!", "'") else listOf(".", ",", "?", "!", "'")
            rows += Row(listOf(Key(KeyType.SYMBOLS, if (fa) "۱۲۳" else "123", width = 1.5f)) + r3.map { sym(it, 1.4f) } +
                listOf(Key(KeyType.DELETE, "del", width = 1.5f)))
        }
        rows += bottomRow(fa, kind, units, if (page2) Page.SYMBOLS2 else Page.SYMBOLS)
        return KeyboardLayout(if (page2) "sym2" else "sym", rows, rtl = false, units = units)
    }

    // ---------- numpad ----------

    fun numpad(phone: Boolean, persianDigits: Boolean): KeyboardLayout {
        val d = digits(persianDigits)
        fun n(i: Int, sub: String? = null) = Key(KeyType.CHAR, d[i].toString(), d[i].toString(), 1f,
            listOf(EN_DIGITS[i].toString()).filter { it != d[i].toString() }, sub)
        val rows = ArrayList<Row>()
        rows += Row(listOf(n(0), n(1, if (phone) "ABC" else null), n(2, if (phone) "DEF" else null),
            Key(KeyType.CHAR, "-", "-", 0.8f, listOf("–", "—"))))
        rows += Row(listOf(n(3, if (phone) "GHI" else null), n(4, if (phone) "JKL" else null), n(5, if (phone) "MNO" else null),
            Key(KeyType.SPACE, "␣", " ", 0.8f)))
        rows += Row(listOf(n(6, if (phone) "PQRS" else null), n(7, if (phone) "TUV" else null), n(8, if (phone) "WXYZ" else null),
            Key(KeyType.DELETE, "del", width = 0.8f)))
        val bottom = if (phone) listOf(
            Key(KeyType.CHAR, "*", "*", 1f, listOf("#", "+", "(", ")", ",", ";")),
            n(9),
            Key(KeyType.CHAR, "+", "+", 1f, listOf("#", "*", ",", ";")),
            Key(KeyType.ENTER, "enter", width = 0.8f),
        ) else listOf(
            Key(KeyType.CHAR, ",", ",", 1f, listOf("،", "/", ":")),
            n(9),
            Key(KeyType.CHAR, ".", ".", 1f, listOf("٫", "/")),
            Key(KeyType.ENTER, "enter", width = 0.8f),
        )
        rows += Row(bottom)
        return KeyboardLayout("num", rows, rtl = false, units = 3.8f)
    }

    // ---------- helpers for the wrong-layout fixer and proximity model ----------

    /** Standard Persian (ISIRI) PC layout mapping from QWERTY key positions. */
    val EN_TO_FA: Map<Char, Char> = mapOf(
        'q' to 'ض', 'w' to 'ص', 'e' to 'ث', 'r' to 'ق', 't' to 'ف', 'y' to 'غ', 'u' to 'ع', 'i' to 'ه',
        'o' to 'خ', 'p' to 'ح', '[' to 'ج', ']' to 'چ', 'a' to 'ش', 's' to 'س', 'd' to 'ی', 'f' to 'ب',
        'g' to 'ل', 'h' to 'ا', 'j' to 'ت', 'k' to 'ن', 'l' to 'م', ';' to 'ک', '\'' to 'گ', 'z' to 'ظ',
        'x' to 'ط', 'c' to 'ز', 'v' to 'ر', 'b' to 'ذ', 'n' to 'د', 'm' to 'پ', ',' to 'و',
        'C' to 'ژ', 'H' to 'آ',
    )
    val FA_TO_EN: Map<Char, Char> = EN_TO_FA.entries.associate { (k, v) -> v to k }
}
