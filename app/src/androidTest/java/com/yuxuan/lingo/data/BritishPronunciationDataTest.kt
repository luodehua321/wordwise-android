package com.yuxuan.lingo.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BritishPronunciationDataTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyPayloadsWithoutBritishPronunciationRemainReadable() {
        val json = """{"id":"car","word":"car","pronunciationUs":"/kɑr/","partOfSpeech":"n.","meaningZh":"汽车","difficulty":1,"rank":1}"""
        val word = WordJsonCodec.decode(json)
        assertEquals("", word.pronunciationUk)
        assertEquals("/kɑr/", word.pronunciationUs)
    }

    @Test fun packagedBritishReadingsAreRealSeparateDataAndMissingReadingsRemainEmpty() = runBlocking {
        val name = "british-test-${UUID.randomUUID()}.db"
        try {
            RoomLearningRepository(context, databaseName = name).use { repository ->
                val words = repository.snapshot().words.associateBy { it.word }
                for (spelling in listOf("car", "bath", "schedule")) {
                    val word = requireNotNull(words[spelling])
                    assertTrue(word.pronunciationUk.isNotBlank())
                    assertNotEquals(word.pronunciationUs, word.pronunciationUk)
                }
                assertEquals("/riːd/, /red/", requireNotNull(words["read"]).pronunciationUk)
                val readForm = requireNotNull(words["read"]).wordForms.first { it.form == "read" }
                assertTrue(readForm.label.contains("过去式") && readForm.label.contains("过去分词"))
                assertEquals("sheep", requireNotNull(words["sheep"]).wordForms.single().form)
                val manifest = context.assets.open("dictionary_sources.json").bufferedReader().use { JSONObject(it.readText()) }
                assertEquals(manifest.getInt("wordCount"), words.size)
                assertTrue(words.values.count { it.pronunciationUk.isNotBlank() } > 18_000)
            }
        } finally { context.deleteDatabase(name) }
    }
}
