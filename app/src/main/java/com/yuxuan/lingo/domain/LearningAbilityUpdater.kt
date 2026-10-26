package com.yuxuan.lingo.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One independent first encounter, supplied before the answer being scored. */
internal data class RecognitionEvidence(val difficulty: Double, val known: Boolean)

/** Recognition model for stream balance and first-encounter unknown penalties.
 * Known answers and review mastery use the explicit LearningDistanceWeight reward. */
internal object LearningAbilityUpdater {
    const val MAX_STEP = .12
    private const val BASE_GAIN = .12
    private const val HISTORY_WINDOW = 20
    private const val MIN_TREND_SAMPLES = 8
    private const val TARGET_LOG_ODDS = .8472978603872037 // ln(.7 / .3)

    internal fun expectedRecognition(level: Double, difficulty: Double): Double =
        1.0 / (1.0 + exp(-1.25 * (level - difficulty) - TARGET_LOG_ODDS))

    fun nextLevel(
        current: Double,
        difficulty: Int,
        known: Boolean,
        history: List<RecognitionEvidence>,
    ): Double {
        val level = if (current.isFinite()) current.coerceIn(1.0, 10.0) else 3.0
        val expected = expectedRecognition(level, difficulty.coerceIn(1, 10).toDouble())
        val recent = history.filter { it.difficulty.isFinite() && it.difficulty in 1.0..10.0 }
            .takeLast(HISTORY_WINDOW)
        // Recompute old predictions at today's estimate: acceleration fades once the
        // estimate explains the evidence. Never let this answer choose its own gain.
        val bias = if (recent.size >= MIN_TREND_SAMPLES) recent.sumOf {
            (if (it.known) 1.0 else 0.0) - expectedRecognition(level, it.difficulty)
        } / recent.size else 0.0
        val acceleration = ((abs(bias) - .10) / .20).coerceIn(0.0, 1.0)
        val proposedGain = BASE_GAIN * (1.0 + acceleration)
        // Bound the gain before observing the outcome. Clipping a large negative
        // delta alone would make a correctly estimated 70% learner drift upward.
        val gain = min(proposedGain, MAX_STEP / max(expected, 1.0 - expected))
        val residual = (if (known) 1.0 else 0.0) - expected
        return (level + gain * residual).coerceIn(1.0, 10.0)
    }
}
