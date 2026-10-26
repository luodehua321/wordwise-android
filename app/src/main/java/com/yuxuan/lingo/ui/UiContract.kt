package com.yuxuan.lingo.ui

enum class AppTab { LEARN, MISTAKES, RECORDS, PROFILE }

enum class PronunciationAccent { US, UK }

data class WordUi(
    val wordId: String,
    val word: String,
    val difficulty: String,
    val americanIpa: String = "",
    val partOfSpeech: String = "",
    val meaning: String = "",
    val examples: List<ExampleUi> = emptyList(),
    val collocations: List<WordNoteUi> = emptyList(),
    val derivatives: List<WordNoteUi> = emptyList(),
    val confusables: List<WordNoteUi> = emptyList(),
    val wordForms: List<WordNoteUi> = emptyList(),
    val synonyms: List<WordNoteUi> = emptyList(),
    val sourceLabel: String = "",
    val britishIpa: String = "",
    val similarWords: List<WordNoteUi> = emptyList(),
)

data class ExampleUi(val english: String, val chinese: String = "", val attribution: String = "", val sourceUrl: String = "")
data class WordNoteUi(
    val text: String,
    val meaning: String = "",
    val americanIpa: String = "",
    val britishIpa: String = "",
    val pronunciationExpected: Boolean = false,
)

data class WordProgressUi(
    val word: WordUi,
    val knownCount: Int,
    val unknownCount: Int,
    val masteryPercent: Int,
    val nextReviewLabel: String = "",
)

data class StudyDayUi(val label: String, val reviewed: Int, val known: Int, val studySeconds: Long = 0)

data class StatsUi(
    val todayReviewed: Int = 0,
    val todayKnown: Int = 0,
    val totalReviewed: Int = 0,
    val learnedWords: Int = 0,
    val masteredWords: Int = 0,
    val learningDays: Int = 0,
    val streakDays: Int = 0,
    val todayStudySeconds: Long = 0,
    val totalStudySeconds: Long = 0,
    val todayLearnedWords: Int = 0,
    val currentLevelLabel: String = "正在了解词汇水平",
    val currentLevelStage: String = "",
    val currentLevelDescription: String = "",
    val week: List<StudyDayUi> = emptyList(),
)

data class ProfileUi(
    val displayName: String = "学习者",
    val dailyGoal: Int = 20,
    val createdAtLabel: String = "",
)

data class FeedbackUi(
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val pronunciationAccent: PronunciationAccent = PronunciationAccent.US,
)

/** A separate, twenty-word placement; its answers do not become study reviews. */
data class PlacementUi(
    val active: Boolean = false,
    val answered: Int = 0,
    val total: Int = 20,
    val confidence: Float = 0f,
    val levelLabel: String = "正在了解",
)

data class AppUiState(
    val excludedWordIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: AppTab = AppTab.LEARN,
    val currentWord: WordUi? = null,
    val answerIsKnown: Boolean? = null,
    val canUndo: Boolean = false,
    val calibrationFeedback: Boolean = false,
    val sessionPosition: Int = 1,
    val sessionSize: Int = 0,
    val sessionComplete: Boolean = false,
    val reviewMode: Boolean = false,
    val dueCount: Int = 0,
    val mistakes: List<WordProgressUi> = emptyList(),
    val selectedWord: WordUi? = null,
    val stats: StatsUi = StatsUi(),
    val profile: ProfileUi = ProfileUi(),
    val placement: PlacementUi = PlacementUi(),
    val feedback: FeedbackUi = FeedbackUi(),
)

sealed interface AppAction {
    data class SelectTab(val tab: AppTab) : AppAction
    data class Answer(val known: Boolean) : AppAction
    data object UndoAnswer : AppAction
    data object NextWord : AppAction
    data object ReviewMistakes : AppAction
    data class UnmarkLearned(val wordId: String) : AppAction
    data class MarkLearned(val wordId: String) : AppAction
    data class OpenWord(val word: WordUi) : AppAction
    data object DismissWord : AppAction
    data class SaveProfile(val displayName: String, val dailyGoal: Int) : AppAction
    data object Retry : AppAction
    data object ContinueLearning : AppAction
    data class SetSoundEnabled(val enabled: Boolean) : AppAction
    data class SetHapticsEnabled(val enabled: Boolean) : AppAction
    data class SetPronunciationAccent(val accent: PronunciationAccent) : AppAction
    data object FeedbackTap : AppAction
}
