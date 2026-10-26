package com.yuxuan.lingo

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.data.WordJsonCodec
import com.yuxuan.lingo.domain.*
import com.yuxuan.lingo.feedback.FeedbackPreferences
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.AppUiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Controls repository suspension to exercise states too short to catch with screenshot tests. */
@RunWith(AndroidJUnit4::class)
class MainViewModelStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun relatedWordsReceiveTheirOwnReadingsInTheFullDetailEntry() {
        val repository = ControlledRepository()
        val apple = repository.snapshotValue.words.single().copy(
            derivatives = listOf(Derivative("fruit", "水果")),
            synonyms = listOf(Synonym("fruit", "水果")),
            confusables = listOf(Confusable("fruit", "水果", "注意含义区别")),
        )
        repository.snapshotValue = repository.snapshotValue.copy(words = listOf(apple,
            Word("fruit", "fruit", "/fruːt/", "n.", "水果", 1, 2,
                pronunciationUk = "/fruːt/")))
        withModel(repository) { model ->
            awaitReady(model)
            main { model.dispatch(AppAction.OpenWord(model.state.value.currentWord!!)) }
            val detail = model.state.value.selectedWord!!
            for (note in listOf(detail.derivatives.single(), detail.synonyms.single(), detail.confusables.single())) {
                assertEquals("fruit", note.text)
                assertEquals("/fruːt/", note.americanIpa)
                assertEquals("/fruːt/", note.britishIpa)
                assertTrue(note.pronunciationExpected)
            }
            assertEquals(LearningLevel.describe(3.0).description,
                model.state.value.stats.currentLevelDescription)
        }
    }

    @Test fun mistakeRowsAreLightweightButOpeningDetailsKeepsTheCompleteEntry() {
        val repository = ControlledRepository()
        repository.snapshotValue = repository.snapshotValue.copy(progress = listOf(
            ReviewProgress("apple", incorrectCount = 1)))
        withModel(repository) { model ->
            awaitReady(model)
            val row = model.state.value.mistakes.single().word
            assertTrue(row.examples.isEmpty())
            main { model.dispatch(AppAction.OpenWord(row)) }
            assertEquals("I eat an apple.", model.state.value.selectedWord!!.examples.single().english)
            main { model.dispatch(AppAction.MarkLearned("apple")) }
            await { !model.state.value.isWorking && model.state.value.mistakes.isEmpty() }
            assertTrue("apple" in repository.snapshotValue.excludedWordIds)
            assertEquals("apple", model.state.value.selectedWord?.wordId)
            assertEquals("I eat an apple.", model.state.value.selectedWord!!.examples.single().english)
            assertEquals("我吃一个苹果。", model.state.value.selectedWord!!.examples.single().chinese)
            main { model.dispatch(AppAction.UnmarkLearned("apple")) }
            await { !model.state.value.isWorking && model.state.value.mistakes.size == 1 }
            assertTrue(repository.snapshotValue.excludedWordIds.isEmpty())
            assertEquals("I eat an apple.", model.state.value.selectedWord!!.examples.single().english)
            assertEquals("我吃一个苹果。", model.state.value.selectedWord!!.examples.single().chinese)
        }
    }

    @Test fun relationDetailsSeparateShapeCandidatesAndKeepSpecificConfusableNotes() {
        val repository = ControlledRepository()
        repository.snapshotValue = repository.snapshotValue.copy(words = listOf(
            repository.snapshotValue.words.single().copy(
                synonyms = listOf(
                    Synonym(" fruit ", " 水果 \n", " \n "),
                    Synonym("produce", " \n", " fresh fruit \n"),
                ),
                confusables = listOf(
                    Confusable(" apply ", " 申请 \n", " \n "),
                    Confusable("applet", " 小程序 ", " 形近对照（自动筛选，未验证学习者错误率）：位置6：∅ → t。请对照两词释义，不能仅凭相似拼写替换。 \n"),
                    Confusable("maple", " \n", " 注意词首与发音。 \n"),
                    Confusable(" eye ", " 眼睛 ", " 同音对照：发音相同，含义不同。 \n"),
                ),
            ),
            Word("applet", "applet", "/ˈæpɫət/", "n.", "小程序", 4, 3000,
                pronunciationUk = "/ˈæplət/"),
        ))
        withModel(repository) { model ->
            awaitReady(model)
            main { model.dispatch(AppAction.OpenWord(com.yuxuan.lingo.ui.WordUi("apple", "apple", "难度 1 / 10"))) }
            val detail = model.state.value.selectedWord!!
            assertEquals("fruit", detail.synonyms[0].text)
            assertEquals("水果", detail.synonyms[0].meaning)
            assertEquals("对应义项：fresh fruit", detail.synonyms[1].meaning)
            assertEquals("apply", detail.confusables[0].text)
            assertEquals("申请", detail.confusables[0].meaning)
            assertEquals(listOf("apply", "maple", "eye"), detail.confusables.map { it.text })
            assertEquals("注意词首与发音。", detail.confusables[1].meaning)
            assertEquals("眼睛", detail.confusables[2].meaning)
            assertEquals(listOf("applet"), detail.similarWords.map { it.text })
            assertTrue(detail.similarWords.single().meaning.contains("小程序"))
            assertTrue(detail.similarWords.single().pronunciationExpected)
            assertTrue(detail.similarWords.single().americanIpa.isNotBlank())
            assertFalse(detail.confusables.any { it.meaning.contains("形近对照") ||
                it.meaning.contains("自动筛选") || it.meaning.contains("同音对照：") })
        }
    }

    @Test fun preparedAppleKeepsCompleteExamplesAndRelationsWhenLearningIsToggled() {
        val assets = instrumentation.targetContext.assets
        val source = assets.open("dictionary_sources.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val apple = assets.open("prepared_vocabulary.bin").buffered(65_536).use {
            WordJsonCodec.readPreparedVocabulary(it, source.getString("vocabularySha256"), source.getInt("wordCount"))
                .single { word -> word.id == "apple" }
        }
        assertTrue("The packaged apple must include English examples", apple.examples.any { it.en.isNotBlank() })
        assertTrue("The packaged apple must include Chinese translations", apple.examples.any { it.zh.isNotBlank() })
        val repository = ControlledRepository()
        repository.snapshotValue = repository.snapshotValue.copy(
            words = listOf(apple), progress = listOf(ReviewProgress("apple", incorrectCount = 1)),
        )
        withModel(repository) { model ->
            awaitReady(model)
            val row = model.state.value.mistakes.single().word
            assertTrue(row.examples.isEmpty())
            assertTrue(row.synonyms.isEmpty())
            assertTrue(row.confusables.isEmpty())
            assertTrue(row.similarWords.isEmpty())
            main { model.dispatch(AppAction.OpenWord(row)) }
            fun assertCompleteDetails() {
                val detail = model.state.value.selectedWord!!
                assertEquals(apple.examples.map { it.en }, detail.examples.map { it.english })
                assertEquals(apple.examples.map { it.zh }, detail.examples.map { it.chinese })
                assertEquals(apple.examples.map { it.attribution }, detail.examples.map { it.attribution })
                assertEquals(apple.examples.map { it.sourceUrl }, detail.examples.map { it.sourceUrl })
                assertEquals(apple.synonyms.map { it.word }, detail.synonyms.map { it.text })
                assertEquals(apple.confusables.filterNot { it.note.trim().startsWith("形近对照") }
                    .map { it.word.trim() }, detail.confusables.map { it.text })
                assertEquals(apple.confusables.filter { it.note.trim().startsWith("形近对照") }
                    .map { it.word.trim() }, detail.similarWords.map { it.text })
                assertFalse(detail.confusables.any { it.meaning.contains("形近对照") ||
                    it.meaning.contains("自动筛选") || it.meaning.contains("同音对照：") })
            }
            assertCompleteDetails()
            main { model.dispatch(AppAction.MarkLearned("apple")) }
            await { !model.state.value.isWorking && "apple" in model.state.value.excludedWordIds }
            assertCompleteDetails()
            main { model.dispatch(AppAction.UnmarkLearned("apple")) }
            await { !model.state.value.isWorking && "apple" !in model.state.value.excludedWordIds }
            assertCompleteDetails()
        }
    }

    @Test fun legacyReviewLabelUsesTheSameFiveMinuteEligibilityAsTheScheduler() {
        val repository = ControlledRepository()
        val now = System.currentTimeMillis()
        repository.snapshotValue = repository.snapshotValue.copy(progress = listOf(ReviewProgress(
            wordId = "apple", incorrectCount = 1, lastReviewedAt = now - 300_001L,
            nextReviewAt = now + 86_400_000L, retryAfterSequence = 9999)))
        withModel(repository) { model ->
            awaitReady(model)
            assertEquals("待巩固 · 优先复习", model.state.value.mistakes.single().nextReviewLabel)
        }
    }

    @Test fun startupAndReviewModeRemainLoadingUntilTheirWordIsReady() {
        val repository = ControlledRepository()
        val firstWord = CompletableDeferred<Unit>()
        repository.nextGate = firstWord
        withModel(repository) { model ->
            await { repository.nextCalls == 1 }
            assertTrue("A refreshed snapshot is not yet a prepared word", model.state.value.isLoading)
            assertNull(model.state.value.currentWord)
            firstWord.complete(Unit)
            awaitReady(model)

            repository.snapshotValue = repository.snapshotValue.copy(progress = listOf(ReviewProgress(
                wordId = "apple", correctCount = 1, nextReviewAt = 0L,
                lastReviewedAt = System.currentTimeMillis() - 7_200_000L,
            )))
            repository.nextGate = CompletableDeferred()
            main { assertTrue(model.dispatch(AppAction.ReviewMistakes)) }
            await { repository.nextCalls == 2 }
            assertTrue(model.state.value.isLoading)
            assertTrue(model.state.value.reviewMode)
            assertEquals(0, model.state.value.dueCount)
            repository.nextGate?.complete(Unit)
            awaitReady(model)
            assertTrue(model.state.value.reviewMode)
        }
    }

    @Test fun wordPreparationFailureUnlocksRetry() {
        val repository = ControlledRepository().apply { failNext = true }
        withModel(repository) { model ->
            await { model.state.value.errorMessage != null && !model.state.value.isWorking }
            assertFalse(model.state.value.isLoading)
            val failure = model.state.value.errorMessage
            assertEquals("Storage diagnostics must not be exposed in the app", "暂时无法完成操作，请重试。", failure)
            main {
                model.setStudyForeground(false)
                model.setStudyForeground(true)
                assertFalse(model.refreshStats())
            }
            assertEquals("Returning to the app must preserve the retry state", failure, model.state.value.errorMessage)
            main { assertTrue(model.dispatch(AppAction.Retry)) }
            awaitReady(model)
            assertEquals(2, repository.nextCalls)
            assertNull(model.state.value.errorMessage)
        }
    }

    @Test fun anInFlightAnswerCannotBeSubmittedTwiceAndFailureDoesNotLeaveItLocked() {
        val repository = ControlledRepository()
        withModel(repository) { model ->
            awaitReady(model)
            repository.answerGate = CompletableDeferred()
            repository.failAnswer = true
            main {
                assertTrue(model.dispatch(AppAction.Answer(true)))
                assertFalse(model.dispatch(AppAction.Answer(true)))
                assertFalse(model.dispatch(AppAction.NextWord))
            }
            repository.answerGate?.complete(Unit)
            await { model.state.value.errorMessage != null && !model.state.value.isWorking }
            assertEquals(1, repository.answerCalls)
            assertEquals(0, repository.snapshotValue.logs.size)
            main { assertTrue(model.dispatch(AppAction.Retry)) }
            awaitReady(model)
            main { assertTrue(model.dispatch(AppAction.Answer(true))) }
            await { model.state.value.answerIsKnown == true && !model.state.value.isWorking }
            assertEquals(2, repository.answerCalls)
            assertEquals(1, repository.snapshotValue.logs.size)
        }
    }

    @Test fun failedUndoCanBeRetriedAndRestoresOneAnswerOnly() {
        val repository = ControlledRepository()
        withModel(repository) { model ->
            awaitReady(model)
            main { assertTrue(model.dispatch(AppAction.Answer(true))) }
            await { model.state.value.canUndo && !model.state.value.isWorking }
            repository.failUndo = true
            main { assertTrue(model.dispatch(AppAction.UndoAnswer)) }
            await { model.state.value.errorMessage != null && !model.state.value.isWorking }
            assertEquals(1, repository.snapshotValue.logs.size)
            main { assertTrue(model.dispatch(AppAction.UndoAnswer)) }
            await { model.state.value.answerIsKnown == null && !model.state.value.isWorking }
            assertNull(model.state.value.errorMessage)
            assertFalse(model.state.value.canUndo)
            assertEquals(0, repository.snapshotValue.logs.size)
            main { assertFalse(model.dispatch(AppAction.UndoAnswer)) }
        }
    }

    @Test fun anErrorScreenDoesNotAccumulateForegroundStudyTime() {
        val repository = ControlledRepository()
        withModel(repository) { model ->
            awaitReady(model)
            main {
                model.setStudyForeground(true)
                assertTrue(model.dispatch(AppAction.Answer(true)))
            }
            await { model.state.value.answerIsKnown == true && !model.state.value.isWorking }
            repository.failNext = true
            main { assertTrue(model.dispatch(AppAction.NextWord)) }
            await { model.state.value.errorMessage != null && !model.state.value.isWorking }
            val before = model.state.value.stats.totalStudySeconds
            // Interaction while an error is visible cannot turn it back into learning time.
            main { model.userInteracted() }
            SystemClock.sleep(2_100)
            main { model.userInteracted() }
            assertEquals(before, model.state.value.stats.totalStudySeconds)
        }
    }

    private fun withModel(repository: ControlledRepository, block: (MainViewModel) -> Unit) {
        val application = instrumentation.targetContext.applicationContext as Application
        val preferenceName = "state-test-${UUID.randomUUID()}"
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        main {
            model = MainViewModel(application, repository, FeedbackPreferences(application, preferenceName))
            store.put("subject", model)
        }
        try { block(model) } finally {
            main { store.clear() }
            application.deleteSharedPreferences(preferenceName)
        }
    }

    private fun awaitReady(model: MainViewModel) = await {
        val state: AppUiState = model.state.value
        !state.isLoading && !state.isWorking && state.currentWord != null && state.errorMessage == null
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("The state transition did not complete", condition())
    }

    private class ControlledRepository : LearningRepository {
        private val word = Word("apple", "apple", "/ˈæpəl/", "n.", "苹果", 1, 1,
            examples = listOf(Example("I eat an apple.", "我吃一个苹果。")))
        @Volatile var snapshotValue = LearningSnapshot(
            words = listOf(word), progress = emptyList(), logs = emptyList(), profile = UserProfile(),
            // An explicit completed marker keeps these tests independent of calibration selection.
            calibration = CalibrationSession("completed", 0L, 1L, emptyList(), 0L),
        )
        @Volatile var nextGate: CompletableDeferred<Unit>? = null
        @Volatile var answerGate: CompletableDeferred<Unit>? = null
        @Volatile var nextCalls = 0
        @Volatile var answerCalls = 0
        @Volatile var failNext = false
        @Volatile var failAnswer = false
        @Volatile var failUndo = false

        override suspend fun unmarkLearned(wordId: String) {
            snapshotValue = snapshotValue.copy(excludedWordIds = snapshotValue.excludedWordIds - wordId)
        }
        override suspend fun markLearned(wordId: String) {
            snapshotValue = snapshotValue.copy(excludedWordIds = snapshotValue.excludedWordIds + wordId)
        }
        override suspend fun snapshot() = snapshotValue
        override suspend fun nextWord(reviewOnly: Boolean): Word {
            nextCalls++
            nextGate?.await()
            if (failNext) { failNext = false; throw IOException("SQLite preparation failure: SELECT * FROM review_progress") }
            return word
        }
        override suspend fun answer(wordId: String, known: Boolean): AnswerResult {
            answerCalls++
            answerGate?.await()
            if (failAnswer) { failAnswer = false; throw IOException("test answer failure") }
            val before = snapshotValue
            val result = LearningScheduler().recordAnswer(before, wordId, known)
            snapshotValue = before.copy(progress = listOf(result.progress),
                profile = result.profile, logs = before.logs + result.log)
            return result
        }
        override suspend fun undoAnswer(result: AnswerResult) {
            if (failUndo) { failUndo = false; throw IOException("test undo failure") }
            snapshotValue = snapshotValue.copy(progress = listOfNotNull(result.previousProgress),
                logs = snapshotValue.logs.filterNot { it.sequence == result.log.sequence },
                profile = result.previousProfile ?: snapshotValue.profile)
        }
        override suspend fun answerCalibration(wordId: String, known: Boolean) = error("No calibration expected")
        override suspend fun updateProfile(name: String, dailyGoal: Int) = Unit
        override suspend fun recordStudyTime(startedAt: Long, durationMillis: Long) = Unit
    }
}
