package com.yuxuan.lingo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real on-device migration and dictionary replacement, using disposable v1 databases. */
@RunWith(AndroidJUnit4::class)
class LearningMigrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationAndDictionaryRefreshRetainEveryProfileProgressAndLogField() = runBlocking {
        val name = "lingo-v1-migration-${UUID.randomUUID()}.db"
        val payload = firstAssetWord()
        val id = payload.getString("id")
        val expectedMeaning = payload.getString("meaningZh")
        payload.put("meaningZh", "升级前的旧词条释义")
        createV1Database(name, payload)
        try {
            RoomLearningRepository(context, clock = { 999L }, databaseName = name).use { repository ->
                val upgraded = repository.snapshot()
                assertEquals("语萱", upgraded.profile.name)
                assertEquals(35, upgraded.profile.dailyGoal)
                assertEquals(1.8, upgraded.profile.level, .0001)
                assertEquals(4L, upgraded.profile.totalAnswers)
                assertEquals(100L, upgraded.profile.createdAt)
                assertEquals(4L, upgraded.profile.resetCalibrationSinceSequence)
                val progress = upgraded.progress.single()
                assertEquals(id, progress.wordId)
                assertEquals(3, progress.correctCount)
                assertEquals(1, progress.incorrectCount)
                assertEquals(1, progress.consecutiveKnown)
                assertEquals(101L, progress.firstSeenAt)
                assertEquals(104L, progress.lastReviewedAt)
                assertEquals(102L, progress.lastUnknownAt)
                assertEquals(104L, progress.knownStreakStartedAt)
                assertEquals(null, progress.masteredAt)
                assertEquals(900L, progress.nextReviewAt)
                assertEquals(4L, progress.lastAnsweredSequence)
                assertEquals(7L, progress.retryAfterSequence)
                assertEquals(listOf(true, false, true, true), upgraded.logs.map { it.known })
                assertEquals((1L..4L).toList(), upgraded.logs.map { it.sequence })
                assertEquals(expectedMeaning, upgraded.words.single { it.id == id }.meaningZh)
                assertTrue(upgraded.words.size > 1)
                assertTrue(requireNotNull(upgraded.calibration).isComplete)
                assertTrue(requireNotNull(upgraded.calibration).wordIds.isEmpty())
                assertNotEquals(id, repository.nextWord()?.id)
            }
            val database = Room.databaseBuilder(context, LearningDatabase::class.java, name)
                .addMigrations(LearningDatabase.MIGRATION_1_2, LearningDatabase.MIGRATION_2_3, LearningDatabase.MIGRATION_3_4).build()
            try {
                val manifest = context.assets.open("dictionary_sources.json").bufferedReader().use { JSONObject(it.readText()) }
                assertEquals(manifest.getString("vocabularySha256"), database.learningDao().metadata("vocabularySha256"))
                assertEquals(4L, database.learningDao().profile()?.resetCalibrationSinceSequence)
                assertEquals(4, database.learningDao().logs().size)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun hashChangeRefreshesExistingRowsAndAddsWordsWithoutForeignKeyCascade() = runBlocking {
        val name = "lingo-upsert-${UUID.randomUUID()}.db"
        var wordId = ""
        try {
            RoomLearningRepository(context, databaseName = name).use { repository ->
                wordId = requireNotNull(repository.nextWord()).id
                repository.answer(wordId, known = false)
            }
            val database = Room.databaseBuilder(context, LearningDatabase::class.java, name)
                .addMigrations(LearningDatabase.MIGRATION_1_2, LearningDatabase.MIGRATION_2_3, LearningDatabase.MIGRATION_3_4).build()
            try {
                val dao = database.learningDao()
                val word = dao.words().single { it.id == wordId }
                val changed = JSONObject(word.payload).put("meaningZh", "过期资料")
                dao.upsertWords(listOf(word.copy(payload = changed.toString())))
                // This assertion catches the REPLACE/FK-CASCADE data-loss bug directly.
                assertEquals(1, dao.progress().size)
                assertEquals(1, dao.logs().size)
                dao.saveMetadata(AppMetadataEntity("vocabularySha256", "older-asset-version"))
            } finally { database.close() }
            RoomLearningRepository(context, databaseName = name).use { repository ->
                val refreshed = repository.snapshot()
                assertNotEquals("过期资料", refreshed.words.single { it.id == wordId }.meaningZh)
                assertEquals(1, refreshed.progress.single().incorrectCount)
                assertEquals(1, refreshed.logs.size)
                assertFalse(refreshed.logs.single().known)
                assertEquals(1L, refreshed.profile.totalAnswers)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun exampleAttributionDecodesAndLegacyExamplesRemainCompatible() {
        val payload = firstAssetWord()
        payload.put("examples", JSONArray().put(JSONObject().put("en", "An example.").put("zh", "例句。")
            .put("attribution", "Tatoeba / CC BY 2.0 FR / author").put("sourceUrl", "https://tatoeba.org/en/sentences/show/1")))
        val example = WordJsonCodec.decode(payload.toString()).examples.single()
        assertTrue(example.attribution.contains("CC BY"))
        assertTrue(example.sourceUrl.startsWith("https://tatoeba.org/"))
        payload.getJSONArray("examples").getJSONObject(0).remove("attribution")
        payload.getJSONArray("examples").getJSONObject(0).remove("sourceUrl")
        assertEquals("", WordJsonCodec.decode(payload.toString()).examples.single().attribution)
        assertEquals("", WordJsonCodec.decode(payload.toString()).examples.single().sourceUrl)
    }

    @Test fun migrationTwoToThreeAddsTimeTableWithoutChangingV2LearningData() = runBlocking {
        val name = "lingo-v2-time-migration-${UUID.randomUUID()}.db"
        createV1Database(name, firstAssetWord())
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE user_profile ADD COLUMN resetCalibrationSinceSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE user_profile SET resetCalibrationSinceSequence = 2")
                db.execSQL("CREATE TABLE app_metadata (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("INSERT INTO app_metadata VALUES('migrationTest', 'preserved-v2-data')")
                db.version = 2
            }
            val database = Room.databaseBuilder(context, LearningDatabase::class.java, name)
                .addMigrations(LearningDatabase.MIGRATION_1_2, LearningDatabase.MIGRATION_2_3, LearningDatabase.MIGRATION_3_4).build()
            try {
                val dao = database.learningDao()
                assertEquals(emptyList<DailyStudyTimeEntity>(), dao.studyTimes())
                assertEquals("语萱", dao.profile()?.name)
                assertEquals(4L, dao.profile()?.totalAnswers)
                assertEquals(2L, dao.profile()?.resetCalibrationSinceSequence)
                assertEquals("preserved-v2-data", dao.metadata("migrationTest"))
                assertEquals(4, dao.logs().size)
                assertEquals(3, dao.progress().single().correctCount)
                assertEquals(1, dao.progress().single().incorrectCount)
                dao.insertStudyTimeIfAbsent(DailyStudyTimeEntity("2026-10-23", 0L))
                dao.addStudyTime("2026-10-23", 12_345L)
                assertEquals(12_345L, dao.studyTimes().single().elapsedMillis)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationThreeToFourPreservesTimeAndHistoryAndCreatesIndependentDiagnosticTables() = runBlocking {
        val name = "lingo-v3-calibration-migration-${UUID.randomUUID()}.db"
        createV1Database(name, firstAssetWord())
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("ALTER TABLE user_profile ADD COLUMN resetCalibrationSinceSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE app_metadata (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE TABLE daily_study_time (date TEXT NOT NULL, elapsedMillis INTEGER NOT NULL, PRIMARY KEY(date))")
                db.execSQL("INSERT INTO daily_study_time VALUES('2026-10-23', 98765)")
                db.version = 3
            }
            RoomLearningRepository(context, clock = { 999L }, databaseName = name).use { repository ->
                val migrated = repository.snapshot()
                assertEquals(4L, migrated.profile.totalAnswers)
                assertEquals(4, migrated.logs.size)
                assertEquals(1, migrated.progress.single().incorrectCount)
                assertEquals(98_765L, migrated.studyTimes.single().elapsedMillis)
                assertTrue(requireNotNull(migrated.calibration).isComplete)
                assertTrue(requireNotNull(migrated.calibration).wordIds.isEmpty())
                assertTrue(requireNotNull(migrated.calibration).answers.isEmpty())
                assertNotEquals(null, repository.nextWord())
                assertEquals(migrated, repository.snapshot())
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun firstAssetWord(): JSONObject = context.assets.open("vocabulary.json").bufferedReader()
        .use { JSONObject(WordJsonCodec.readVocabulary(it).first().payload) }

    private fun createV1Database(name: String, word: JSONObject) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE words (id TEXT NOT NULL PRIMARY KEY, difficulty INTEGER NOT NULL, frequencyRank INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_words_difficulty ON words (difficulty)")
            db.execSQL("CREATE INDEX index_words_frequencyRank ON words (frequencyRank)")
            db.execSQL("CREATE TABLE user_profile (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, dailyGoal INTEGER NOT NULL, level REAL NOT NULL, totalAnswers INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE review_progress (wordId TEXT NOT NULL PRIMARY KEY, correctCount INTEGER NOT NULL, incorrectCount INTEGER NOT NULL, consecutiveKnown INTEGER NOT NULL, firstSeenAt INTEGER, lastReviewedAt INTEGER, lastUnknownAt INTEGER, knownStreakStartedAt INTEGER, masteredAt INTEGER, nextReviewAt INTEGER NOT NULL, lastAnsweredSequence INTEGER NOT NULL, retryAfterSequence INTEGER NOT NULL, FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE answer_logs (sequence INTEGER NOT NULL PRIMARY KEY, wordId TEXT NOT NULL, known INTEGER NOT NULL, answeredAt INTEGER NOT NULL, FOREIGN KEY(wordId) REFERENCES words(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_answer_logs_wordId ON answer_logs(wordId)")
            db.execSQL("CREATE INDEX index_answer_logs_answeredAt ON answer_logs(answeredAt)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42, '2b1a89565b174b7ccdcd92eeb571fe1a')")
            val id = word.getString("id")
            db.execSQL("INSERT INTO words VALUES (?, ?, ?, ?)", arrayOf(id, word.getInt("difficulty"), word.getInt("rank"), word.toString()))
            db.execSQL("INSERT INTO user_profile VALUES(1, '语萱', 35, 1.8, 4, 100)")
            db.execSQL("INSERT INTO review_progress VALUES(?, 3, 1, 1, 101, 104, 102, 104, NULL, 900, 4, 7)", arrayOf(id))
            (1L..4L).forEach { sequence ->
                db.execSQL("INSERT INTO answer_logs VALUES(?, ?, ?, ?)", arrayOf(sequence, id, if (sequence == 2L) 0 else 1, 100 + sequence))
            }
            db.version = 1
        }
    }
}
