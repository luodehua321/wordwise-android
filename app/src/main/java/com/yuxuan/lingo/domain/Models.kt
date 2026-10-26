package com.yuxuan.lingo.domain

data class Example(val en: String, val zh: String, val attribution: String = "", val sourceUrl: String = "")
data class Collocation(val en: String, val zh: String)
data class Derivative(val word: String, val meaningZh: String)
data class Confusable(val word: String, val meaningZh: String, val note: String)
data class Synonym(val word: String, val meaningZh: String, val senseEn: String = "")
data class WordForm(val form: String, val label: String)

data class Word(
    val id: String,
    val word: String,
    val pronunciationUs: String,
    val partOfSpeech: String,
    val meaningZh: String,
    val difficulty: Int,
    val rank: Int,
    val examples: List<Example> = emptyList(),
    val collocations: List<Collocation> = emptyList(),
    val derivatives: List<Derivative> = emptyList(),
    val confusables: List<Confusable> = emptyList(),
    val wordForms: List<WordForm> = emptyList(),
    val synonyms: List<Synonym> = emptyList(),
    /** Received Pronunciation from a separate UK dataset; empty when not attested. */
    val pronunciationUk: String = "",
)

/** All timestamps are milliseconds since Unix epoch; null means never. */
data class ReviewProgress(
    val wordId: String,
    val correctCount: Int = 0,
    val incorrectCount: Int = 0,
    val consecutiveKnown: Int = 0,
    val firstSeenAt: Long? = null,
    val lastReviewedAt: Long? = null,
    val lastUnknownAt: Long? = null,
    val knownStreakStartedAt: Long? = null,
    val masteredAt: Long? = null,
    val nextReviewAt: Long = 0,
    val lastAnsweredSequence: Long = 0,
    val retryAfterSequence: Long = 0,
) {
    val answerCount: Int get() = correctCount + incorrectCount
    val accuracy: Double get() = if (answerCount == 0) 0.0 else correctCount.toDouble() / answerCount
    val isMastered: Boolean get() = masteredAt != null || correctCount >= 2
    val isMistake: Boolean get() = incorrectCount > 0 && !isMastered
}

data class UserProfile(
    val name: String = "学习者",
    val dailyGoal: Int = 20,
    val level: Double = LearningScheduler.INITIAL_LEVEL,
    val totalAnswers: Long = 0,
    val createdAt: Long = 0,
    /** Legacy migration marker retained for database compatibility. */
    val resetCalibrationSinceSequence: Long = 0,
)

/** Confidence describes the estimate's stability; it is not a standardized exam result. */
data class PlacementStatus(
    val isCalibrating: Boolean,
    val sampleCount: Int,
    val maxSamples: Int,
    val minSamples: Int,
    val confidenceLabel: String,
    val estimatedLevel: Double,
    val levelLabel: String,
    val confidence: Double,
)

data class AnswerLog(
    val sequence: Long,
    val wordId: String,
    val known: Boolean,
    val answeredAt: Long,
)

/** ISO local date and accumulated active-learning time; historical answer logs are separate. */
data class DailyStudyTime(val date: String, val elapsedMillis: Long)

/** Calibration combines broad coverage and adaptive information targeting. */
const val PLACEMENT_SAMPLE_TARGET = 20

/** Diagnostic answers never create learning progress, review cards, or learning answer logs. */
data class CalibrationAnswer(val wordId: String, val known: Boolean, val answeredAt: Long)
data class CalibrationSession(
    val id: String,
    val startedAt: Long,
    val seed: Long,
    /** Words already assigned, in order. The queue grows adaptively after each answer. */
    val wordIds: List<String>,
    val learningStartSequence: Long,
    val answers: List<CalibrationAnswer> = emptyList(),
    /** Persisted indirectly from vocabulary size; an empty queue is a completed marker. */
    val targetSamples: Int = PLACEMENT_SAMPLE_TARGET,
) {
    val isComplete: Boolean get() = wordIds.isEmpty() || answers.size >= targetSamples
    val nextWordId: String? get() = if (isComplete) null else wordIds.getOrNull(answers.size)
}


data class LearningSnapshot(
    val words: List<Word>,
    val progress: List<ReviewProgress>,
    val logs: List<AnswerLog>,
    val profile: UserProfile,
    val studyTimes: List<DailyStudyTime> = emptyList(),
    val calibration: CalibrationSession? = null,
    val excludedWordIds: Set<String> = emptySet(),
)

data class AnswerResult(
    val word: Word,
    val progress: ReviewProgress,
    val profile: UserProfile,
    val log: AnswerLog,
    /** Snapshot just before this answer, used by the in-session undo action. */
    val previousProgress: ReviewProgress? = null,
    val previousProfile: UserProfile? = null,
)

interface LearningRepository {
    suspend fun snapshot(): LearningSnapshot
    suspend fun nextWord(reviewOnly: Boolean = false): Word?
    suspend fun answer(wordId: String, known: Boolean): AnswerResult
    suspend fun undoAnswer(result: AnswerResult)
    suspend fun answerCalibration(wordId: String, known: Boolean)
    suspend fun updateProfile(name: String, dailyGoal: Int)
    suspend fun recordStudyTime(startedAt: Long, durationMillis: Long)
    suspend fun unmarkLearned(wordId: String)
    suspend fun markLearned(wordId: String)
}
