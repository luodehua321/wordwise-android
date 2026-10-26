package com.yuxuan.lingo.domain

import kotlin.math.abs

/** Shared distance weight for formal learning; diagnostic calibration stays separate. */
internal object LearningDistanceWeight {
    const val BASE = .02
    const val MIN_WEIGHT = .002
    const val MAX_DISTANCE = 2.0

    fun safeLevel(level: Double): Double =
        if (level.isFinite()) level.coerceIn(1.0, 10.0) else LearningScheduler.INITIAL_LEVEL

    fun isNewEligible(level: Double, difficulty: Int): Boolean {
        val distance = abs(difficulty - safeLevel(level))
        return difficulty in 1..10 && distance <= MAX_DISTANCE
    }

    /** One level is 0.02; the small floor keeps same-level words drawable and rewarded. */
    fun weight(level: Double, difficulty: Int): Double =
        (BASE * abs(difficulty.coerceIn(1, 10) - safeLevel(level))).coerceAtLeast(MIN_WEIGHT)

    fun rewardedLevel(level: Double, difficulty: Int): Double {
        val current = safeLevel(level)
        return (current + weight(current, difficulty)).coerceAtMost(10.0)
    }

    /** Callers sort candidates by stable ID before drawing, so storage order cannot reroll. */
    fun drawIndex(weights: List<Double>, draw: Double): Int? {
        if (weights.isEmpty()) return null
        require(draw.isFinite() && draw >= 0.0 && draw < 1.0)
        require(weights.all { it.isFinite() && it >= 0.0 })
        val total = weights.sum()
        if (total == 0.0) return null
        var remaining = draw * total
        for (index in weights.indices) {
            remaining -= weights[index]
            if (remaining < 0.0) return index
        }
        return weights.indexOfLast { it > 0.0 } // Never return a zero-weight tail on rounding.
    }
}
