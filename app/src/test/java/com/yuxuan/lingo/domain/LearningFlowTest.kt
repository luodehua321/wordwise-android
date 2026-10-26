package com.yuxuan.lingo.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Persisted snapshots exercise the complete scheduling flow, including migration and undo. */
class LearningFlowTest {
    private val now = Instant.parse("2026-10-25T01:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val scheduler = LearningScheduler({ now }, zone)

    @Test fun threeAnsweredNewWordsForceADueReviewWhenBothPoolsExist() {
        val histories = (1..3).map { word("seen$it") }
        val state = snapshot(listOf(word("fresh"), word("due")) + histories).copy(
            progress = listOf(due("due")) + histories.map { cooling(it.id) },
            logs = histories.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) },
            profile = profile(3),
        )
        for (seed in 1L..100L) {
            assertEquals("seed=$seed", "due", scheduler.nextWord(state.withSeed(seed))?.id)
        }
    }

    @Test fun twoAnsweredReviewsForceANewWordWhenBothPoolsExist() {
        val state = snapshot(listOf(word("fresh"), word("due"))).copy(
            progress = listOf(due("due").copy(incorrectCount = 3)),
            logs = (1L..3L).map { AnswerLog(it, "due", false, now - LearningScheduler.REVIEW_DELAY) },
            profile = profile(3),
        )
        for (seed in 1L..100L) {
            assertEquals("seed=$seed", "fresh", scheduler.nextWord(state.withSeed(seed))?.id)
        }
    }

    @Test fun anEmptyReviewPoolStillAllowsNewAfterThreeNewAnswers() {
        val histories = (1..3).map { word("seen$it") }
        val state = snapshot(listOf(word("fresh")) + histories).copy(
            progress = histories.map { cooling(it.id) },
            logs = histories.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) },
            profile = profile(3),
        )
        assertEquals(0, scheduler.dueReviewCount(state))
        assertEquals("fresh", scheduler.nextWord(state)?.id)
    }

    @Test fun anEmptyNewPoolStillAllowsReviewAfterTwoReviews() {
        val state = snapshot(listOf(word("due"))).copy(
            progress = listOf(due("due").copy(incorrectCount = 3)),
            logs = (1L..3L).map { AnswerLog(it, "due", false, now - LearningScheduler.REVIEW_DELAY) },
            profile = profile(3),
        )
        assertEquals("due", scheduler.nextWord(state)?.id)
    }

    @Test fun aReturningCalibrationProbeFillsTheReviewSlotAndDoesNotRescoreAbility() {
        val histories = (1..3).map { word("seen$it") }
        val state = snapshot(listOf(word("fresh"), word("probe")) + histories).copy(
            progress = histories.map { cooling(it.id) },
            logs = histories.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) },
            profile = profile(3),
            calibration = CalibrationSession(
                "complete", now - DAY, 731L, listOf("probe"), 0,
                listOf(CalibrationAnswer("probe", false, now - DAY)), 1,
            ),
        )
        assertEquals(1, scheduler.dueReviewCount(state))
        assertEquals("probe", scheduler.nextWord(state)?.id)
        assertEquals(state.profile.level, scheduler.recordAnswer(state, "probe", true).profile.level, 0.0)
    }

    @Test fun answeredCalibrationReturnsCountAsReviewsForTheConsecutiveCardGuard() {
        val probes = listOf(word("probe1"), word("probe2"))
        val state = snapshot(listOf(word("fresh"), word("due")) + probes).copy(
            progress = listOf(due("due")) + probes.map { cooling(it.id) },
            logs = probes.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) },
            profile = profile(2),
            calibration = CalibrationSession(
                "complete", now - 2 * DAY, 731L, probes.map { it.id }, 0,
                probes.map { CalibrationAnswer(it.id, false, now - 2 * DAY) }, 2,
            ),
        )
        for (seed in 1L..100L) {
            assertEquals("seed=$seed", "fresh", scheduler.nextWord(state.withSeed(seed))?.id)
        }
    }

    @Test fun migratedProgressWithMissingEarlyLogsRemainsReviewEvidence() {
        val imported = (1..3).map { word("imported$it") }
        val state = snapshot(listOf(word("fresh"), word("due")) + imported).copy(
            // Each retained log is a later review: two earlier answers are missing.
            progress = listOf(due("due")) + imported.map { cooling(it.id).copy(incorrectCount = 3) },
            logs = imported.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) },
            profile = profile(20),
        )
        for (seed in 1L..100L) {
            assertEquals("seed=$seed", "fresh", scheduler.nextWord(state.withSeed(seed))?.id)
        }
        assertEquals(state.profile.level, scheduler.recordAnswer(state, "imported1", true).profile.level, 0.0)
    }

    @Test fun redisplayAndRestartKeepTheSameCardForTheSameSnapshotAndClock() {
        val state = snapshot((1..20).map { word("fresh$it") } + (1..5).map { word("due$it") }).copy(
            progress = (1..5).map { due("due$it") },
            profile = profile(17),
        )
        for (seed in 1L..100L) {
            val persisted = state.withSeed(seed)
            val first = scheduler.nextWord(persisted)
            assertNotNull(first)
            assertEquals(first, scheduler.nextWord(persisted))
            val restarted = LearningScheduler({ now }, zone)
            assertEquals(first, restarted.nextWord(persisted.copy(
                words = persisted.words.toList(), progress = persisted.progress.toList(),
                logs = persisted.logs.toList(), profile = persisted.profile.copy(),
                calibration = persisted.calibration!!.copy(),
            )))
        }
    }

    @Test fun masteredExcludedAndCoolingWordsDoNotIncreaseEligibleBacklogPressure() {
        val baseline = snapshot(listOf(word("fresh"), word("due"))).copy(progress = listOf(due("due")))
        val mastered = (1..25).map { word("mastered$it") }
        val excluded = (1..25).map { word("excluded$it") }
        val cooling = (1..25).map { word("cooling$it") }
        val expanded = baseline.copy(
            words = baseline.words + mastered + excluded + cooling,
            progress = baseline.progress + mastered.map {
                due(it.id).copy(correctCount = 2, masteredAt = now - 10 * DAY)
            } + excluded.map { due(it.id).copy(lastReviewedAt = now - 10 * DAY) } +
                cooling.map { cooling(it.id) },
            excludedWordIds = excluded.map { it.id }.toSet(),
        )
        assertEquals(1, scheduler.dueReviewCount(expanded))
        for (seed in 1L..500L) {
            assertEquals("seed=$seed", scheduler.nextWord(baseline.withSeed(seed)),
                scheduler.nextWord(expanded.withSeed(seed)))
        }
    }

    @Test fun fewerThanEightIndependentAnswersKeepTheDefaultMix() {
        val baseline = snapshot(listOf(word("fresh"), word("due"))).copy(
            progress = listOf(due("due")), profile = profile(8),
        )
        for (known in listOf(false, true)) {
            val histories = (1..7).map { word("seen$it") }
            val state = baseline.copy(
                words = baseline.words + histories,
                progress = baseline.progress + histories.mapIndexed { index, word ->
                    if (index == 0) cooling(word.id).copy(
                        correctCount = if (known) 1 else 0, incorrectCount = if (known) 1 else 2,
                    ) else cooling(word.id).copy(
                        correctCount = if (known) 1 else 0, incorrectCount = if (known) 0 else 1,
                        consecutiveKnown = if (known) 1 else 0,
                    )
                },
                // End with a review so neither consecutive-card guard is active.
                logs = histories.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, known, now) } +
                    AnswerLog(8, "seen1", false, now),
            )
            for (seed in 1L..500L) {
                assertEquals("known=$known seed=$seed", scheduler.nextWord(baseline.withSeed(seed)),
                    scheduler.nextWord(state.withSeed(seed)))
            }
        }
    }

    @Test fun twentyEligibleReviewsRaiseTheObservedReviewShareToSixtyPercent() {
        fun state(count: Int) = snapshot(listOf(word("fresh")) + (1..count).map { word("due$it") }).copy(
            progress = (1..count).map { due("due$it") },
        )
        val ordinary = reviewShare(state(19))
        val backlog = reviewShare(state(20))
        assertTrue("ordinary=$ordinary", ordinary in .36.. .44)
        assertTrue("backlog=$backlog", backlog in .56.. .64)
    }

    @Test fun runtimeMixCorrectsForTheDifficultyOfTheRecentIndependentWords() {
        fun state(difficulty: Int): LearningSnapshot {
            val histories = (1..8).map { word("seen$it").copy(difficulty = difficulty) }
            return snapshot(listOf(word("fresh"), word("due")) + histories).copy(
                progress = listOf(due("due").copy(incorrectCount = 2)) +
                    histories.mapIndexed { index, word -> cooling(word.id).copy(
                        correctCount = if (index < 2) 1 else 0,
                        incorrectCount = if (index < 2) 0 else 1,
                        consecutiveKnown = if (index < 2) 1 else 0,
                    ) },
                // One final review avoids both consecutive-card guards while preserving
                // eight aligned first-encounter samples (two known, six unknown).
                logs = histories.mapIndexed { index, word ->
                    AnswerLog(index + 1L, word.id, index < 2, now)
                } + AnswerLog(9, "due", false, now - LearningScheduler.REVIEW_DELAY),
                profile = profile(9),
            )
        }
        val expectedHard = reviewShare(state(5))
        val unexpectedlyWeak = reviewShare(state(3))
        assertTrue("hard=$expectedHard", expectedHard in .36.. .44)
        assertTrue("same=$unexpectedlyWeak", unexpectedlyWeak in .56.. .64)
    }

    @Test fun outOfOrderSnapshotLogsKeepTheSameEvidenceAndNextCard() {
        val histories = (1..3).map { word("seen$it") }
        val state = snapshot(listOf(word("fresh"), word("due")) + histories).copy(
            progress = listOf(due("due")) + histories.mapIndexed { index, word ->
                cooling(word.id).copy(incorrectCount = if (index == 0) 2 else 1)
            },
            logs = histories.mapIndexed { index, word -> AnswerLog(index + 1L, word.id, false, now) } +
                AnswerLog(4, "seen1", false, now),
            profile = profile(4),
        )
        for (seed in 1L..100L) {
            val ordered = state.withSeed(seed)
            val reversed = ordered.copy(logs = ordered.logs.reversed())
            assertEquals(scheduler.nextWord(ordered), scheduler.nextWord(reversed))
            assertEquals(scheduler.placementStatus(ordered), scheduler.placementStatus(reversed))
        }
    }

    @Test fun exactlyTwentyFourHoursOverdueRaisesTheObservedReviewShareToSixtyPercent() {
        fun state(overdue: Long) = snapshot(listOf(word("fresh"), word("due"))).copy(
            progress = listOf(due("due").copy(lastReviewedAt = now - LearningScheduler.REVIEW_DELAY - overdue)),
        )
        val before = reviewShare(state(DAY - 1))
        val overdue = reviewShare(state(DAY))
        assertTrue("before=$before", before in .36.. .44)
        assertTrue("overdue=$overdue", overdue in .56.. .64)
    }

    @Test fun undoRestoresTheSameNextCardAndAnswerEligibility() {
        val state = snapshot((1..10).map { word("fresh$it") } + word("due")).copy(
            progress = listOf(due("due")), profile = profile(5),
        )
        for (seed in 1L..100L) {
            val before = state.withSeed(seed)
            val card = requireNotNull(scheduler.nextWord(before))
            val answer = scheduler.recordAnswer(before, card.id, false)
            val after = before.copy(
                progress = before.progress.filterNot { it.wordId == card.id } + answer.progress,
                logs = before.logs + answer.log, profile = answer.profile,
            )
            val undone = after.copy(
                progress = after.progress.filterNot { it.wordId == card.id } + listOfNotNull(answer.previousProgress),
                logs = after.logs.filterNot { it.sequence == answer.log.sequence },
                profile = requireNotNull(answer.previousProfile),
            )
            assertEquals(before, undone)
            assertEquals(card, scheduler.nextWord(undone))
            assertEquals(scheduler.recordAnswer(before, card.id, true), scheduler.recordAnswer(undone, card.id, true))
        }
    }

    private fun reviewShare(snapshot: LearningSnapshot): Double = (1L..2_000L).count { seed ->
        scheduler.nextWord(snapshot.withSeed(seed))?.id?.startsWith("due") == true
    } / 2_000.0

    private fun snapshot(words: List<Word>) = LearningSnapshot(
        words = words, progress = emptyList(), logs = emptyList(), profile = profile(0),
        calibration = CalibrationSession("completed", now - DAY, 731L, emptyList(), 0),
    )

    private fun profile(sequence: Long) = UserProfile(level = 3.0, totalAnswers = sequence, createdAt = now - DAY)
    // A one-level gap keeps these stream-policy fixtures drawable under distance weighting.
    private fun word(id: String) = Word(id, id, "/test/", "n.", "示例", 4, 1)
    private fun due(id: String) = ReviewProgress(
        id, incorrectCount = 1, lastReviewedAt = now - LearningScheduler.REVIEW_DELAY, nextReviewAt = now,
    )
    private fun cooling(id: String) = ReviewProgress(
        id, incorrectCount = 1, lastReviewedAt = now, nextReviewAt = now + LearningScheduler.REVIEW_DELAY,
    )
    private fun LearningSnapshot.withSeed(seed: Long) = copy(calibration = requireNotNull(calibration).copy(seed = seed))

    companion object { private const val DAY = 86_400_000L }
}
