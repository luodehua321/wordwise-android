package com.yuxuan.lingo

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.AppTab
import com.yuxuan.lingo.ui.AppUiState
import com.yuxuan.lingo.ui.LingoApp
import com.yuxuan.lingo.ui.ProfileUi
import com.yuxuan.lingo.ui.WordProgressUi
import com.yuxuan.lingo.ui.WordUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val learnWord = WordUi(
        wordId = "linden", word = "linden", difficulty = "难度 5 / 10",
        americanIpa = "/ˈlɪndən/", meaning = "椴树",
    )
    private val mistakeWord = WordUi(
        wordId = "pear", word = "pear", difficulty = "难度 2 / 10",
        americanIpa = "/pɛr/", meaning = "梨子中文释义",
    )

    /** Exercise the production UI against real, observable action results, including tab changes. */
    private class Fixture(private val initial: AppUiState) {
        val state = mutableStateOf(initial)
        val selectedTabs = mutableListOf<AppTab>()
        var modeChanges = 0
        var learnedCount = 0

        fun dispatch(action: AppAction) {
            state.value = when (action) {
                is AppAction.SelectTab -> {
                    selectedTabs += action.tab
                    state.value.copy(selectedTab = action.tab)
                }
                is AppAction.MarkLearned -> {
                    learnedCount++
                    state.value.copy(excludedWordIds = state.value.excludedWordIds + action.wordId, mistakes = state.value.mistakes.filterNot { it.word.wordId == action.wordId })
                }
                is AppAction.UnmarkLearned -> initial.copy(selectedWord = state.value.selectedWord)
                is AppAction.Answer -> state.value.copy(answerIsKnown = action.known)
                AppAction.ReviewMistakes -> {
                    modeChanges++
                    state.value.copy(reviewMode = true)
                }
                AppAction.ContinueLearning -> {
                    modeChanges++
                    state.value.copy(reviewMode = false)
                }
                is AppAction.OpenWord -> state.value.copy(selectedWord = action.word)
                AppAction.DismissWord -> state.value.copy(selectedWord = null)
                else -> state.value
            }
        }
    }

    private fun show(initial: AppUiState): Fixture {
        val fixture = Fixture(initial)
        compose.setContent { LingoApp(fixture.state.value, fixture::dispatch) }
        return fixture
    }

    @Test fun learnedButtonInDetailsCanBeUndone() {
        val fixture = show(AppUiState(isLoading = false, selectedTab = AppTab.MISTAKES,
            mistakes = listOf(WordProgressUi(mistakeWord, 0, 1, 0))))
        compose.onNodeWithTag("learned-pear").assertDoesNotExist()
        compose.onNodeWithText(mistakeWord.word).performClick()
        compose.onNodeWithTag("learned-pear").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.learnedCount)
            assertEquals(0, fixture.state.value.mistakes.size)
            assertEquals("pear", fixture.state.value.selectedWord?.wordId)
        }
        compose.onNodeWithText("按错了").performClick()
        compose.runOnIdle { assertEquals(1, fixture.state.value.mistakes.size) }
        compose.onNodeWithText("已学会").assertExists()
    }

    @Test fun swipingPagesUpdatesSelectionAndNavigationReturnsToTheSameLearningWord() {
        val fixture = show(AppUiState(
            isLoading = false, currentWord = learnWord,
            mistakes = listOf(WordProgressUi(mistakeWord, 0, 1, 0)),
            profile = ProfileUi(displayName = "回归学习者"),
        ))
        compose.onNodeWithText("linden").assertIsDisplayed()
        compose.onNodeWithTag("floatingNavigation").assertIsDisplayed()
        compose.onNode(hasText("学习") and hasClickAction()).assertIsSelected()

        compose.onNodeWithTag("appPager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("pear").assertIsDisplayed()
        compose.onNode(hasText("易错本") and hasClickAction()).assertIsSelected()
        compose.runOnIdle { assertEquals(AppTab.MISTAKES, fixture.state.value.selectedTab) }

        compose.onNodeWithTag("appPager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("回归学习者").assertIsDisplayed()
        compose.onNode(hasText("我的") and hasClickAction()).assertIsSelected()
        compose.runOnIdle { assertEquals(AppTab.PROFILE, fixture.state.value.selectedTab) }

        compose.onNodeWithTag("appPager").performTouchInput { swipeRight() }
        compose.onNodeWithText("pear").assertIsDisplayed()
        compose.runOnIdle { assertEquals(AppTab.MISTAKES, fixture.state.value.selectedTab) }

        compose.onNode(hasText("学习") and hasClickAction()).performClick()
        compose.onNodeWithText("linden").assertIsDisplayed()
        compose.onNodeWithText("认识").assertIsDisplayed()
        compose.onNode(hasText("学习") and hasClickAction()).assertIsSelected()
        compose.runOnIdle {
            assertEquals(AppTab.LEARN, fixture.state.value.selectedTab)
            assertEquals(learnWord, fixture.state.value.currentWord)
            assertEquals(listOf(AppTab.MISTAKES, AppTab.PROFILE, AppTab.MISTAKES, AppTab.LEARN), fixture.selectedTabs)
        }
    }

    @Test fun answeredLearningWordOmitsUnavailableDetailsInsteadOfShowingEmptySections() {
        val sparseWord = learnWord.copy(americanIpa = "", meaning = "", sourceLabel = "不应显示的资料来源")
        val fixture = show(AppUiState(isLoading = false, currentWord = sparseWord))

        compose.onNodeWithText("不认识").performClick()
        compose.onNodeWithText("下一个单词").assertIsDisplayed()
        listOf(
            "尚未收录中文释义", "音标暂缺", "尚未收录例句",
            "在句子里记住", "词形变化", "搭配与短语", "派生与词族", "别和它们混淆",
            "同义与近义词", "易混词", "词汇资料 · 不应显示的资料来源",
            "暂未收录可靠的同义词；部分词没有可直接替换的同义词。",
            "暂未收录可靠的易混词或形近词对照。",
            "此词的搭配与短语尚未收录", "暂未收录此词的易混辨析",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.runOnIdle { assertEquals(false, fixture.state.value.answerIsKnown) }
    }

    @Test fun savingAnAnswerKeepsHeaderAndModeChipGeometryStableWithoutAcceptingModeChanges() {
        val fixture = show(AppUiState(isLoading = false, currentWord = learnWord))
        val headerBounds = compose.onNodeWithText("知词").fetchSemanticsNode().boundsInRoot
        val chipBounds = compose.onNodeWithText("无限学习").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(isWorking = true) }

        // Persisting a response must not toggle the chips into a visibly different disabled style.
        compose.onNodeWithText("无限学习").assertIsEnabled()
        compose.onNodeWithText("易错词复习").assertIsEnabled().performClick()
        assertEquals(headerBounds, compose.onNodeWithText("知词").fetchSemanticsNode().boundsInRoot)
        assertEquals(chipBounds, compose.onNodeWithText("无限学习").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(0, fixture.modeChanges)
            assertEquals(false, fixture.state.value.reviewMode)
            fixture.state.value = fixture.state.value.copy(isWorking = false)
        }
        compose.onNodeWithText("易错词复习").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.modeChanges)
            assertEquals(true, fixture.state.value.reviewMode)
        }
    }

    @Test fun mistakeBookHidesDefinitionsAndExtraControlsButPreservesDetailAccess() {
        val fixture = show(AppUiState(
            isLoading = false, selectedTab = AppTab.MISTAKES,
            currentWord = learnWord,
            mistakes = listOf(WordProgressUi(mistakeWord, 1, 3, 25)),
        ))
        compose.onNodeWithText("pear").assertIsDisplayed()
        compose.onNodeWithText("梨子中文释义").assertDoesNotExist()
        compose.onNodeWithText("搜索单词或中文释义").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("开始易错词复习").assertDoesNotExist()

        compose.onNodeWithText("pear").performClick()
        compose.onNodeWithText("单词详情").assertIsDisplayed()
        compose.onNodeWithText("梨子中文释义").assertIsDisplayed()
        compose.runOnIdle { assertEquals(mistakeWord, fixture.state.value.selectedWord) }
        compose.onNodeWithContentDescription("关闭单词详情").performClick()
        compose.onNodeWithText("单词详情").assertDoesNotExist()
        compose.runOnIdle { assertNull(fixture.state.value.selectedWord) }
    }
}
