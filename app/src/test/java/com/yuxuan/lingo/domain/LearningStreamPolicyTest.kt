package com.yuxuan.lingo.domain

import org.junit.Assert.*
import org.junit.Test

class LearningStreamPolicyTest {
    private val day = 24 * 60 * 60_000L

    @Test fun insufficientNewEvidenceKeepsDefaultProbability() {
        for (answers in listOf(emptyList(), List(7) { false }, List(7) { true })) {
            assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0, answers), 0.0)
        }
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, 0, List(8) { false }), 0.0)
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, 0, List(8) { true }), 0.0)
    }

    @Test fun backlogThresholdIncludesTwentyAndOverridesHighRecognition() {
        val allKnown = List(20) { true }
        assertEquals(.30, LearningStreamPolicy.reviewProbability(9, 0, allKnown), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(10, 0, allKnown), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(19, 0, allKnown), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(20, 0, allKnown), 0.0)
    }

    @Test fun overdueThresholdIncludesExactlyOneDay() {
        val allKnown = List(20) { true }
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, day - 1, allKnown), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, day, allKnown), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, day, emptyList()), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, -day, emptyList()), 0.0)
    }

    @Test fun recognitionThresholdsUseStrictLowAndInclusiveHighBoundaries() {
        fun probability(known: Int) = LearningStreamPolicy.reviewProbability(
            1, 0, List(20) { it < known },
        )
        assertEquals(.60, probability(11), 0.0)
        assertEquals(.40, probability(12), 0.0) // Exactly 60% is not low.
        assertEquals(.40, probability(16), 0.0)
        assertEquals(.30, probability(17), 0.0) // Exactly 85% qualifies.
    }

    @Test fun recognitionWindowOnlyUsesLastTwentyIndependentAnswers() {
        val oldKnown = List(100) { true }
        val oldUnknown = List(100) { false }
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, 0, oldKnown + List(20) { false }), 0.0)
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, 0, oldUnknown + List(20) { true }), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0, oldUnknown + List(20) { it < 12 }), 0.0)
    }

    @Test fun difficultyAdjustmentComparesAnswersWithTheirExpectedRecognition() {
        // Four of ten hard words is better than their predicted 20%, even though
        // the raw rate is below 60%. Seventeen easy words can still underperform.
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, 0,
            List(10) { it < 4 }, List(10) { .20 }), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0,
            List(20) { it < 17 }, List(20) { .95 }), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, 0,
            List(20) { it < 14 }, List(20) { .85 }), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0,
            List(20) { it < 10 }, List(20) { .50 }), 0.0)
    }

    @Test fun adjustedRecognitionNeedsEightAnswersAndUsesAlignedRecentWindows() {
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0,
            List(7) { false }, List(7) { 1.0 }), 0.0)
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0,
            List(30) { it >= 20 }, List(10) { 1.0 } + List(20) { .50 }), 0.0)
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, 0,
            List(8) { true }, List(8) { 0.0 }), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, 0,
            List(8) { false }, List(8) { 1.0 }), 0.0)
    }

    @Test fun adjustedThresholdsRemainStableAtFloatingPointBoundaries() {
        // 70% + (0 - 10%) equals the inclusive 60% boundary.
        assertEquals(.40, LearningStreamPolicy.reviewProbability(1, 0,
            List(20) { false }, List(20) { .10 }), 0.0)
        assertEquals(.30, LearningStreamPolicy.reviewProbability(1, 0,
            List(20) { it < 17 }, List(20) { .70 }), 0.0)
        // Pressure conditions still override a strong adjusted result.
        assertEquals(.60, LearningStreamPolicy.reviewProbability(20, 0,
            List(8) { true }, List(8) { .20 }), 0.0)
        assertEquals(.60, LearningStreamPolicy.reviewProbability(1, day,
            List(8) { true }, List(8) { .20 }), 0.0)
    }

    @Test fun mismatchedOrInvalidPredictionsAreRejectedInsteadOfSilentlyMisaligning() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningStreamPolicy.reviewProbability(1, 0, List(8) { true }, List(7) { .70 })
        }
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) {
                LearningStreamPolicy.reviewProbability(1, 0, List(8) { true }, List(8) { invalid })
            }
        }
    }

    @Test fun twoConsecutiveReviewsForceNewEvenForAReviewDraw() {
        val kinds = listOf(LearningCardKind.NEW, LearningCardKind.REVIEW, LearningCardKind.REVIEW)
        assertFalse(LearningStreamPolicy.chooseReview(.60, kinds, 0.0))
        assertFalse(LearningStreamPolicy.chooseReview(1.0, List(5) { LearningCardKind.REVIEW }, 0.0))
    }

    @Test fun threeConsecutiveNewCardsForceReviewEvenForANewDraw() {
        val kinds = listOf(LearningCardKind.REVIEW) + List(3) { LearningCardKind.NEW }
        assertTrue(LearningStreamPolicy.chooseReview(.30, kinds, .99))
        assertTrue(LearningStreamPolicy.chooseReview(0.0, List(5) { LearningCardKind.NEW }, .99))
    }

    @Test fun shorterOrInterruptedRunsUseStableDrawAndExactProbabilityBoundary() {
        val histories = listOf(
            emptyList(), listOf(LearningCardKind.REVIEW), List(2) { LearningCardKind.NEW },
            listOf(LearningCardKind.REVIEW, LearningCardKind.NEW, LearningCardKind.REVIEW),
            listOf(LearningCardKind.NEW, LearningCardKind.REVIEW, LearningCardKind.NEW),
        )
        for (history in histories) {
            assertTrue(LearningStreamPolicy.chooseReview(.40, history, .399999))
            assertFalse(LearningStreamPolicy.chooseReview(.40, history, .40))
            assertFalse(LearningStreamPolicy.chooseReview(.40, history, .999999))
        }
    }

    @Test fun reviewWeightHasPositiveBaseAndClampsErrorRate() {
        assertEquals(1.0, LearningStreamPolicy.reviewWeight(0.0, 0), 0.0)
        assertEquals(2.0, LearningStreamPolicy.reviewWeight(.5, 0), 0.0)
        assertEquals(5.0, LearningStreamPolicy.reviewWeight(1.0, 0), 0.0)
        assertEquals(1.0, LearningStreamPolicy.reviewWeight(-1.0, 0), 0.0)
        assertEquals(5.0, LearningStreamPolicy.reviewWeight(2.0, 0), 0.0)
    }

    @Test fun reviewWeightCapsLatenessWithoutBreakingCooldown() {
        assertEquals(1.0, LearningStreamPolicy.reviewWeight(0.0, -day), 0.0)
        assertEquals(1.5, LearningStreamPolicy.reviewWeight(0.0, day / 2), 0.0)
        assertEquals(2.0, LearningStreamPolicy.reviewWeight(0.0, day), 0.0)
        assertEquals(3.0, LearningStreamPolicy.reviewWeight(0.0, 2 * day), 0.0)
        assertEquals(3.0, LearningStreamPolicy.reviewWeight(0.0, Long.MAX_VALUE), 0.0)
        assertEquals(15.0, LearningStreamPolicy.reviewWeight(1.0, Long.MAX_VALUE), 0.0)
    }
}
