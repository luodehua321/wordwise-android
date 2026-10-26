package com.yuxuan.lingo.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

/** Uses a disposable database; the learner's real database is never opened by these tests. */
@RunWith(AndroidJUnit4::class)
class RoomLearningRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: RoomLearningRepository
    private lateinit var databaseName: String
    private var now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "lingo-test-${UUID.randomUUID()}.db"
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun manuallyLearnedWordsStayExcludedAfterReopenAndVocabularyRefresh() = runBlocking {
        val first = requireNotNull(repository.nextWord())
        repository.answer(first.id, false)
        val before = repository.snapshot()
        repository.markLearned(first.id)
        repository.markLearned(first.id)
        val marked = repository.snapshot()
        assertTrue(first.id in marked.excludedWordIds)
        assertEquals(before.logs, marked.logs)
        assertEquals(before.progress, marked.progress)
        assertEquals(before.profile, marked.profile)
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try { database.learningDao().saveMetadata(AppMetadataEntity("vocabularySha256", "old")) }
        finally { database.close() }
        now += 86_400_000L
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertTrue(first.id in repository.snapshot().excludedWordIds)
        assertNull(repository.nextWord(reviewOnly = true))
        assertTrue(repository.nextWord()?.id != first.id)
        repository.unmarkLearned(first.id)
        assertTrue(first.id !in repository.snapshot().excludedWordIds)
        assertEquals(before.logs, repository.snapshot().logs)
        assertEquals(before.progress, repository.snapshot().progress)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertTrue(first.id !in repository.snapshot().excludedWordIds)
        assertEquals(first.id, repository.nextWord(reviewOnly = true)?.id)
    }

    @Test fun installationsHaveIndependentPersistedRandomSeeds() = runBlocking {
        val first = repository.snapshot()
        val otherName = "seed-test-${UUID.randomUUID()}.db"
        try {
            RoomLearningRepository(context, clock = { now }, databaseName = otherName).use { other ->
                assertTrue(first.calibration!!.seed != other.snapshot().calibration!!.seed)
                val selected = other.nextWord()
                assertEquals(selected, other.nextWord())
            }
        } finally { context.deleteDatabase(otherName) }
        val selected = repository.nextWord()
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(first.calibration!!.seed, repository.snapshot().calibration!!.seed)
        assertEquals(selected, repository.nextWord())
    }

    @Test fun importIsAtomicUnderConcurrentFirstReads() = runBlocking {
        val reads = coroutineScope {
            List(8) { async { repository.snapshot() } }.awaitAll()
        }
        val initial = reads.first()
        assertTrue(initial.words.isNotEmpty())
        assertEquals(3.0, initial.profile.level, 0.0)
        assertEquals(3, requireNotNull(repository.nextWord()).difficulty)
        assertEquals(initial.words.size, initial.words.map { it.id }.toSet().size)
        reads.forEach { snapshot ->
            assertEquals(initial.words, snapshot.words)
            assertTrue(snapshot.progress.isEmpty())
            assertTrue(snapshot.logs.isEmpty())
            assertEquals(0L, snapshot.profile.totalAnswers)
            assertEquals(now, snapshot.profile.createdAt)
        }
    }

    @Test fun recognitionCooldownAndRetirementPersistAndUndoRestoresPriorState() = runBlocking {
        val first = requireNotNull(repository.nextWord())
        repository.answer(first.id, true)
        assertEquals(now + 3_600_000L, repository.snapshot().progress.single().nextReviewAt)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertFalse(repository.snapshot().progress.single().isMastered)
        now += 3_600_000L
        val second = repository.answer(first.id,true)
        assertTrue(repository.snapshot().progress.single().isMastered)
        assertTrue(repository.nextWord()?.id != first.id)
        repository.undoAnswer(second)
        assertFalse(repository.snapshot().progress.single().isMastered)
        assertEquals(1,repository.snapshot().progress.single().correctCount)
    }

    @Test fun fiveMinuteReviewAppliesToPersistedLegacySchedulesAfterReopen() = runBlocking {
        val start = now
        val first = requireNotNull(repository.nextWord())
        repository.answer(first.id, known = false)
        val saved = repository.snapshot()
        assertEquals(start + 300_000L, saved.progress.single().nextReviewAt)
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            database.learningDao().saveProgress(ReviewProgressEntity.fromModel(
                saved.progress.single().copy(nextReviewAt = start + 86_400_000L, retryAfterSequence = 9999)))
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        now = start + 299_999L
        assertNull(repository.nextWord(reviewOnly = true))
        now++
        assertEquals(first.id, repository.nextWord(reviewOnly = true)?.id)
        val reopened = repository.snapshot()
        assertEquals(saved.profile, reopened.profile)
        assertEquals(saved.logs, reopened.logs)
        assertEquals(saved.calibration, reopened.calibration)
        assertEquals(start + 86_400_000L, reopened.progress.single().nextReviewAt)
        repository.answer(first.id, known = true)
        assertEquals(now + 3_600_000L, repository.snapshot().progress.single().nextReviewAt)
    }

    @Test fun sameLevelReviewsKeepPositiveWeightAndEarnSmallMasteryRewardAfterReopen() = runBlocking {
        val first = requireNotNull(repository.nextWord()) // First diagnostic word and profile are both level 3.
        repository.answer(first.id, known = false)
        now += 300_000L
        assertEquals(3.0, repository.snapshot().profile.level, 0.0)
        assertEquals(first.id, repository.nextWord(reviewOnly = true)?.id)
        assertEquals(1, com.yuxuan.lingo.domain.LearningScheduler({ now })
            .dueReviewCount(repository.snapshot(), reviewOnly = true))
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(first.id, repository.nextWord(reviewOnly = true)?.id)
        val once = repository.answer(first.id, known = true)
        assertEquals(3.0, once.profile.level, 0.0)
        now += 3_600_000L
        val learned = repository.answer(first.id, known = true)
        assertTrue(learned.progress.isMastered)
        assertEquals(3.002, learned.profile.level, 1e-12)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(learned.profile, repository.snapshot().profile)
    }

    @Test fun preparedVocabularyContainsEveryCompleteSourceRecord() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val snapshot = repository.snapshot()
        Log.i("LingoStartup", "Fresh prepared repository: ${SystemClock.elapsedRealtime() - started} ms; ${snapshot.words.size} words")
        val source = context.assets.open("dictionary_sources.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(source.getInt("wordCount"), snapshot.words.size)
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            database.openHelper.readableDatabase.query("SELECT payload FROM words ORDER BY difficulty, frequencyRank, id").use { cursor ->
                var index = 0
                while (cursor.moveToNext()) {
                    // Every field, including Chinese, sentences and attribution, survives
                    // precompilation. Compare one decoded payload at a time to bound RAM.
                    assertEquals(WordJsonCodec.decode(cursor.getString(0)), snapshot.words[index++])
                }
                assertEquals(snapshot.words.size, index)
            }
        } finally { database.close() }
    }

    @Test fun preparedAssetChecksumRejectsModifiedBytes() {
        var rejected = false
        try {
            WordJsonCodec.checkedStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "0".repeat(64)).use {
                val buffer = ByteArray(16)
                while (it.read(buffer) != -1) { /* consume the whole copied asset */ }
            }
        } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun dictionaryHashUpdatePreservesAllLearningAndCalibrationState() = runBlocking {
        val first = requireNotNull(repository.nextWord())
        repository.answerCalibration(first.id, known = false)
        repository.answer(requireNotNull(repository.nextWord()).id, known = false)
        repository.updateProfile("保留记录", 25)
        repository.recordStudyTime(now, 9_000L)
        val before = repository.snapshot()
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            val smartphone = requireNotNull(dao.word("smartphone"))
            // Simulate the former unranked-word bug in an installed dictionary.
            // Reimport must update the word without resetting learner state.
            dao.upsertWords(listOf(smartphone.copy(difficulty = 10,
                payload = JSONObject(smartphone.payload).put("difficulty", 10).toString())))
            dao.saveMetadata(AppMetadataEntity("vocabularySha256", "previous-content-version"))
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val started = SystemClock.elapsedRealtime()
        assertEquals(before, repository.snapshot())
        assertEquals(2, repository.snapshot().words.first { it.id == "smartphone" }.difficulty)
        Log.i("LingoStartup", "Legacy JSON vocabulary refresh: ${SystemClock.elapsedRealtime() - started} ms")
        Unit
    }

    @Test fun nextWordDoesNotRecordAnAnswerAndReopeningPreservesAllState() = runBlocking {
        val initial = repository.snapshot()
        val card = requireNotNull(repository.nextWord())
        assertEquals(card, repository.nextWord())
        assertEquals(initial, repository.snapshot())

        repeat(20) {
            repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = false)
        }
        val learningCard = requireNotNull(repository.nextWord())
        val answer = repository.answer(learningCard.id, known = false)
        assertFalse(answer.log.known)
        assertEquals(1L, answer.log.sequence)
        assertTrue(answer.progress.isMistake)
        repository.updateProfile("语萱", 35)
        val saved = repository.snapshot()
        assertEquals(1L, saved.profile.totalAnswers)
        assertEquals(1, saved.logs.size)
        assertEquals(1, saved.progress.single().incorrectCount)
        assertEquals("语萱", saved.profile.name)
        assertEquals(35, saved.profile.dailyGoal)
        assertNull(repository.nextWord(reviewOnly = true))

        repository.close()
        now += 86_400_000
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(saved, repository.snapshot())
        assertNotNull(repository.nextWord())
        assertEquals(saved, repository.snapshot())
    }

    @Test fun calibrationUnknownsAreTwentyUniqueAndNeverEnterLearningHistory() = runBlocking {
        val initial = repository.snapshot()
        val selected = mutableListOf<String>()
        repeat(20) { index ->
            val card = requireNotNull(repository.nextWord())
            selected += card.id
            assertEquals(card, repository.nextWord())
            repository.answerCalibration(card.id, known = false)
            val after = repository.snapshot()
            assertEquals(index + 1, after.calibration?.answers?.size)
            assertTrue(after.logs.isEmpty())
            assertTrue(after.progress.isEmpty())
            assertEquals(initial.profile.totalAnswers, after.profile.totalAnswers)
            assertNull(repository.nextWord(reviewOnly = true))
        }
        assertEquals(20, selected.toSet().size)
        assertFalse(requireNotNull(repository.snapshot().calibration).let { !it.isComplete })
        assertFalse(selected.contains(repository.nextWord()?.id))
    }

    @Test fun partialFirstUseCalibrationResumesAndCompletionNeverRestartsAcrossReopen() = runBlocking {
        val initial = repository.snapshot()
        val first = requireNotNull(repository.nextWord())
        repository.answerCalibration(first.id, known = false)
        repository.recordStudyTime(now, 13_000L)
        val saved = repository.snapshot()
        val next = requireNotNull(repository.nextWord())
        assertTrue(first.id != next.id)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(saved, repository.snapshot())
        assertEquals(next.id, repository.nextWord()?.id)
        repeat(19) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = false) }
        val completed = repository.snapshot()
        assertTrue(requireNotNull(completed.calibration).isComplete)
        assertEquals(initial.calibration?.id, completed.calibration?.id)
        repository.answer(requireNotNull(repository.nextWord()).id, known = true)
        val learned = repository.snapshot()
        repeat(2) {
            repository.close()
            now += 86_400_000L
            repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
            val reopened = repository.snapshot()
            assertEquals(learned, reopened)
            assertTrue(requireNotNull(reopened.calibration).isComplete)
            assertEquals(completed.calibration?.id, reopened.calibration?.id)
            assertNotNull(repository.nextWord())
        }
    }

    @Test fun staleDiagnosticAnswerIsRejectedWithoutAdvancingTheQueue() = runBlocking {
        val first = requireNotNull(repository.nextWord())
        repository.answerCalibration(first.id, known = false)
        val saved = repository.snapshot()
        var rejected = false
        try { repository.answerCalibration(first.id, known = true) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(saved, repository.snapshot())
    }

    @Test fun aMissingOrMasteredQueuedProbeIsReplacedPersistentlyBeforeItCanBeAnswered() = runBlocking {
        val initial = repository.snapshot()
        val sessionId = requireNotNull(initial.calibration).id
        assertNotNull(repository.nextWord())
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            dao.updateCalibrationWordIds(sessionId, JSONArray(listOf("removed-dictionary-word")).toString())
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val replacement = requireNotNull(repository.nextWord())
        assertEquals(replacement.id, repository.snapshot().calibration?.nextWordId)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(replacement.id, repository.nextWord()?.id)
        repository.answerCalibration(replacement.id, known = false)

        val queued = requireNotNull(repository.nextWord())
        repository.close()
        val masteredDatabase = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            masteredDatabase.learningDao().saveProgress(ReviewProgressEntity.fromModel(
                com.yuxuan.lingo.domain.ReviewProgress(queued.id, correctCount = 2),
            ))
        } finally { masteredDatabase.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val unmastered = requireNotNull(repository.nextWord())
        assertTrue(queued.id != unmastered.id)
        assertEquals(unmastered.id, repository.snapshot().calibration?.nextWordId)
        repository.answerCalibration(unmastered.id, known = true)
        assertEquals(2, repository.snapshot().calibration?.answers?.size)
    }

    @Test fun calibrationFinishesWhenExcludedAndMasteredWordsLeaveFewerThanTwentyEligibleProbes() = runBlocking {
        val initial = repository.snapshot()
        val available = initial.words.take(3)
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            dao.saveMetadata(AppMetadataEntity("excludedWordIds", JSONArray(
                initial.words.drop(3).map { it.id },
            ).toString()))
            dao.saveProgress(ReviewProgressEntity.fromModel(
                com.yuxuan.lingo.domain.ReviewProgress(available.last().id, correctCount = 2),
            ))
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val selected = mutableSetOf<String>()
        repeat(2) {
            val word = requireNotNull(repository.nextWord())
            assertTrue(selected.add(word.id))
            assertTrue(word.id in available.take(2).map { it.id })
            repository.answerCalibration(word.id, known = false)
        }
        val completed = repository.snapshot()
        assertEquals(2, completed.calibration?.targetSamples)
        assertTrue(requireNotNull(completed.calibration).isComplete)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(completed, repository.snapshot())
        assertTrue(requireNotNull(repository.snapshot().calibration).isComplete)
        repository.unmarkLearned(initial.words[3].id)
        val restored = repository.snapshot()
        assertEquals(2, restored.calibration?.targetSamples)
        assertEquals(completed.calibration?.answers, restored.calibration?.answers)
        assertTrue(requireNotNull(restored.calibration).isComplete)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(restored, repository.snapshot())
    }

    @Test fun anOldLevelTenStartingProbeIsReplacedWithAnAnswerableStartingWord() = runBlocking {
        val initial = repository.snapshot()
        val session = requireNotNull(initial.calibration)
        val tooHard = initial.words.first { it.difficulty == 10 }
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            database.learningDao().updateCalibrationWordIds(session.id,
                JSONArray(listOf(tooHard.id)).toString())
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val replacement = requireNotNull(repository.nextWord())
        assertEquals(3, replacement.difficulty)
        assertEquals(replacement.id, repository.snapshot().calibration?.nextWordId)
        repository.answerCalibration(replacement.id, known = true)
        assertEquals(listOf(replacement.id), repository.snapshot().calibration?.answers?.map { it.wordId })
    }

    @Test fun anIncompleteV12UpgradeCalibrationDoesNotInterruptAnExistingLearner() = runBlocking {
        val first = requireNotNull(repository.nextWord())
        repository.answer(first.id, known = false)
        repository.recordStudyTime(now, 17_000L)
        val before = repository.snapshot()
        val oldQueue = before.words.take(20).map { it.id }
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            dao.insertCalibration(CalibrationSessionEntity("v12-upgrade-only", now, 15L,
                JSONArray(oldQueue).toString(), before.profile.totalAnswers))
            dao.insertCalibrationAnswer(CalibrationAnswerEntity("v12-upgrade-only", 0, oldQueue.first(), false, now))
            dao.saveMetadata(AppMetadataEntity("activeCalibrationId", "v12-upgrade-only"))
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val after = repository.snapshot()
        assertTrue(requireNotNull(after.calibration).isComplete)
        assertTrue(requireNotNull(after.calibration).wordIds.isEmpty())
        assertTrue(after.calibration?.id != "v12-upgrade-only")
        assertEquals(before.profile, after.profile)
        assertEquals(before.logs, after.logs)
        assertEquals(before.progress, after.progress)
        assertEquals(before.studyTimes, after.studyTimes)
        assertNotNull(repository.nextWord())
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(after, repository.snapshot())
    }

    @Test fun undoAnswerRestoresLogsProgressAndProfileExactly() = runBlocking {
        repeat(20) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = true) }
        val before = repository.snapshot()
        val card = requireNotNull(repository.nextWord())
        val result = repository.answer(card.id, known = false)
        assertFalse(repository.snapshot().progress.isEmpty())
        assertTrue("A persisted learning answer must respect the adaptive update bound",
            kotlin.math.abs(result.profile.level - before.profile.level) <= .120001)
        repository.undoAnswer(result)
        assertEquals(before, repository.snapshot())
        // Correct the answer. The reverted response must not remain as hidden model evidence.
        val retry = repository.answer(card.id, known = true)
        val expected = com.yuxuan.lingo.domain.LearningScheduler({ now })
            .recordAnswer(before, card.id, known = true)
        assertEquals(expected.profile, retry.profile)
        assertEquals(expected.log, retry.log)
        // A second undo of a new first answer must remove the newly created progress row.
        repository.undoAnswer(retry)
        assertEquals(before, repository.snapshot())
    }

    @Test fun newKnownAndFirstReviewMasteryPersistDistanceRewardsAcrossReopenAndUndo() = runBlocking {
        repeat(20) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = true) }
        val before = repository.snapshot()
        val card = requireNotNull(repository.nextWord())
        fun rewarded(level: Double) = (level + maxOf(.002, .02 * kotlin.math.abs(card.difficulty - level))).coerceAtMost(10.0)
        val first = repository.answer(card.id, true)
        assertEquals(rewarded(before.profile.level), first.profile.level, 1e-12)
        assertFalse(first.progress.isMastered)
        now += 3_600_000L
        val second = repository.answer(card.id, true)
        assertEquals(rewarded(first.profile.level), second.profile.level, 1e-12)
        assertTrue(second.progress.isMastered)
        val mastered = repository.snapshot()
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(mastered, repository.snapshot())
        repository.undoAnswer(second)
        val undone = repository.snapshot()
        assertEquals(first.profile, undone.profile)
        assertFalse(undone.progress.single { it.wordId == card.id }.isMastered)
        val corrected = repository.answer(card.id, true)
        assertEquals(second.profile, corrected.profile)
        assertEquals(second.progress, corrected.progress)
        val repeated = repository.answer(card.id, true)
        assertEquals(corrected.profile.level, repeated.profile.level, 0.0)
    }

    @Test fun anUnknownReviewOnlyRewardsItsSecondRecognition() = runBlocking {
        repeat(20) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = true) }
        val card = requireNotNull(repository.nextWord())
        val unknown = repository.answer(card.id, false)
        now += 300_000L
        val firstKnown = repository.answer(card.id, true)
        assertEquals(unknown.profile.level, firstKnown.profile.level, 0.0)
        assertFalse(firstKnown.progress.isMastered)
        now += 3_600_000L
        val secondKnown = repository.answer(card.id, true)
        val weight = maxOf(.002, .02 * kotlin.math.abs(card.difficulty - firstKnown.profile.level))
        assertEquals((firstKnown.profile.level + weight).coerceAtMost(10.0), secondKnown.profile.level, 1e-12)
        assertTrue(secondKnown.progress.isMastered)
    }

    @Test fun reopeningRestoresMissingProgressFromLogsWithoutRepeatingNewWordsOrLosingCooldown() = runBlocking {
        repeat(20) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = true) }
        val source = repository.snapshot()
        val diagnosticIds = requireNotNull(source.calibration).wordIds.toSet()
        val cards = source.words.filterNot { it.id in diagnosticIds }.take(3)
        val unknown = cards[0]
        val knownOnce = cards[1]
        val mastered = cards[2]
        repository.answer(unknown.id, known = false)
        repository.answer(knownOnce.id, known = true)
        repository.answer(mastered.id, known = true)
        repository.answer(mastered.id, known = true)
        repository.updateProfile("恢复记录", 36)
        repository.recordStudyTime(now, 7_000L)
        val before = repository.snapshot()
        repository.close()
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            dao.deleteProgress(unknown.id)
            dao.deleteProgress(mastered.id)
            // Restrict the learning pool to demonstrate that a lost progress row would
            // otherwise reappear immediately as a new card, before its cooldown.
            dao.saveMetadata(AppMetadataEntity("excludedWordIds", JSONArray(
                source.words.filterNot { word -> cards.any { it.id == word.id } }.map { it.id },
            ).toString()))
        } finally { database.close() }
        now += 60_000L
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val restored = repository.snapshot()
        assertEquals(before.progress.associateBy { it.wordId }, restored.progress.associateBy { it.wordId })
        assertEquals(before.profile, restored.profile)
        assertEquals(before.logs, restored.logs)
        assertEquals(before.calibration, restored.calibration)
        assertEquals(before.studyTimes, restored.studyTimes)
        assertNull(repository.nextWord())
        assertNull(repository.nextWord(reviewOnly = true))
        now += 240_000L
        assertEquals(unknown.id, repository.nextWord(reviewOnly = true)?.id)
        val result = repository.answer(unknown.id, known = true)
        assertEquals(1, result.progress.incorrectCount)
        assertEquals(1, result.progress.correctCount)
        assertEquals(before.profile.level, result.profile.level, 0.0)
        repository.undoAnswer(result)
        assertEquals(restored.progress.associateBy { it.wordId }, repository.snapshot().progress.associateBy { it.wordId })
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val reopened = repository.snapshot()
        assertEquals(restored.copy(progress = restored.progress.sortedBy { it.wordId }),
            reopened.copy(progress = reopened.progress.sortedBy { it.wordId }))
    }

    @Test fun dictionaryRefreshRetiresRemovedEntriesWhilePreservingTheirLearningHistory() = runBlocking {
        val source = repository.snapshot()
        repository.close()
        val retiredId = "retired-dictionary-entry"
        val database = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            val dao = database.learningDao()
            val original = requireNotNull(dao.word(source.words.first().id))
            val payload = JSONObject(original.payload).put("id", retiredId).put("word", retiredId)
            dao.upsertWords(listOf(original.copy(id = retiredId, payload = payload.toString())))
            dao.insertLog(AnswerLogEntity(1L, retiredId, false, now))
            dao.saveProgress(ReviewProgressEntity.fromModel(
                com.yuxuan.lingo.domain.ReviewProgress(retiredId, incorrectCount = 1, lastReviewedAt = now),
            ))
            dao.saveProfile(UserProfileEntity.fromModel(source.profile.copy(totalAnswers = 1)))
            dao.saveMetadata(AppMetadataEntity("vocabularySha256", "previous-dictionary-version"))
        } finally { database.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        val refreshed = repository.snapshot()
        assertEquals(source.words, refreshed.words)
        assertFalse(refreshed.words.any { it.id == retiredId })
        assertEquals(retiredId, refreshed.logs.single().wordId)
        assertEquals(retiredId, refreshed.progress.single().wordId)
        assertEquals(1L, refreshed.profile.totalAnswers)
        now += 300_000L
        assertNull(repository.nextWord(reviewOnly = true))
        assertEquals(0, com.yuxuan.lingo.domain.LearningScheduler({ now }).dueReviewCount(refreshed, reviewOnly = true))
        repository.close()
        val saved = Room.databaseBuilder(context, LearningDatabase::class.java, databaseName).build()
        try {
            assertNotNull(saved.learningDao().word(retiredId))
            assertEquals(1, saved.learningDao().logs().size)
            assertEquals(1, saved.learningDao().progress().size)
        } finally { saved.close() }
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(refreshed, repository.snapshot())
    }

    @Test fun concurrentAnswersKeepLogProgressAndProfileConsistent() = runBlocking {
        val card = requireNotNull(repository.nextWord())
        coroutineScope {
            List(10) { async { repository.answer(card.id, known = false) } }.awaitAll()
        }
        val snapshot = repository.snapshot()
        assertEquals(10L, snapshot.profile.totalAnswers)
        assertEquals((1L..10L).toList(), snapshot.logs.map { it.sequence })
        assertTrue(snapshot.logs.all { it.wordId == card.id && !it.known })
        assertEquals(10, snapshot.progress.single().incorrectCount)
        assertEquals(10L, snapshot.progress.single().lastAnsweredSequence)
    }

    @Test fun undoPreservesProfileSettingsEditedAfterTheAnswer() = runBlocking {
        repeat(20) { repository.answerCalibration(requireNotNull(repository.nextWord()).id, known = true) }
        val before = repository.snapshot()
        val result = repository.answer(requireNotNull(repository.nextWord()).id, known = false)
        repository.updateProfile("改名后的学习者", 42)
        repository.undoAnswer(result)
        val restored = repository.snapshot()
        assertEquals(before.copy(profile = before.profile.copy(name = "改名后的学习者", dailyGoal = 42)), restored)
        repository.close()
        repository = RoomLearningRepository(context, clock = { now }, databaseName = databaseName)
        assertEquals(restored, repository.snapshot())
    }

    @Test fun staleRepeatedAndReplacedUndoResultsCannotCorruptTheAnswerSequence() = runBlocking {
        val card = requireNotNull(repository.nextWord())
        val first = repository.answer(card.id, known = false)
        val afterFirst = repository.snapshot()
        val second = repository.answer(card.id, known = true)
        val afterSecond = repository.snapshot()
        var rejected = false
        try { repository.undoAnswer(first) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("A result older than the latest answer must be rejected", rejected)
        assertEquals(afterSecond, repository.snapshot())

        repository.undoAnswer(second)
        assertEquals(afterFirst, repository.snapshot())
        rejected = false
        try { repository.undoAnswer(second) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Undoing the same result twice must not rewind history", rejected)
        assertEquals(afterFirst, repository.snapshot())

        val replacement = repository.answer(card.id, known = false)
        val afterReplacement = repository.snapshot()
        assertEquals(listOf(1L, 2L), afterReplacement.logs.map { it.sequence })
        assertEquals(2L, afterReplacement.profile.totalAnswers)
        rejected = false
        try { repository.undoAnswer(second) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Reusing a sequence does not make an old response valid again", rejected)
        assertEquals(afterReplacement, repository.snapshot())
        repository.undoAnswer(replacement)
        assertEquals(afterFirst, repository.snapshot())
    }
}
