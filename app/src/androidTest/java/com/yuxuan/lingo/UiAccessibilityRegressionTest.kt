package com.yuxuan.lingo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.AppTab
import com.yuxuan.lingo.ui.AppUiState
import com.yuxuan.lingo.ui.LingoApp
import com.yuxuan.lingo.ui.WordUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual clipped text and hit targets, including content behind the bottom overlay. */
@RunWith(AndroidJUnit4::class)
class UiAccessibilityRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val apple = WordUi("apple", "apple", "难度 1 / 10", meaning = "苹果")

    private fun showCompact(state: AppUiState, onAction: (AppAction) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).height(480.dp)) { LingoApp(state, onAction) }
            }
        }
    }

    @Test fun learningModeLabelsRemainUnclippedAtTwoHundredPercentFont() {
        showCompact(AppUiState(isLoading = false, currentWord = apple))
        compose.waitForIdle()
        listOf("无限学习", "易错词复习").forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label must expose text layout", layouts.isNotEmpty())
            // A density/font-scale change can expose the previous transient layout for a
            // frame. The last result is the one actually painted on screen.
            val layout = layouts.last()
            assertTrue("$label must not lose text within its layout size=${layout.size} lines=${layout.lineCount} all=${layouts.map { it.size }}", !layout.hasVisualOverflow)
            assertTrue("$label must not be cropped by a fixed-height chip",
                node.fetchSemanticsNode().boundsInRoot.height + 1f >= layout.size.height)
        }
    }

    @Test fun emptyMistakeActionCanScrollEntirelyAboveFloatingNavigation() {
        var destination: AppTab? = null
        showCompact(AppUiState(isLoading = false, selectedTab = AppTab.MISTAKES)) { action ->
            if (action is AppAction.SelectTab) destination = action.tab
        }
        assertActionClearsNavigationAfterScrolling("去学习")
        compose.onNodeWithText("去学习").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(AppTab.LEARN, destination) }
    }

    @Test fun retryActionCanScrollEntirelyAboveFloatingNavigation() {
        var retries = 0
        showCompact(AppUiState(isLoading = false, errorMessage =
            "本地词汇暂时没有准备好，请重新加载后继续学习。")) { action ->
            if (action == AppAction.Retry) retries++
        }
        assertActionClearsNavigationAfterScrolling("重新加载")
        compose.onNodeWithText("重新加载").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun assertActionClearsNavigationAfterScrolling(label: String) {
        compose.onNodeWithText(label).performScrollTo()
        // Finish scrolling through padding. A visible semantics node may still be
        // underneath the navigation and therefore impossible to tap safely.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.onNodeWithText(label).assertIsDisplayed()
        val button = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("floatingNavigation").fetchSemanticsNode().boundsInRoot
        assertTrue("$label must scroll entirely above floating navigation", button.bottom <= navigation.top)
    }

    @Test fun largeFontSettingsKeepIndependentAccessibleChoicesAndSwitches() {
        val state = mutableStateOf(AppUiState(isLoading = false, selectedTab = AppTab.PROFILE))
        var inputModeManager: InputModeManager? = null
        var soundChanges = 0
        var hapticChanges = 0
        var accentChanges = 0
        compose.setContent {
            val density = LocalDensity.current
            inputModeManager = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    LingoApp(state.value) { action ->
                        when (action) {
                            is AppAction.SetSoundEnabled -> {
                                soundChanges++
                                state.value = state.value.copy(feedback = state.value.feedback.copy(soundEnabled = action.enabled))
                            }
                            is AppAction.SetHapticsEnabled -> {
                                hapticChanges++
                                state.value = state.value.copy(feedback = state.value.feedback.copy(hapticsEnabled = action.enabled))
                            }
                            is AppAction.SetPronunciationAccent -> {
                                accentChanges++
                                state.value = state.value.copy(feedback = state.value.feedback.copy(pronunciationAccent = action.accent))
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        fun focusAboveNavigation(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
            compose.runOnIdle { inputModeManager!!.requestInputMode(InputMode.Keyboard) }
            // Request actual input focus. This invokes the production focus/bring-into-view
            // path; ScrollTo alone only knows the viewport and can leave the hit target
            // beneath the floating navigation. Do not manually compensate in this test.
            node.performSemanticsAction(SemanticsActions.RequestFocus) { request ->
                assertTrue("The settings control must accept focus", request())
            }
            node.assertIsFocused().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val navigation = compose.onNodeWithTag("floatingNavigation").fetchSemanticsNode().boundsInRoot
            assertTrue("Focused settings control must be entirely above navigation",
                bounds.bottom <= navigation.top)
            return node
        }
        focusAboveNavigation(compose.onNode(hasText("英式音标") and isSelectable()))
            .performClick().assertIsSelected()
        focusAboveNavigation(compose.onNodeWithText("操作音效"))
            .assertIsOn().performClick().assertIsOff()
        focusAboveNavigation(compose.onNodeWithText("触感反馈"))
            .assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(1, accentChanges)
            assertEquals(1, soundChanges)
            assertEquals(1, hapticChanges)
        }
    }

    @Test fun unchangedAnswerCanBeRetriedAfterLocalDebounceWithoutQueuingIt() {
        var attempts = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = apple)) { action ->
                // A parent may reject an obsolete/busy action without publishing an answer.
                if (action is AppAction.Answer) attempts++
            }
        }
        compose.onNodeWithText("不认识").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("不认识").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("不认识").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithText("不认识").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun unchangedNextCanBeRetriedAfterLocalDebounceWhileBusyStillBlocksIt() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = apple,
            answerIsKnown = true, canUndo = true))
        var attempts = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LingoApp(state.value) { action -> if (action == AppAction.NextWord) attempts++ }
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("下一个单词").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("下一个单词").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(isWorking = true) }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("下一个单词").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, attempts)
            state.value = state.value.copy(isWorking = false)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("下一个单词").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithText("下一个单词").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun busyProfileSaveRetainsTheEditedNameUntilItCanBeSaved() {
        val state = mutableStateOf(AppUiState(isLoading = false, isWorking = true,
            selectedTab = AppTab.PROFILE))
        val editedName = "新的昵称🌱"
        val saves = mutableListOf<AppAction.SaveProfile>()
        compose.setContent {
            Box(Modifier.width(360.dp).height(640.dp)) {
                LingoApp(state.value) { action ->
                    if (action is AppAction.SaveProfile) {
                        saves += action
                        state.value = state.value.copy(profile = state.value.profile.copy(displayName = action.displayName))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("修改资料").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(editedName)
        compose.onNodeWithText("保存").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("修改昵称").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains(editedName)
        compose.onNodeWithText("取消").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(0, saves.size)
            state.value = state.value.copy(isWorking = false)
        }
        compose.onNode(hasSetTextAction()).assertTextContains(editedName)
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.onNodeWithText("修改昵称").assertDoesNotExist()
        compose.onNodeWithText(editedName).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, saves.size)
            assertEquals(editedName, saves.single().displayName)
            assertEquals(20, saves.single().dailyGoal)
        }
    }
}
