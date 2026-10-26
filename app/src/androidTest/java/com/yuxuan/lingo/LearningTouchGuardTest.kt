package com.yuxuan.lingo

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.AppUiState
import com.yuxuan.lingo.ui.LingoApp
import com.yuxuan.lingo.ui.WordUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The 300 ms window protects each new control state; it never queues a tap. */
@RunWith(AndroidJUnit4::class)
class LearningTouchGuardTest {
    @get:Rule val compose = createComposeRule()

    private val apple = WordUi(
        wordId = "apple", word = "apple", difficulty = "难度 1 / 10",
        americanIpa = "/ˈæpəl/", meaning = "苹果",
    )

    @Test fun knownIgnoresPhysicalTapsDuringTheWindowWithoutSubmittingThemLater() {
        var answers = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = apple)) { action ->
                if (action is AppAction.Answer) answers++
            }
        }
        compose.onNodeWithText("认识").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("不认识").assertIsEnabled()
        compose.mainClock.advanceTimeBy(288)
        compose.onNodeWithText("认识").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, answers) }

        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("认识").assertIsEnabled()
        // Expiring the protection window must not replay the discarded physical tap.
        compose.runOnIdle { assertEquals(0, answers) }
        compose.onNodeWithText("认识").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, answers) }
    }

    @Test fun nextIgnoresPhysicalTapsDuringTheWindowAndStillDebouncesAfterUnlocking() {
        var nexts = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = apple,
                answerIsKnown = true, canUndo = true)) { action ->
                // Keep the old card on screen while persistence is pending.
                if (action == AppAction.NextWord) nexts++
            }
        }
        compose.onNodeWithText("下一个单词").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("反悔").assertIsEnabled()
        compose.mainClock.advanceTimeBy(288)
        compose.onNodeWithText("下一个单词").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, nexts) }

        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("下一个单词").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, nexts) }
        compose.onNodeWithText("下一个单词").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, nexts) }
    }

    @Test fun revealingAnAnswerAndMovingToAnotherWordEachStartAFreshProtectionWindow() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = apple))
        var answers = 0
        var nexts = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    is AppAction.Answer -> {
                        answers++
                        state.value = state.value.copy(answerIsKnown = action.known, canUndo = true)
                    }
                    AppAction.NextWord -> {
                        nexts++
                        state.value = state.value.copy(
                            currentWord = apple.copy(wordId = "pear", word = "pear"),
                            answerIsKnown = null, canUndo = false,
                        )
                    }
                    else -> Unit
                }
            }
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("认识").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("下一个单词").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, answers); assertEquals(0, nexts) }
        // Leave a frame margin: recomposition/color animation may already advance
        // one frame after the answer. Assert inside the window, not at its edge.
        compose.mainClock.advanceTimeBy(256)
        compose.onNodeWithText("下一个单词").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("下一个单词").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, nexts) }
        compose.onNodeWithText("下一个单词").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("认识").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, answers); assertEquals(1, nexts) }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("认识").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, answers) }
        compose.onNodeWithText("认识").performClick()
        compose.runOnIdle { assertEquals(2, answers) }
    }

    @Test fun undoRestartsKnownProtectionAndSavingCannotBypassTheWindow() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = apple,
            answerIsKnown = false, canUndo = true))
        var answers = 0
        var undos = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    AppAction.UndoAnswer -> {
                        undos++
                        state.value = state.value.copy(answerIsKnown = null, canUndo = false)
                    }
                    is AppAction.Answer -> answers++
                    else -> Unit
                }
            }
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("反悔").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("认识").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, undos)
            assertEquals(0, answers)
            state.value = state.value.copy(isWorking = true)
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("认识").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, answers)
            state.value = state.value.copy(isWorking = false)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("认识").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, answers) }
        compose.onNodeWithText("认识").performClick()
        compose.runOnIdle { assertEquals(1, answers) }
    }
}
