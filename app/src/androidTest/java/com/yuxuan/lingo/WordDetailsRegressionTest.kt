package com.yuxuan.lingo

import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.AppUiState
import com.yuxuan.lingo.ui.ExampleUi
import com.yuxuan.lingo.ui.LingoApp
import com.yuxuan.lingo.ui.WordNoteUi
import com.yuxuan.lingo.ui.WordUi
import com.yuxuan.lingo.ui.FeedbackUi
import com.yuxuan.lingo.ui.PronunciationAccent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordDetailsRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val example = ExampleUi(
        "She reads a book every evening.", "她每天晚上读一本书。",
        attribution = "例句来源测试",
        sourceUrl = "https://example.org/example-source",
    )
    private val word = WordUi(
        wordId = "read", word = "read", difficulty = "难度 2 / 10",
        americanIpa = "/riːd/", meaning = "阅读", examples = listOf(example),
        sourceLabel = "应从详情移除的词汇资料",
    )

    @Test fun wordAndPronunciationHaveTheSameCenteredAxis() {
        compose.setContent { LingoApp(AppUiState(isLoading = false, selectedWord = word)) { } }
        val heading = compose.onNodeWithTag("word-dialog-heading").fetchSemanticsNode().boundsInRoot
        val ipa = compose.onNodeWithTag("word-dialog-ipa").fetchSemanticsNode().boundsInRoot
        assertEquals(heading.center.x, ipa.center.x, 1f)
        compose.onNodeWithText("/riːd/").assertIsDisplayed()
    }

    @Test fun relatedIpaFollowsAccentWithoutReplacingTheMeaning() {
        val state = mutableStateOf(AppUiState(isLoading = false, selectedWord = word.copy(
            derivatives = listOf(WordNoteUi("reader", "读者", "/ˈriːdɚ/", "/ˈriːdə/", true)),
        )))
        compose.setContent { LingoApp(state.value) { } }
        compose.onNodeWithText("reader  /ˈriːdər/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("读者").assertIsDisplayed()
        val screenshot = compose.onNodeWithTag("word-dialog").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v205-word-details.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle { state.value = state.value.copy(feedback = FeedbackUi(pronunciationAccent = PronunciationAccent.UK)) }
        compose.onNodeWithText("reader  /ˈriːdə/").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("reader  /ˈriːdər/").assertDoesNotExist()
    }

    @Test fun dialogShowsExampleAndOmitsEmptyOrBlankDetailSections() {
        val blankNote = listOf(WordNoteUi("  ", "这一条没有词条正文，不能显示"))
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedWord = word.copy(
                examples = listOf(ExampleUi(" ", "空英文不应出现"), example),
                wordForms = blankNote, collocations = blankNote, derivatives = blankNote,
                synonyms = blankNote, confusables = blankNote,
            ))) { }
        }
        compose.onNodeWithText(example.english).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(example.chinese).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(example.attribution).assertDoesNotExist()
        compose.onNodeWithText("来源").assertDoesNotExist()
        listOf(
            "词形变化", "搭配与短语", "派生与词族", "同义与近义词", "易错词", "形近词",
            "空英文不应出现", "这一条没有词条正文，不能显示",
            "词汇资料 · 应从详情移除的词汇资料",
        ).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun dialogWithNoExamplesOmitsTheWholeExampleSection() {
        compose.setContent {
            LingoApp(AppUiState(isLoading = false, selectedWord = word.copy(
                examples = listOf(ExampleUi("", "只有中文，不能充当英文例句")),
            ))) { }
        }
        compose.onNodeWithText("在句子里记住").assertDoesNotExist()
        compose.onNodeWithText("只有中文，不能充当英文例句").assertDoesNotExist()
        compose.onNodeWithText("已学会").performScrollTo().assertIsDisplayed()
    }

    @Test fun lastRelationsAndMasteryButtonRemainReachableAndToggleRetainsExamples() {
        val lastSynonym = "最后一个近义词的义项说明，阅读时需要结合上下文。"
        val lastConfusable = "最后一个易混词的辨析说明，注意拼写区别。"
        val detailedWord = word.copy(
            synonyms = List(8) { index -> WordNoteUi("synonym-${index + 1}",
                if (index == 7) lastSynonym else "第 ${index + 1} 个近义词的说明，结合语境选择正确的含义。") },
            confusables = List(8) { index -> WordNoteUi("confusable-${index + 1}",
                if (index == 7) lastConfusable else "第 ${index + 1} 个易混词的说明，注意拼写与含义的区别。") },
        )
        val state = mutableStateOf(AppUiState(isLoading = false, selectedWord = detailedWord))
        var marked = 0
        var unmarked = 0
        compose.setContent {
            LingoApp(state.value) { action ->
                when (action) {
                    is AppAction.MarkLearned -> {
                        marked++
                        state.value = state.value.copy(excludedWordIds = state.value.excludedWordIds + action.wordId)
                    }
                    is AppAction.UnmarkLearned -> {
                        unmarked++
                        state.value = state.value.copy(excludedWordIds = state.value.excludedWordIds - action.wordId)
                    }
                    else -> Unit
                }
            }
        }
        compose.onNodeWithText(lastSynonym).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("易错词").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("形近对照", substring = true).assertDoesNotExist()
        compose.onNodeWithText("自动筛选", substring = true).assertDoesNotExist()
        compose.onNodeWithText(lastConfusable).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("learned-read").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("按错了").assertIsDisplayed()
        compose.onNodeWithText(example.english).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(lastConfusable).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("按错了").performScrollTo().performClick()
        compose.onNodeWithText("已学会").assertIsDisplayed()
        compose.onNodeWithText(example.chinese).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, marked)
            assertEquals(1, unmarked)
            assertEquals(detailedWord, state.value.selectedWord)
            assertEquals(emptySet<String>(), state.value.excludedWordIds)
        }
    }
}
