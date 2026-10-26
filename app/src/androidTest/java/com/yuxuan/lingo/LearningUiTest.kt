package com.yuxuan.lingo

import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuxuan.lingo.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningUiTest {
    @Test fun profileLevelTitleAndScoreShareATextBaseline() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedTab = AppTab.PROFILE,
                stats = StatsUi(currentLevelLabel = "5.4 级", currentLevelStage = "独立阅读",
                    currentLevelDescription = "词汇已延伸到一般新闻和工作交流。"))) { }
        }
        val title = compose.onNodeWithTag("level-title")
        val score = compose.onNodeWithTag("level-score")
        val titleLayout = mutableListOf<TextLayoutResult>()
        val scoreLayout = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayout) }
        score.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(scoreLayout) }
        val titleBaseline = title.fetchSemanticsNode().boundsInRoot.top + titleLayout.single().firstBaseline
        val scoreBaseline = score.fetchSemanticsNode().boundsInRoot.top + scoreLayout.single().firstBaseline
        assertEquals(titleBaseline, scoreBaseline, 1f)
        val screenshot = compose.onNodeWithTag("level-summary").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v205-profile.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("首次校准确定起点，之后根据一段时间的新词表现缓慢调整。").assertDoesNotExist()
    }
    @get:Rule val compose = createComposeRule()

    private val word = WordUi(
        wordId = "apple", word = "apple", difficulty = "难度 1 / 10",
        americanIpa = "/ˈæpəl/", partOfSpeech = "n.", meaning = "苹果",
        examples = listOf(ExampleUi("I eat an apple every day.", "我每天吃一个苹果。")),
    )

    @Test fun confusablesAndSpellingComparisonsHaveSeparateSectionsAndTheirOwnIpa() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word.copy(
                confusables = listOf(WordNoteUi("pear", "梨，注意与 apple 的含义不同。",
                    americanIpa = "/per/", britishIpa = "/peə/", pronunciationExpected = true)),
                similarWords = listOf(WordNoteUi("apply", "申请；注意词尾 y。",
                    americanIpa = "/əˈplaɪ/", britishIpa = "/əˈplaɪ/", pronunciationExpected = true)),
            ), answerIsKnown = false, canUndo = true)) { }
        }
        compose.onNodeWithText("易错词").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("pear  /per/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("形近词").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("apply  /əˈplaɪ/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("learningContent").performTouchInput { swipeUp(startY = center.y, endY = 1f) }
        val comparison = compose.onNodeWithText("apply  /əˈplaɪ/").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("learningActions").fetchSemanticsNode().boundsInRoot
        assertTrue("The spelling comparison must scroll clear of the floating controls", comparison.bottom <= actions.top)
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v208-relations.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun frontCardShowsIpaAndRevealsMeaningOnlyAfterJudgement() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word, sessionSize = 20))
        var answers = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                if (action is AppAction.Answer) {
                    answers++
                    state.value = state.value.copy(answerIsKnown = action.known)
                }
            }
        }
        compose.onNodeWithText("apple").assertIsDisplayed()
        compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.onNodeWithText("美式音标").assertIsDisplayed()
        compose.onNodeWithText("/ˈæpəl/").assertIsDisplayed()
        compose.onNodeWithText("n.").assertDoesNotExist()
        compose.onNodeWithText("不认识").performClick()
        compose.onNodeWithText("不认识").assertDoesNotExist()
        compose.onNodeWithText("认识").assertDoesNotExist()
        compose.onNodeWithText("下一个单词").assertIsDisplayed()
        compose.onNodeWithText("苹果").assertExists()
        compose.onNodeWithText("/ˈæpəl/").assertExists()
        compose.runOnIdle { assertEquals(1, answers) }
    }

    @Test fun answeredCardShowsChineseInsideTheCardAndOffersUndo() {
        val attributedWord = word.copy(examples = word.examples.map {
            it.copy(attribution = "学习页不应展示的例句来源", sourceUrl = "https://example.org/learning-example")
        })
        val state = mutableStateOf(AppUiState(
            isLoading = false, currentWord = attributedWord, answerIsKnown = false,
            canUndo = true, stats = StatsUi(currentLevelLabel = "5.5 级", currentLevelStage = "独立阅读"),
        ))
        var undos = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                if (action == AppAction.UndoAnswer) {
                    undos++
                    state.value = state.value.copy(answerIsKnown = null, canUndo = false)
                }
            }
        }
        compose.onNodeWithText("苹果").assertIsDisplayed()
        compose.onNodeWithText("n.").assertDoesNotExist()
        compose.onNodeWithText("认识 · 继续巩固").assertDoesNotExist()
        compose.onNodeWithText("学习页不应展示的例句来源").assertDoesNotExist()
        compose.onNodeWithText("来源").assertDoesNotExist()
        compose.onNodeWithText(word.examples.single().english).assertExists()
        compose.onNodeWithText("反悔").assertIsDisplayed()
        compose.onNodeWithText("反悔").performClick()
        compose.onNodeWithText("认识").assertIsDisplayed()
        compose.onNodeWithText("不认识").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun emptyMistakeBookOffersReturnToLearning() {
        var selected: AppTab? = null
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedTab = AppTab.MISTAKES)) { action ->
                if (action is AppAction.SelectTab) selected = action.tab
            }
        }
        compose.onNodeWithText("易错本还是空白的").assertIsDisplayed()
        compose.onNodeWithText("去学习").performClick()
        compose.runOnIdle { assertEquals(AppTab.LEARN, selected) }
    }

    @Test fun rapidDoubleTapSubmitsOnlyOneAnswerDuringTransition() {
        var answers = 0
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word, sessionSize = 20)) { action ->
                // Simulate persistence still running: the rendered word has not changed yet.
                if (action is AppAction.Answer) answers++
            }
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("认识").assertIsEnabled()
        compose.onNodeWithText("认识").performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(1, answers) }
    }

    @Test fun twentyWordCalibrationShowsIpaWithoutDefinitionOrStudyCounters() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word,
                placement = PlacementUi(active = true, answered = 3))) { }
        }
        compose.onNodeWithText("水平校准").assertIsDisplayed()
        compose.onNodeWithText("3 / 20").assertIsDisplayed()
        compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.onNodeWithText("/ˈæpəl/").assertIsDisplayed()
        compose.onNodeWithText("今日接触 0 个词").assertDoesNotExist()
        compose.onNodeWithText("先判断，再查看中文与例句 · 不计入学习统计").assertIsDisplayed()
    }

    @Test fun calibrationAnswerRevealsChineseAndExamplesBeforeManualNextWord() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word,
            placement = PlacementUi(active = true)))
        var answers = 0
        var nexts = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    is AppAction.Answer -> {
                        answers++
                        state.value = state.value.copy(answerIsKnown = action.known, calibrationFeedback = true,
                            placement = state.value.placement.copy(answered = 1))
                    }
                    AppAction.NextWord -> {
                        nexts++
                        state.value = state.value.copy(currentWord = word.copy(wordId = "learn", word = "learn"),
                            answerIsKnown = null, calibrationFeedback = false)
                    }
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.onNodeWithText("不认识").performClick()
        compose.onNodeWithText("苹果").assertIsDisplayed()
        compose.onNodeWithText("I eat an apple every day.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("下一个单词").assertIsDisplayed().performClick()
        compose.onNodeWithText("learn").assertIsDisplayed()
        compose.onNodeWithText("苹果").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, answers); assertEquals(1, nexts) }
    }

    @Test fun manualMistakeReviewUsesStudyControlsEvenWhenCalibrationIsPending() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word, reviewMode = true,
                placement = PlacementUi(active = true, answered = 3))) { }
        }
        compose.onNodeWithText("易错词复习").assertIsDisplayed()
        compose.onNodeWithText("水平校准").assertDoesNotExist()
        compose.onNodeWithText("3 / 20").assertDoesNotExist()
        compose.onNodeWithText("待复习 0").assertIsDisplayed()
        compose.onNodeWithText("不认识").assertIsDisplayed()
    }

    @Test fun homeModeSwitchReturnsFromActiveMistakeReviewToUnlimitedLearning() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word,
            stats = StatsUi(currentLevelLabel = "5.4 级", currentLevelStage = "独立阅读")))
        var modeChanges = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    AppAction.ReviewMistakes -> { modeChanges++; state.value = state.value.copy(reviewMode = true) }
                    AppAction.ContinueLearning -> { modeChanges++; state.value = state.value.copy(reviewMode = false) }
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("无限学习").assertIsSelected()
        compose.onNodeWithText("易错词复习").performClick()
        compose.onNodeWithText("易错词复习").assertIsSelected()
        compose.onNodeWithText("不认识").assertIsDisplayed()
        compose.onNodeWithText("无限学习").performClick()
        compose.onNodeWithText("无限学习").assertIsSelected()
        compose.onNodeWithText("5.4 级 · 独立阅读").assertDoesNotExist()
        compose.onNodeWithText("5.4 级").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, modeChanges) }
    }

    @Test fun compactHomeShowsLevelWithoutAnExtraSummaryCard() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(740.dp)) {
                LingoApp(AppUiState(isLoading = false, currentWord = word,
                    stats = StatsUi(currentLevelLabel = "5.4 级", currentLevelStage = "独立阅读",
                        currentLevelDescription = "能理解常见文章中的更多抽象表达。"))) { }
            }
        }
        compose.onNodeWithText("5.4 级 · 独立阅读").assertDoesNotExist()
        compose.onNodeWithText("5.4 级").assertIsDisplayed()
        compose.onNodeWithText("认识").assertIsDisplayed()
        compose.onNodeWithText("不认识").assertIsDisplayed()
    }

    @Test fun homeModeSwitchCannotInterruptFirstCalibrationOrPendingAnswer() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word, placement = PlacementUi(active = true)))
        var modeChanges = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                if (action == AppAction.ReviewMistakes || action == AppAction.ContinueLearning) modeChanges++
            }
        }
        compose.onNodeWithText("无限学习").assertIsNotEnabled()
        compose.onNodeWithText("易错词复习").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(placement = PlacementUi(), isWorking = true) }
        compose.onNodeWithText("无限学习").assertIsEnabled()
        compose.onNodeWithText("易错词复习").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, modeChanges) }
    }

    @Test fun profileHasIndependentGlobalSoundAndHapticControlsWithoutRecalibration() {
        val state = mutableStateOf(AppUiState(isLoading = false, selectedTab = AppTab.PROFILE,
            feedback = FeedbackUi(soundEnabled = false, hapticsEnabled = true)))
        var soundChanges = 0
        var hapticChanges = 0
        compose.setContent {
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
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("操作音效").performScrollTo().assertIsOff()
        compose.onNodeWithText("操作音效").performClick().assertIsOn()
        compose.onNodeWithText("触感反馈").performScrollTo().assertIsOn()
        compose.onNodeWithText("触感反馈").performClick().assertIsOff()
        compose.onNodeWithText("操作音效").assertIsOn()
        compose.onNodeWithText("找到更准确的起点").assertDoesNotExist()
        compose.onNodeWithText("重新校准水平").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, soundChanges); assertEquals(1, hapticChanges) }
    }

    @Test fun profileExplainsDecimalLevelAndLocalDialogsRequestFeedback() {
        var taps = 0
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedTab = AppTab.PROFILE,
                stats = StatsUi(currentLevelLabel = "4.7 级", currentLevelStage = "独立阅读",
                    currentLevelDescription = "正在扩展常见文章所需词汇，适合一般新闻和较长的故事。"))) { action ->
                if (action == AppAction.FeedbackTap) taps++
            }
        }
        compose.onNodeWithContentDescription("修改资料").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(2, taps) }
        compose.onNodeWithText("4.7 级").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("独立阅读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("正在扩展常见文章所需词汇，适合一般新闻和较长的故事。").performScrollTo().assertIsDisplayed()
    }

    @Test fun finalCalibrationAnswerStillShowsFeedbackAndUsesCompactFooter() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(640.dp)) {
                LingoApp(AppUiState(isLoading = false, currentWord = word, answerIsKnown = false,
                    calibrationFeedback = true, placement = PlacementUi(active = false, answered = 20))) { }
            }
        }
        compose.onNodeWithText("水平校准").assertIsDisplayed()
        compose.onNodeWithText("20 / 20").assertIsDisplayed()
        compose.onNodeWithText("苹果").assertIsDisplayed()
        compose.onNodeWithText("I eat an apple every day.").assertIsDisplayed()
        compose.onNodeWithText("下一个单词").assertIsDisplayed()
        compose.onNodeWithText("反悔").assertDoesNotExist()
    }

    @Test fun profilePrecedesLevelOnMyPage() {
        compose.setContent {
            Box(Modifier.width(360.dp).height(640.dp)) {
                LingoApp(AppUiState(isLoading = false, selectedTab = AppTab.PROFILE,
                    stats = StatsUi(currentLevelLabel = "4.7 级"))) { }
            }
        }
        compose.onNodeWithText("学习者").assertIsDisplayed()
        compose.onNodeWithText("当前词汇水平").assertIsDisplayed()
        val profileTop = compose.onNodeWithText("学习者").fetchSemanticsNode().boundsInRoot.top
        val levelTop = compose.onNodeWithText("当前词汇水平").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(profileTop < levelTop)
        compose.onNodeWithText("4.7 级").assertIsDisplayed()
    }

    @Test fun onlyOneIpaIsShownEvenWhenUiReceivesDictionaryVariants() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word.copy(americanIpa = "/ˈɹiɫi/, /ˈɹɪɫi/"))) { }
        }
        compose.onNodeWithText("/ˈrili/").assertIsDisplayed()
        compose.onNodeWithText("/ˈrɪli/").assertDoesNotExist()
    }

    @Test fun floatingNavigationSurvivesSwitchingTabsAndReturningToLearning() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word))
        compose.setContent {
            LingoApp(state.value) { action ->
                if (action is AppAction.SelectTab) state.value = state.value.copy(selectedTab = action.tab)
            }
        }
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        compose.onNodeWithText("学习者").assertIsDisplayed()
        compose.onNodeWithText("学习").performClick()
        compose.onNodeWithText("apple").assertIsDisplayed()
        compose.onNode(hasText("易错本") and isSelectable()).performClick()
        compose.onNodeWithText("易错本还是空白的").assertIsDisplayed()
        compose.onNodeWithText("学习").performClick()
        compose.onNodeWithText("apple").assertIsDisplayed()
    }

    @Test fun accentSettingImmediatelyChangesTheCurrentWordWithoutRecordingAnAnswer() {
        val car = word.copy(wordId = "car", word = "car", americanIpa = "/kɑr/", britishIpa = "/kɑː/")
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = car))
        var answers = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    is AppAction.SelectTab -> state.value = state.value.copy(selectedTab = action.tab)
                    is AppAction.SetPronunciationAccent -> state.value = state.value.copy(
                        feedback = state.value.feedback.copy(pronunciationAccent = action.accent))
                    is AppAction.Answer -> answers++
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText("/kɑr/").assertIsDisplayed()
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        compose.onNode(hasText("英式音标") and isSelectable()).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("学习").performClick()
        compose.onNodeWithText("/kɑː/").assertIsDisplayed()
        compose.onNodeWithText("/kɑr/").assertDoesNotExist()
        compose.runOnIdle { assertEquals(car, state.value.currentWord); assertEquals(0, answers) }
    }

    @Test fun englishOnlyExamplesHideMissingTranslationPlaceholder() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedWord = word.copy(
                examples = listOf(ExampleUi("He decided to learn a new language.", attribution = "Open English WordNet · CC BY 4.0"))))) { }
        }
        compose.onNodeWithText("He decided to learn a new language.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("原文例句 · 暂未收录中文译文").assertDoesNotExist()
        compose.onNodeWithText("Open English WordNet · CC BY 4.0").assertDoesNotExist()
        compose.onNodeWithText("来源").assertDoesNotExist()
        compose.onNodeWithText("发音设置与帮助").assertDoesNotExist()
    }

    @Test fun profileHidesDictionaryTechnicalInformationAndKeepsLearningRecords() {
        val initial = AppUiState(isLoading = false, selectedTab = AppTab.PROFILE,
            stats = StatsUi(learnedWords = 12, masteredWords = 5, todayReviewed = 8,
                todayLearnedWords = 6, todayStudySeconds = 125, totalStudySeconds = 485))
        var actions = 0
        compose.setContent { LingoApp(initial) { actions++ } }
        listOf("词汇资料来源", "ECDICT", "ipa-dict", "Princeton WordNet", "Open English WordNet", "Tatoeba", "数据库")
            .forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
        compose.onNodeWithText("专注学习时间").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("今日接触 6 个不同单词 · 8 次练习").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2分5秒").assertExists()
        compose.onNodeWithText("8分5秒").assertExists()
        compose.runOnIdle {
            assertEquals("Rendering profile records must not dispatch a destructive operation", 0, actions)
            assertEquals(12, initial.stats.learnedWords)
            assertEquals(5, initial.stats.masteredWords)
        }
    }

    @Test fun longWordAndIpaRemainVisibleOnCompactScreenWithLargerFont() {
        val longWord = word.copy(wordId = "characteristically", word = "characteristically", americanIpa = "/ˌkɛɹəktəˈɹɪstɪk(ə)li/")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(360.dp)) {
                    LingoApp(AppUiState(isLoading = false, currentWord = longWord)) { }
                }
            }
        }
        compose.onNodeWithText(longWord.word).assertIsDisplayed()
        compose.onNodeWithText(PronunciationDisplay.primary(longWord.americanIpa)).assertIsDisplayed()
        compose.onNodeWithText("认识").assertIsDisplayed()
        compose.onNodeWithText("不认识").assertIsDisplayed()
    }

    @Test fun unfinishedCalibrationDoesNotPublishItsChangingProbeEstimateAsCurrentLevel() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, currentWord = word,
                placement = PlacementUi(active = true, answered = 8),
                stats = StatsUi(currentLevelLabel = "7.8 级"))) { }
        }
        compose.onNodeWithText("校准中").assertIsDisplayed()
        compose.onNodeWithText("7.8 级").assertDoesNotExist()
        compose.onNodeWithText("8 / 20").assertIsDisplayed()
        compose.onNodeWithText("认识").assertIsDisplayed()
    }

    @Test fun finalLearningDetailsCanScrollClearOfBothFloatingControlsOnCompactLargeFontScreen() {
        val finalNote = "最后一条易混词辨析，滚动后应当可以完整阅读。"
        val detailWord = word.copy(
            examples = List(9) { index -> ExampleUi(
                "Example ${index + 1}: I eat an apple every day and learn a new word.",
                "第 ${index + 1} 个例句，向上滚动后应当可以完整阅读。",
            ) },
            collocations = listOf(WordNoteUi("an apple a day", "每日一个苹果")),
            synonyms = List(8) { index -> WordNoteUi("related-word-${index + 1}",
                "第 ${index + 1} 个近义词说明，仅用于当前义项，需要根据上下文区别使用。") },
            confusables = List(8) { index -> WordNoteUi("confusable-word-${index + 1}",
                if (index == 7) finalNote else "第 ${index + 1} 个易混词说明，注意单词的拼写、词性以及使用语境。") },
            sourceLabel = "不应出现的资料来源",
        )
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    LingoApp(AppUiState(isLoading = false, currentWord = detailWord,
                        answerIsKnown = false, canUndo = true)) { }
                }
            }
        }
        compose.onNodeWithText("反悔").assertIsDisplayed()
        compose.onNodeWithText("下一个单词").assertIsDisplayed()
        val navigation = compose.onNodeWithTag("floatingNavigation").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("learningActions").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("learningContent").fetchSemanticsNode().boundsInRoot
        assertTrue("Learning content should extend behind floating navigation", content.bottom > navigation.top)
        assertTrue("Answer controls must not overlap navigation", actions.bottom <= navigation.top)
        listOf("反悔", "下一个单词").forEach { label ->
            val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label must retain a 48 dp touch target", bounds.height >= 48f * pixelsPerDp - 1f)
        }

        // Read the real final detail and then use the remaining trailing space, rather
        // than accepting a node which is technically displayed underneath the controls.
        compose.onNodeWithText(finalNote).performScrollTo()
        compose.onNodeWithTag("learningContent").performTouchInput {
            swipeUp(startY = center.y, endY = 1f)
        }
        compose.onNodeWithText(finalNote).assertIsDisplayed()
        val finalDetail = compose.onNodeWithText(finalNote).fetchSemanticsNode().boundsInRoot
        assertTrue("The final detail must scroll entirely above the action controls", finalDetail.bottom <= actions.top)
        assertTrue("The final detail must remain within the visible content", finalDetail.top >= content.top)
    }

    @Test fun floatingActionsDebounceNextAndUndoAndUnlockForAnotherWord() {
        val state = mutableStateOf(AppUiState(isLoading = false, currentWord = word,
            answerIsKnown = false, canUndo = true))
        var nexts = 0
        var answers = 0
        var undos = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                // Leave the previous response rendered while persistence is pending.
                when (action) {
                    AppAction.NextWord -> nexts++
                    is AppAction.Answer -> answers++
                    AppAction.UndoAnswer -> undos++
                    else -> Unit
                }
            }
        }
        compose.onAllNodesWithText("下一个单词").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("下一个单词").assertIsEnabled()
        compose.onNodeWithText("下一个单词").performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, nexts)
            assertEquals(0, undos)
            state.value = state.value.copy(
                currentWord = word.copy(wordId = "pear", word = "pear"),
                answerIsKnown = null, canUndo = false,
            )
        }
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("认识").assertIsEnabled().performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, answers)
            state.value = state.value.copy(answerIsKnown = true, canUndo = true)
        }
        compose.onAllNodesWithText("反悔").assertCountEquals(1)
        compose.onNodeWithText("反悔").assertIsEnabled().performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, undos)
            assertEquals(1, nexts)
        }
    }
}
