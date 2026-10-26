package com.yuxuan.lingo.data

import com.yuxuan.lingo.domain.Word
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class RelatedPronunciationCodecTest {
    @Test fun retainsMissingAccentsAndExtraWordsWithoutDuplicatingMainReadings() {
        fun word(name: String, us: String, uk: String) = Word(name, name, us, "n.", "测试", 1, 1, pronunciationUk = uk)
        val words = listOf(word("complete", "/a/", "/b/"), word("missingUk", "/a/", ""),
            word("missingUs", "", "/b/")).associateBy { it.id }
        val json = """{"complete":{"us":"/x/","uk":"/y/"},
            "missingUk":{"us":"/ignored/","uk":"/uk/","future":{"x":1}},
            "missingUs":{"us":"/us/","uk":"/ignored/"},
            "extra":{"us":"/extra-us/","uk":"/extra-uk/"},
            "empty":{"us":"","uk":""}}"""
        assertEquals(mapOf("missingUk" to ("" to "/uk/"), "missingUs" to ("/us/" to ""),
            "extra" to ("/extra-us/" to "/extra-uk/")), RelatedPronunciationCodec.read(StringReader(json), words))
    }
}
