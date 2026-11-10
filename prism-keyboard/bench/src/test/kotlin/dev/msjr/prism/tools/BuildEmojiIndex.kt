package dev.msjr.prism.tools

import dev.msjr.prism.data.EmojiData
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Packs Unicode CLDR emoji annotations (common/annotations/<lang>.xml) into `assets/emoji/<lang>.tsv`:
 * one line per emoji — emoji, name, keywords joined by "|", rank — most used first, then panel order.
 *
 *   cd bench && ./gradlew test --tests '*BuildEmojiIndex*' -Pprism.cldr=<folder with fa.xml, en.xml>
 */
class BuildEmojiIndex {

    private val cldr = System.getProperty("prism.cldr").orEmpty()

    @Test fun persian() = build("fa")
    @Test fun english() = build("en")

    private fun strip(s: String) = s.replace("️", "")

    private fun build(lang: String) {
        assumeTrue("set -Pprism.cldr to build the emoji index", cldr.isNotEmpty())
        val xml = File(cldr, "$lang.xml").readText()
        val names = LinkedHashMap<String, String>()
        val keywords = LinkedHashMap<String, String>()
        val re = Regex("""<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>""")
        for (m in re.findAll(xml)) {
            val cp = unescape(m.groupValues[1])
            val text = unescape(m.groupValues[3]).trim()
            if (m.groupValues[2].isNotEmpty()) names[cp] = text else keywords[cp] = text
        }
        // the panel's own emoji, in panel order (most used first), matched without variation selectors
        val panel = EmojiData.categories.flatMap { it.items }.distinct()
        val panelForm = panel.associateBy { strip(it) }
        // order: the most used emoji worldwide first, then the panel order
        val rank = HashMap<String, Int>().also { r ->
            POPULAR.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, e -> r.putIfAbsent(strip(e), i) }
            panel.forEachIndexed { i, e -> r.putIfAbsent(strip(e), 1000 + i) }
        }
        // flags have no annotations of their own
        names.putIfAbsent("🇮🇷", if (lang == "fa") "پرچم ایران" else "flag Iran")
        keywords.putIfAbsent("🇮🇷", if (lang == "fa") "ایران|پرچم" else "iran|flag")

        val rows = ArrayList<Triple<Int, String, String>>()
        var extra = 1000 + panel.size
        for ((cp, name) in names) {
            val key = strip(cp)
            val inPanel = key in panelForm
            // keep real emoji; CLDR also annotates arrows, currency and math symbols
            if (!inPanel && key.codePointAt(0) < 0x1F000) continue
            val emoji = panelForm[key] ?: cp
            val kws = (keywords[cp] ?: "").split('|').map { it.trim() }.filter { it.isNotEmpty() && it != name }
            val r = rank[key] ?: extra++
            rows += Triple(r, emoji, "$emoji\t$name\t${kws.joinToString("|")}\t$r")
        }
        rows.sortBy { it.first }
        val out = File("../app/src/main/assets/emoji/$lang.tsv")
        out.parentFile.mkdirs()
        out.writeText(rows.joinToString("\n", postfix = "\n") { it.third })
        println("[$lang] ${rows.size} emoji (${panel.size} in the panel), ${out.length() / 1024} KB")
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** Roughly the most used emoji worldwide, most used first (after Unicode's emoji frequency data). */
private const val POPULAR =
    "😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 ☺️ 🤦 ♥️ 🤷 🙄 😆 🤗 😉 🎂 🤔 👏 🙂 😳 🥳 😎 👌 💜 😔 💪 ✨ 💖 👀 " +
    "😋 😏 😢 👉 💗 😩 💯 🌹 💞 🎈 💙 😃 😡 💐 😜 🙈 🤞 😄 🤤 🙌 🤪 ❣️ 😀 💋 💀 👇 💔 😌 💓 🤩 🙃 😬 😱 😴 🤭 😐 🌞 😒 😇 🌸 😈 " +
    "🎶 ✌️ 🎊 🥵 😞 💚 ☀️ 🖤 💰 😚 👑 🎁 💥 🙋 ☹️ 😑 🥴 👈 💩 ✅ 👋 🤮 😤 🤢 🌟 ❗ 😥 🌈 💛 😝 😫 😲 ‼️ 🔴 🌻 🤯 💃 👊 🤬 " +
    "🏃 😕 👁️ ⚡ ☕ 🍀 💦 ⭐ 🦋 🤨 🌺 😹 🤘 🌷 💝 💤 🤝 🐰 😓 💘 🍻 😟 😣 🧐 😠 🤠 😻 🌙 😛 🤙 🙊 🧡 🤡 🤫 🌼 🥂 😷 🤓 🥶 😶 😖 " +
    "🎵 🙆 🍑 😙 🍆 🤑 🙉 🍕 🍔 🎄 🌍 ⚽ 🏆 🇮🇷 🫶 🥹 🫠 😮‍💨 ❤️‍🔥 ❤️‍🩹 🫡 🙂‍↕️"
