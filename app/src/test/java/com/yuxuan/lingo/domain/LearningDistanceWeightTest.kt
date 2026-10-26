package com.yuxuan.lingo.domain

import org.junit.Assert.*
import org.junit.Test

class LearningDistanceWeightTest {
    @Test fun distanceWeightsAreSymmetricAndLinearIncludingFractionalLevels() {
        for (difficulty in 3..7) {
            assertEquals(maxOf(.002, .02 * kotlin.math.abs(difficulty - 5)),
                LearningDistanceWeight.weight(5.0, difficulty), 1e-12)
        }
        assertEquals(.01, LearningDistanceWeight.weight(5.5, 5), 1e-12)
        assertEquals(.01, LearningDistanceWeight.weight(5.5, 6), 1e-12)
        assertEquals(.03, LearningDistanceWeight.weight(5.5, 4), 1e-12)
        assertEquals(.03, LearningDistanceWeight.weight(5.5, 7), 1e-12)
    }

    @Test fun newWindowUsesActualLevelRatherThanRoundedBand() {
        assertEquals(listOf(3, 4, 5, 6, 7), (1..10).filter { LearningDistanceWeight.isNewEligible(5.0, it) })
        assertEquals(listOf(4, 5, 6, 7), (1..10).filter { LearningDistanceWeight.isNewEligible(5.5, it) })
        assertEquals(listOf(1, 2, 3), (1..10).filter { LearningDistanceWeight.isNewEligible(1.0, it) })
        assertEquals(listOf(8, 9, 10), (1..10).filter { LearningDistanceWeight.isNewEligible(10.0, it) })
        assertFalse(LearningDistanceWeight.isNewEligible(5.0001, 3))
        assertFalse(LearningDistanceWeight.isNewEligible(5.0, 0))
        assertFalse(LearningDistanceWeight.isNewEligible(10.0, 11))
    }

    @Test fun oldFarAwayReviewsUseActualDistanceAndRewardStaysWithinBounds() {
        assertEquals(.18, LearningDistanceWeight.weight(1.0, 10), 1e-12)
        assertEquals(.18, LearningDistanceWeight.weight(10.0, 1), 1e-12)
        assertEquals(5.002, LearningDistanceWeight.rewardedLevel(5.0, 5), 1e-12)
        assertEquals(5.04, LearningDistanceWeight.rewardedLevel(5.0, 3), 1e-12)
        assertEquals(10.0, LearningDistanceWeight.rewardedLevel(9.99, 8), 0.0)
        assertEquals(10.0, LearningDistanceWeight.rewardedLevel(10.0, 10), 0.0)
    }

    @Test fun malformedLevelsUseTheInitialLevelAndFiniteLevelsAreClamped() {
        for (level in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(3.0, LearningDistanceWeight.safeLevel(level), 0.0)
            assertEquals(.002, LearningDistanceWeight.weight(level, 3), 0.0)
            assertEquals(3.002, LearningDistanceWeight.rewardedLevel(level, 3), 1e-12)
        }
        assertEquals(1.0, LearningDistanceWeight.safeLevel(-100.0), 0.0)
        assertEquals(10.0, LearningDistanceWeight.safeLevel(100.0), 0.0)
    }

    @Test fun normalizedDrawHasTheExpectedIntervalsAndEmptyPoolsEndCleanly() {
        // Weight 0.02 means 1/6 here, rather than a fixed 2% probability.
        val weights = listOf(.02, .04, .06)
        assertEquals(0, LearningDistanceWeight.drawIndex(weights, 0.0))
        assertEquals(0, LearningDistanceWeight.drawIndex(weights, .16))
        assertEquals(1, LearningDistanceWeight.drawIndex(weights, .17))
        assertEquals(1, LearningDistanceWeight.drawIndex(weights, .49))
        assertEquals(2, LearningDistanceWeight.drawIndex(weights, .51))
        assertEquals(2, LearningDistanceWeight.drawIndex(weights, .9999999999999999))
        assertNull(LearningDistanceWeight.drawIndex(emptyList(), .5))
    }

    @Test fun drawHandlesZeroInputsWhileSameAndVeryCloseLevelsKeepThePositiveFloor() {
        assertNull(LearningDistanceWeight.drawIndex(listOf(0.0, 0.0), .5))
        for (draw in listOf(0.0, .01, .5, .9999999999999999)) {
            assertEquals(1, LearningDistanceWeight.drawIndex(listOf(0.0, .02, 0.0), draw))
        }
        assertEquals(.002, LearningDistanceWeight.weight(5.0, 5), 0.0)
        assertEquals(.002, LearningDistanceWeight.weight(5.001, 5), 0.0)
        assertEquals(.002, LearningDistanceWeight.weight(5.1, 5), 1e-12)
        assertEquals(.004, LearningDistanceWeight.weight(5.2, 5), 1e-12)
    }
}
