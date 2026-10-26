package com.yuxuan.lingo

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.domain.*
import com.yuxuan.lingo.feedback.FeedbackPreferences
import com.yuxuan.lingo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class TemporalAndMistakeRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val word = Word("apple", "apple", "/ˈæpəl/", "n.", "苹果", 1, 1)
    private fun timestamp(hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(2026, 10, 25, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test fun dueCountAndMistakeLabelRefreshWhileReadingWithoutAnAnswerOrDatabasePolling() {
        val now = AtomicLong(timestamp(12, 0, 0))
        val last = now.get() - LearningScheduler.REVIEW_DELAY + 1_000L
        val repository = SnapshotRepository(now, last)
        withModel(repository, now) { model ->
            ready(model)
            assertEquals(0, model.state.value.dueCount)
            assertEquals("待巩固 · 间隔未到", model.state.value.mistakes.single().nextReviewLabel)
            val currentWord = model.state.value.currentWord
            val reads = repository.snapshotCalls
            instrumentation.runOnMainSync { model.setStudyForeground(true); now.addAndGet(1_000) }
            await { !model.state.value.isWorking && model.state.value.dueCount == 1 }
            assertEquals("待巩固 · 优先复习", model.state.value.mistakes.single().nextReviewLabel)
            assertEquals(currentWord, model.state.value.currentWord)
            assertEquals("The timer must not query storage every second", reads, repository.snapshotCalls)
            assertEquals(1, repository.nextCalls)
            assertEquals(1, repository.value.logs.size)
        }
    }

    @Test fun crossingMidnightRefreshesTodayAndWeekWithoutReplacingTheQuestion() {
        val now = AtomicLong(timestamp(23, 59, 59))
        val repository = SnapshotRepository(now, now.get() - 60_000L)
        withModel(repository, now) { model ->
            ready(model)
            assertEquals(1, model.state.value.stats.todayReviewed)
            assertEquals(1, model.state.value.stats.week.last().reviewed)
            val currentWord = model.state.value.currentWord
            instrumentation.runOnMainSync { model.setStudyForeground(true); now.addAndGet(2_000) }
            await { !model.state.value.isWorking && model.state.value.stats.todayReviewed == 0 }
            assertEquals(0, model.state.value.stats.week.last().reviewed)
            assertEquals(1, model.state.value.stats.week[5].reviewed)
            assertEquals(1, model.state.value.stats.totalReviewed)
            assertEquals(currentWord, model.state.value.currentWord)
            assertEquals(1, repository.nextCalls)
        }
    }

    @Test fun movingClockBackRecomputesEligibilityInsteadOfKeepingADueBadge() {
        val now = AtomicLong(timestamp(12, 0, 0))
        val repository = SnapshotRepository(now, now.get() - LearningScheduler.REVIEW_DELAY)
        withModel(repository, now) { model ->
            ready(model)
            assertEquals(1, model.state.value.dueCount)
            instrumentation.runOnMainSync { model.setStudyForeground(true); now.addAndGet(-1_000L) }
            await { !model.state.value.isWorking && model.state.value.dueCount == 0 }
            assertEquals("待巩固 · 间隔未到", model.state.value.mistakes.single().nextReviewLabel)
        }
    }

    @Test fun mistakeFiltersIncludeWordsRecognisedOnceEvenAfterManyIncorrectAnswers() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedTab = AppTab.MISTAKES,
                mistakes = listOf(
                    WordProgressUi(WordUi("apple", "apple", "难度 1 / 10"), 0, 5, 0),
                    WordProgressUi(WordUi("pear", "pear", "难度 1 / 10"), 1, 99, 1),
                ))) { }
        }
        compose.onNodeWithText("尚未认识").performClick()
        compose.onNodeWithText("apple").assertIsDisplayed()
        compose.onNodeWithText("pear").assertDoesNotExist()
        compose.onNodeWithText("认识过一次").performClick()
        compose.onNodeWithText("pear").assertIsDisplayed()
        compose.onNodeWithText("apple").assertDoesNotExist()
    }

    private fun withModel(repository: SnapshotRepository, clock: AtomicLong, block: (MainViewModel) -> Unit) {
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = "temporal-test-${UUID.randomUUID()}"
        val models = ViewModelStore()
        lateinit var model: MainViewModel
        instrumentation.runOnMainSync {
            model = MainViewModel(application, repository, FeedbackPreferences(application, preferences), clock::get, { zone })
            models.put("subject", model)
        }
        try { block(model) } finally {
            instrumentation.runOnMainSync { models.clear() }
            application.deleteSharedPreferences(preferences)
        }
    }
    private fun ready(model: MainViewModel) = await {
        !model.state.value.isLoading && !model.state.value.isWorking && model.state.value.currentWord != null
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("The time-sensitive state did not refresh", condition())
    }
    private inner class SnapshotRepository(clock: AtomicLong, answeredAt: Long) : LearningRepository {
        @Volatile var snapshotCalls = 0
        @Volatile var nextCalls = 0
        val value = LearningSnapshot(words = listOf(word),
            progress = listOf(ReviewProgress("apple", incorrectCount = 1, lastReviewedAt = answeredAt)),
            logs = listOf(AnswerLog(1, "apple", false, answeredAt)),
            profile = UserProfile(totalAnswers = 1, createdAt = clock.get()),
            calibration = CalibrationSession("complete", 0L, 1L, emptyList(), 0L))
        override suspend fun snapshot(): LearningSnapshot { snapshotCalls++; return value }
        override suspend fun nextWord(reviewOnly: Boolean): Word { nextCalls++; return word }
        override suspend fun answer(wordId: String, known: Boolean): AnswerResult = error("No answer expected")
        override suspend fun undoAnswer(result: AnswerResult) = error("No undo expected")
        override suspend fun answerCalibration(wordId: String, known: Boolean) = error("No calibration expected")
        override suspend fun updateProfile(name: String, dailyGoal: Int) = Unit
        override suspend fun recordStudyTime(startedAt: Long, durationMillis: Long) = Unit
        override suspend fun markLearned(wordId: String) = Unit
        override suspend fun unmarkLearned(wordId: String) = Unit
    }
}
