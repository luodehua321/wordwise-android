package com.yuxuan.lingo.domain

internal enum class LearningCardKind { NEW, REVIEW }

/** Stateless policy; callers rebuild the inputs from persisted progress and answer logs. */
internal object LearningStreamPolicy {
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    private const val NEW_ANSWER_WINDOW = 20
    private const val MIN_NEW_ANSWERS = 8

    /**
     * [dueCount] and [maxOverdueMillis] describe only reviews eligible in this mode now.
     * [recentNewAnswers] is oldest first, with one first-encounter answer per independent
     * new word; reviews and returning calibration probes must not enter this sample.
     * [expectedNewRecognition], when supplied, contains one model prediction for each
     * answer in the same order. Empty predictions preserve raw-rate behavior for callers
     * without difficulty evidence by assuming the 70% target for every word.
     * The result is a base probability, subject to streak guards and available pools.
     */
    fun reviewProbability(
        dueCount: Int,
        maxOverdueMillis: Long,
        recentNewAnswers: List<Boolean>,
        expectedNewRecognition: List<Double> = emptyList(),
    ): Double {
        require(expectedNewRecognition.isEmpty() || expectedNewRecognition.size == recentNewAnswers.size) {
            "每个新词回答必须对应一个认识概率"
        }
        require(expectedNewRecognition.all { it.isFinite() && it in 0.0..1.0 }) {
            "认识概率必须是 0 到 1 之间的有限数"
        }
        val recent = recentNewAnswers.takeLast(NEW_ANSWER_WINDOW)
        val recognition = if (recent.size >= MIN_NEW_ANSWERS) {
            if (expectedNewRecognition.isEmpty()) recent.count { it }.toDouble() / recent.size
            else {
                val expected = expectedNewRecognition.takeLast(NEW_ANSWER_WINDOW)
                (.70 + (recent.count { it } - expected.sum()) / recent.size).coerceIn(0.0, 1.0)
            }
        } else null
        return when {
            dueCount >= 20 || maxOverdueMillis >= DAY_MILLIS ||
                (recognition != null && recognition < .60 - 1e-12) -> .60
            recognition != null && recognition >= .85 - 1e-12 && dueCount < 10 -> .30
            else -> .40
        }
    }

    /**
     * Invoke only when both pools contain a card; a single available pool takes priority.
     * [recentKinds] contains answered learning cards in chronological order, including
     * returning calibration probes as REVIEW. [draw] is a stable value in [0, 1).
     */
    fun chooseReview(
        probability: Double,
        recentKinds: List<LearningCardKind>,
        draw: Double,
    ): Boolean = when {
        recentKinds.size >= 2 && recentKinds.takeLast(2).all { it == LearningCardKind.REVIEW } -> false
        recentKinds.size >= 3 && recentKinds.takeLast(3).all { it == LearningCardKind.NEW } -> true
        else -> draw < probability.coerceIn(0.0, 1.0)
    }

    /**
     * Positive weight for an eligible review. Errors contribute 1..5 times the base;
     * lateness contributes 1..3 times, reaching its cap 48 hours after eligibility.
     * Cooldown enforcement happens before this function; it cannot make a card eligible.
     */
    fun reviewWeight(errorRate: Double, overdueMillis: Long): Double {
        val error = errorRate.coerceIn(0.0, 1.0)
        val overdueDays = overdueMillis.coerceAtLeast(0L).toDouble() / DAY_MILLIS
        return (1.0 + 4.0 * error * error) * (1.0 + overdueDays.coerceAtMost(2.0))
    }
}
