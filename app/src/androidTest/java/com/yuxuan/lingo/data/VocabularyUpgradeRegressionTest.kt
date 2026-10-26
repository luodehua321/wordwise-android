package com.yuxuan.lingo.data

import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VocabularyUpgradeRegressionTest {
    @Test fun preparedRefreshPreservesEveryLearnerTableAndRestoresWordPayload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upgrade-${UUID.randomUUID()}.db"
        val filesBefore = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("vocabulary-upgrade-") }.toSet()
        try {
            val before = RoomLearningRepository(context, databaseName = name).use { repository ->
                val word = requireNotNull(repository.nextWord())
                repository.answer(word.id, false)
                repository.markLearned(word.id)
                repository.recordStudyTime(System.currentTimeMillis() - 5_000, 3_000)
                repository.snapshot()
            }
            val id = before.progress.single().wordId
            val db = Room.databaseBuilder(context, LearningDatabase::class.java, name).build()
            try {
                val dao = db.learningDao()
                val old = requireNotNull(dao.word(id))
                dao.upsertWords(listOf(old.copy(payload = JSONObject(old.payload).put("meaningZh", "旧释义").toString())))
                dao.saveMetadata(AppMetadataEntity("vocabularySha256", "outdated-test-vocabulary"))
            } finally { db.close() }
            val started = SystemClock.elapsedRealtime()
            RoomLearningRepository(context, databaseName = name).use { repository ->
                val after = repository.snapshot()
                val elapsed = SystemClock.elapsedRealtime() - started
                assertEquals(before.profile, after.profile)
                assertEquals(before.progress, after.progress)
                assertEquals(before.logs, after.logs)
                assertEquals(before.studyTimes, after.studyTimes)
                assertEquals(before.excludedWordIds, after.excludedWordIds)
                assertEquals(before.calibration, after.calibration)
                assertEquals(before.words.first { it.id == id }, after.words.first { it.id == id })
                println("LINGO_UPGRADE_BENCHMARK readyMillis=$elapsed preservedLogs=${after.logs.size}")
            }
            assertTrue(filesBefore.containsAll(context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("vocabulary-upgrade-") }.toSet()))
        } finally { context.deleteDatabase(name) }
    }
}
