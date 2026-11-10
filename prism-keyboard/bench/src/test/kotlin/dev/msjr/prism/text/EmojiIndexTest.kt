package dev.msjr.prism.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiIndexTest {
    private val fa = File("../app/src/main/assets/emoji/fa.tsv").inputStream().use { EmojiIndex.load(it, persian = true) }
    private val en = File("../app/src/main/assets/emoji/en.tsv").inputStream().use { EmojiIndex.load(it, persian = false) }

    @Test fun search() {
        for (q in listOf("خنده", "قلب", "پیتزا", "گریه", "تولد", "گل", "ماشین", "ایران", "مي خندم")) println("fa $q → ${fa.search(q, 10).joinToString(" ")}")
        for (q in listOf("laugh", "heart", "pizza", "cry", "birthday", "fire", "car", "iran", "thumbs up")) println("en $q → ${en.search(q, 10).joinToString(" ")}")
        for (w in listOf("پیتزا", "قلب", "خنده", "تولد", "گل", "سلام", "من", "کتاب")) println("fa forWord $w → ${fa.forWord(w)}")
        for (w in listOf("pizza", "heart", "love", "birthday", "the", "man", "book", "fire")) println("en forWord $w → ${en.forWord(w)}")
        assertTrue("🍕" in fa.search("پیتزا"))
        assertTrue("🍕" in en.search("piz"))
        assertEquals("🍕", en.forWord("pizza"))
        assertEquals("❤️", fa.forWord("قلب"))
        assertEquals("❤️", fa.search("قلب").first())
        assertEquals("😂", fa.search("خنده").first())
        assertEquals("🇮🇷", fa.search("ایران").first())
        assertNull(en.forWord("the"))
        assertNull(fa.forWord("کتابخانه‌ها"))
    }
}
