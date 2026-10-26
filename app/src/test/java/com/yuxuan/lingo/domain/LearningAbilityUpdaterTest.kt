package com.yuxuan.lingo.domain

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LearningAbilityUpdaterTest {
    private fun history(difficulty: Double, vararg known: Boolean) =
        known.map { RecognitionEvidence(difficulty, it) }

    private fun gain(level: Double, difficulty: Int, history: List<RecognitionEvidence>): Double {
        val expected = LearningAbilityUpdater.expectedRecognition(level, difficulty.toDouble())
        return (LearningAbilityUpdater.nextLevel(level, difficulty, true, history) - level) / (1.0 - expected)
    }

    @Test fun sameBandHasSeventyPercentTargetAndVisibleBoundedUpdates() {
        assertEquals(.7, LearningAbilityUpdater.expectedRecognition(5.0, 5.0), 1e-12)
        assertEquals(5.036, LearningAbilityUpdater.nextLevel(5.0, 5, true, emptyList()), 1e-12)
        assertEquals(4.916, LearningAbilityUpdater.nextLevel(5.0, 5, false, emptyList()), 1e-12)
    }

    @Test fun everyAnswerHasTheCorrectDirectionAndCannotJumpPastTheStepCap() {
        val histories = listOf(emptyList(), List(20) { RecognitionEvidence(5.0, true) },
            List(20) { RecognitionEvidence(5.0, false) })
        for (level in listOf(1.0, 1.1, 3.0, 5.0, 7.0, 9.9, 10.0)) {
            for (difficulty in 1..10) for (evidence in histories) for (known in listOf(true, false)) {
                val next = LearningAbilityUpdater.nextLevel(level, difficulty, known, evidence)
                assertTrue(next in 1.0..10.0)
                assertTrue(abs(next - level) <= LearningAbilityUpdater.MAX_STEP + 1e-12)
                if (known && level < 10.0) assertTrue(next > level)
                if (!known && level > 1.0) assertTrue(next < level)
            }
        }
        assertEquals(10.0, LearningAbilityUpdater.nextLevel(10.0, 10, true, emptyList()), 0.0)
        assertEquals(1.0, LearningAbilityUpdater.nextLevel(1.0, 1, false, emptyList()), 0.0)
    }

    @Test fun stepCapPreservesZeroExpectedDriftWhenTheModelMatches() {
        val histories = listOf(emptyList(), List(20) { RecognitionEvidence(5.0, true) },
            List(20) { RecognitionEvidence(5.0, false) })
        for (level in listOf(2.25, 5.0, 7.75)) for (difficulty in 1..10) for (evidence in histories) {
            val p = LearningAbilityUpdater.expectedRecognition(level, difficulty.toDouble())
            val known = LearningAbilityUpdater.nextLevel(level, difficulty, true, evidence)
            val unknown = LearningAbilityUpdater.nextLevel(level, difficulty, false, evidence)
            assertEquals(level, p * known + (1.0 - p) * unknown, 1e-12)
        }
    }

    @Test fun onlyTheLastTwentyIndependentWordsAffectTheTrend() {
        val recent = List(20) { RecognitionEvidence(5.0, true) }
        val oppositeOldHistory = List(100) { RecognitionEvidence(5.0, false) } + recent
        assertEquals(LearningAbilityUpdater.nextLevel(5.0, 5, true, recent),
            LearningAbilityUpdater.nextLevel(5.0, 5, true, oppositeOldHistory), 0.0)
    }

    @Test fun trendNeedsEightPriorWordsAndTheCurrentOutcomeCannotChooseItsGain() {
        val early = List(7) { RecognitionEvidence(5.0, true) }
        val established = early + RecognitionEvidence(5.0, true)
        assertEquals(.12, gain(5.0, 5, early), 1e-12)
        assertTrue(gain(5.0, 5, established) > .12)
        val p = LearningAbilityUpdater.expectedRecognition(5.0, 5.0)
        val knownGain = gain(5.0, 5, established)
        val unknownGain = (5.0 - LearningAbilityUpdater.nextLevel(5.0, 5, false, established)) / p
        assertEquals(knownGain, unknownGain, 1e-12)
    }

    @Test fun balancedMatureEvidenceUsesTheBaseGainAndResolvedBiasStopsAcceleration() {
        val balanced = history(5.0, true, true, false, true, false, true, true, false, true, true)
        assertEquals(.12, gain(5.0, 5, balanced), 1e-12)
        val recognized = List(20) { RecognitionEvidence(5.0, true) }
        assertTrue(gain(5.0, 5, recognized) > .12)
        // The same observations become unsurprising after the estimate catches up.
        assertEquals(.12, gain(7.0, 7, recognized), 1e-12)
    }

    @Test fun sustainedMismatchCorrectsAnIncorrectStartingBandWithinFiftyWords() {
        // Fixed words from the initial band, with a deterministic 90% or 40% stream.
        // Those rates indicate roughly +1 or -1 ability band under this model.
        for (start in listOf(3.0, 5.0, 7.0)) {
            var higher = start
            var lower = start
            val highHistory = mutableListOf<RecognitionEvidence>()
            val lowHistory = mutableListOf<RecognitionEvidence>()
            repeat(50) { index ->
                val highKnown = index % 10 != 9
                val lowKnown = index % 5 < 2
                higher = LearningAbilityUpdater.nextLevel(higher, start.toInt(), highKnown, highHistory)
                lower = LearningAbilityUpdater.nextLevel(lower, start.toInt(), lowKnown, lowHistory)
                highHistory.add(RecognitionEvidence(start, highKnown))
                lowHistory.add(RecognitionEvidence(start, lowKnown))
            }
            assertTrue("upward correction from $start was ${higher - start}", higher - start > .5)
            assertTrue("downward correction from $start was ${lower - start}", start - lower > .7)
            assertTrue(higher - start < 1.3)
            assertTrue(start - lower < 1.4)
        }
    }

    @Test fun alternatingEvidenceConvergesAndSeventyPercentEvidenceDoesNotRunAway() {
        var alternating = 5.0
        var matched = 5.0
        val alternatingHistory = mutableListOf<RecognitionEvidence>()
        val matchedHistory = mutableListOf<RecognitionEvidence>()
        repeat(100) { index ->
            val halfKnown = index % 2 == 0
            val seventyKnown = index % 10 < 7
            alternating = LearningAbilityUpdater.nextLevel(alternating, 5, halfKnown, alternatingHistory)
            matched = LearningAbilityUpdater.nextLevel(matched, 5, seventyKnown, matchedHistory)
            alternatingHistory.add(RecognitionEvidence(5.0, halfKnown))
            matchedHistory.add(RecognitionEvidence(5.0, seventyKnown))
        }
        // 50% at difficulty 5 corresponds to level about 4.32, rather than level 5.
        assertEquals(4.32, alternating, .12)
        assertEquals(5.0, matched, .15)
    }
}
