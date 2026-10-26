package com.yuxuan.lingo.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudyClockTest {
    private var elapsed = 0L
    private var wall = 1_700_000_000_000L
    private val clock = StudyClock({ elapsed }, { wall })
    private fun advance(ms: Long) { elapsed += ms; wall += ms }
    private fun start() { clock.setLearning(true); clock.setForeground(true) }

    @Test fun backgroundTimeIsExcludedAndResumingDoesNotBackfill() {
        start(); advance(20_000)
        assertEquals(20_000L, clock.setForeground(false)?.durationMillis)
        advance(300_000); assertNull(clock.setForeground(true))
        advance(5_000); assertEquals(5_000L, clock.setLearning(true)?.durationMillis)
    }
    @Test fun inactivityStopsAtSixtySecondsUntilAnotherInteraction() {
        start(); advance(90_000)
        assertEquals(60_000L, clock.setLearning(true)?.durationMillis)
        advance(10_000); assertNull(clock.interact())
        advance(5_000); assertEquals(5_000L, clock.setLearning(true)?.durationMillis)
    }
    @Test fun recordsAndProfilePagesDoNotAccumulateLearningTime() {
        start(); advance(2_000)
        assertEquals(2_000L, clock.setLearning(false)?.durationMillis)
        advance(10_000); assertNull(clock.interact())
        assertNull(clock.setLearning(true)); advance(3_000)
        assertEquals(3_000L, clock.setForeground(false)?.durationMillis)
    }
    @Test fun changingTheWallClockCannotCreateExtraOrNegativeDuration() {
        start(); elapsed += 1_000; wall -= 3_600_000
        assertEquals(1_000L, clock.setLearning(true)?.durationMillis)
        elapsed += 2_000; wall += 7_200_000
        assertEquals(2_000L, clock.setForeground(false)?.durationMillis)
    }
}
