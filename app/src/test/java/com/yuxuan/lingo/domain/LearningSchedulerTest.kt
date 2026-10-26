package com.yuxuan.lingo.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LearningSchedulerTest {
    @Test fun excludedWordsNeverReturnInNewReviewOrCalibrationPools() {
        val fresh = word("fresh", 4)
        val weak = word("weak", 4)
        val base = snapshot(listOf(fresh, weak), listOf(ReviewProgress(weak.id, incorrectCount = 5)))
        repeat(200) { sequence ->
            val state = base.copy(profile = base.profile.copy(totalAnswers = sequence.toLong()),
                excludedWordIds = setOf(weak.id))
            assertEquals(fresh.id, scheduler.nextWord(state)?.id)
            assertNull(scheduler.nextWord(state, true))
            assertEquals(0, scheduler.dueReviewCount(state))
        }
        val onlyReview = base.copy(excludedWordIds = setOf(fresh.id))
        assertEquals(weak.id, scheduler.nextWord(onlyReview)?.id)
        val none = base.copy(excludedWordIds = setOf(fresh.id, weak.id))
        assertNull(scheduler.nextWord(none))
        val calibration = base.copy(excludedWordIds = setOf(weak.id))
        assertEquals(listOf(fresh.id), scheduler.createCalibration(calibration, "safe", 123).wordIds)
    }



    private var now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()
    private val scheduler = LearningScheduler({ now }, ZoneId.of("Asia/Shanghai"))

    @Test fun calibrationStartsAtThreeThenUsesPosteriorAndBroadCoverage() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "adaptive", 731L))
        assertEquals(3, scheduler.nextWord(state)!!.difficulty)
        val bands = mutableSetOf<Int>()
        repeat(20) {
            val card = scheduler.nextWord(state)!!
            bands += card.difficulty
            state = state.answerProbe(card.id, card.difficulty <= 5)
        }
        assertTrue("Broad probes must check multiple bands", bands.size >= 4)
    }

    @Test fun calibrationBuildsTwentyUniqueProbesWithinValidBands() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "adaptive", 739L))
        val selected = mutableListOf<String>()
        var previous = 3
        repeat(20) { index ->
            val card = requireNotNull(scheduler.nextWord(state))
            selected += card.id
            assertTrue(card.difficulty in 1..10)
            assertTrue("Adjacent calibration probes must stay within two bands", abs(card.difficulty - previous) <= 2)
            previous = card.difficulty
            assertEquals(index, scheduler.placementStatus(state).sampleCount)
            state = state.answerProbe(card.id, card.difficulty <= 5)
        }
        assertEquals(20, selected.distinct().size)
        assertTrue(requireNotNull(state.calibration).isComplete)
        assertFalse(scheduler.placementStatus(state).isCalibrating)
    }

    @Test fun calibrationPrefersChineseAndBilingualExamplesWithoutLosingHardBands() {
        val complete = probeVocabulary().map { it.copy(examples = listOf(Example("A useful example.", "一个实用例句。"))) }
        val incomplete = complete.map { it.copy(id = it.id + "missing", word = it.word + "missing", rank = 1,
            examples = emptyList()) }
        val englishOnly = complete.map { it.copy(id = it.id + "english", word = it.word + "english", rank = 1,
            examples = listOf(Example("An English example.", ""))) }
        val noChinese = complete.map { it.copy(id = it.id + "plain", word = it.word + "plain",
            meaningZh = "missing translation", rank = 1) }
        var state = snapshot(complete + incomplete + englishOnly + noChinese)
        state = state.copy(calibration = scheduler.createCalibration(state, "rich", 731L))
        val bands = mutableSetOf<Int>()
        repeat(20) {
            val card = requireNotNull(scheduler.nextWord(state))
            assertTrue(card.id in complete.map { it.id })
            assertTrue(card.examples.any { it.en.isNotBlank() && it.zh.isNotBlank() })
            bands += card.difficulty
            state = state.answerProbe(card.id, true)
        }
        assertTrue("Enriched easy words cannot hide the hard bands", bands.any { it >= 9 })
    }

    @Test fun calibrationAcceptsTransitiveAndIntransitiveVerbsAndAvoidsFunctionWords() {
        val example = listOf(Example("We invest in the project.", "我们投资这个项目。"))
        val transitive = word("invest", 5).copy(partOfSpeech = "vt.", examples = example)
        val intransitive = word("arrive", 5).copy(partOfSpeech = "vi.", examples = example)
        val function = word("would", 5).copy(partOfSpeech = "v.", examples = example)
        val determiner = word("each", 5).copy(partOfSpeech = "adj. / adv. / pron.", examples = example)
        val bareNoun = word("unexplained", 5)
        val selected = (1L..20L).map { seed ->
            val state = snapshot(listOf(function, determiner, bareNoun, transitive, intransitive))
            val calibrated = state.copy(calibration = scheduler.createCalibration(state, "verbs", seed))
            requireNotNull(scheduler.nextWord(calibrated)).id
        }
        assertEquals(setOf("invest", "arrive"), selected.toSet())
    }

    @Test fun calibrationPrefersBaseFormsAndAvoidsAnAssignedWordsFamily() {
        val base = word("learn", 5).copy(wordForms = listOf(WordForm("learning", "现在分词")),
            derivatives = listOf(Derivative("learner", "学习者")))
        val inflection = word("learning", 5)
        val derivative = word("learner", 5)
        val independent = word("garden", 5, 999)
        val initial = snapshot(listOf(base, inflection))
        assertEquals("learn", scheduler.createCalibration(initial, "base", 1L).wordIds.single())
        // A restored session may have started on a derivative. Reverse links must still
        // connect it to the base and sibling inflections before selecting the next probe.
        val restored = snapshot(listOf(derivative, base, inflection, independent)).copy(calibration =
            CalibrationSession("restored", now, 731L, listOf(derivative.id), 0,
                listOf(CalibrationAnswer(derivative.id, true, now)), 4))
        assertEquals(independent.id, scheduler.nextCalibrationWord(restored)?.id)
    }

    @Test fun calibrationSelectionSurvivesVocabularyReorderingAndSessionRestore() {
        val initial = snapshot(probeVocabulary())
        val session = scheduler.createCalibration(initial, "stable", 735L)
        assertEquals(session.wordIds, scheduler.createCalibration(initial.copy(words = initial.words.reversed()), "stable", 735L).wordIds)
        var state = initial.copy(calibration = session)
        repeat(12) { index ->
            val card = requireNotNull(scheduler.nextWord(state))
            state = state.answerProbe(card.id, index % 3 != 0)
            val queued = scheduler.nextCalibrationWord(state)
            val restored = LearningScheduler({ now }, ZoneId.of("Asia/Shanghai"))
            assertEquals(queued, restored.nextCalibrationWord(state.copy(words = state.words.reversed())))
            // Also reproduce the unpersisted adaptive choice, not just a queued word ID.
            val pending = state.copy(calibration = state.calibration!!.copy(wordIds = state.calibration!!.wordIds.take(state.calibration!!.answers.size)))
            assertEquals(scheduler.nextCalibrationWord(pending), restored.nextCalibrationWord(pending.copy(words = pending.words.reversed())))
        }
        val starts = (1L..30L).map { scheduler.createCalibration(initial, "random", it).wordIds.single() }
        assertTrue("Different installations should not all receive the same first word", starts.toSet().size > 10)
    }

    @Test fun calibrationLimitsDifficultyJumpsForDifferentAbilityPatterns() {
        for (ability in listOf(1, 5, 9)) {
            for (seed in listOf(1L, 731L, 739L)) {
                var state = snapshot(probeVocabulary())
                state = state.copy(calibration = scheduler.createCalibration(state, "bounded", seed))
                val bands = mutableListOf<Int>()
                repeat(20) {
                    val card = requireNotNull(scheduler.nextWord(state))
                    bands += card.difficulty
                    state = state.answerProbe(card.id, card.difficulty <= ability)
                }
                assertTrue("ability=$ability seed=$seed bands=$bands", bands.zipWithNext().all { (a, b) -> abs(a - b) <= 2 })
                assertTrue("Probe multiple appropriate bands: $bands", bands.toSet().size >= minOf(ability + 2, 4))
            }
        }
    }

    @Test fun calibrationCapsProbesForMidLevelLearners() {
        for ((ability, cap) in listOf(3 to 8, 4 to 8, 5 to 8, 7 to 9)) {
            for (seed in listOf(1L, 731L, 739L)) {
                var state = snapshot(probeVocabulary())
                state = state.copy(calibration = scheduler.createCalibration(state, "gentle", seed))
                val bands = mutableListOf<Int>()
                repeat(20) {
                    val card = requireNotNull(scheduler.nextWord(state))
                    bands += card.difficulty
                    state = state.answerProbe(card.id, card.difficulty <= ability)
                }
                assertTrue("ability=$ability cap=$cap seed=$seed bands=$bands",
                    bands.max() <= cap)
            }
        }
    }

    @Test fun advancedCalibrationCanStillReachLevelTen() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "advanced", 731L))
        val bands = mutableListOf<Int>()
        repeat(20) {
            val card = requireNotNull(scheduler.nextWord(state))
            bands += card.difficulty
            state = state.answerProbe(card.id, true)
        }
        assertTrue("An advanced learner should still see the top band: $bands", bands.contains(10))
    }

    @Test fun calibrationFirstFourProbesRampGently() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "ramp", 731L))
        val bands = mutableListOf<Int>()
        repeat(4) {
            val card = requireNotNull(scheduler.nextWord(state))
            bands += card.difficulty
            state = state.answerProbe(card.id, true)
        }
        assertEquals(3, bands.first())
        assertTrue("First probes must not jump more than one band: $bands",
            bands.zipWithNext().all { (a, b) -> abs(b - a) <= 1 })
    }

    @Test fun calibrationNeedsTwoKnownWordsBeforeUnlockingAHarderBand() {
        for (seed in listOf(1L, 731L, 739L)) {
            var state = snapshot(probeVocabulary())
            state = state.copy(calibration = scheduler.createCalibration(state, "confirmed", seed))
            repeat(20) { index ->
                val card = requireNotNull(scheduler.nextCalibrationWord(state))
                val answers = requireNotNull(state.calibration).answers
                if (card.difficulty > 3) {
                    val evidence = answers.count { answer ->
                        answer.known && state.words.first { it.id == answer.wordId }.difficulty >= card.difficulty - 1
                    }
                    assertTrue("question=${index + 1} difficulty=${card.difficulty} evidence=$evidence", evidence >= 2)
                }
                if (index < 6) assertTrue(card.difficulty <= 5)
                if (card.difficulty == 10) assertTrue(index >= 12)
                state = state.answerProbe(card.id, true)
            }
        }
    }

    @Test fun anUnknownCalibrationAnswerCannotTriggerAHarderCoverageProbe() {
        for (seed in listOf(1L, 731L, 739L)) {
            var state = snapshot(probeVocabulary())
            state = state.copy(calibration = scheduler.createCalibration(state, "ease", seed))
            var previous: Word? = null
            var wasKnown = true
            repeat(20) { index ->
                val card = requireNotNull(scheduler.nextCalibrationWord(state))
                if (!wasKnown) assertTrue("previous=$previous next=$card", card.difficulty <= previous!!.difficulty)
                previous?.let { assertTrue(card.difficulty <= it.difficulty + 1) }
                wasKnown = index % 4 != 1
                state = state.answerProbe(card.id, wasKnown)
                previous = card
            }
        }
    }

    @Test fun restoredHighDifficultyQueueIsReplacedUntilEvidenceSupportsIt() {
        val initial = snapshot(probeVocabulary())
        val hard = initial.words.first { it.difficulty == 10 }
        var state = initial.copy(calibration = CalibrationSession("legacy", now, 731L, listOf(hard.id), 0))
        assertEquals(3, requireNotNull(scheduler.nextCalibrationWord(state)).difficulty)
        val first = initial.words.first { it.difficulty == 3 }
        state = state.copy(calibration = state.calibration!!.copy(wordIds = listOf(first.id, hard.id),
            answers = listOf(CalibrationAnswer(first.id, true, now))))
        assertEquals(3, requireNotNull(scheduler.nextCalibrationWord(state)).difficulty)
    }

    @Test fun calibrationCountsOnlyEligibleUniqueWordsAndFinishesWhenTheyRunOut() {
        val words = listOf(word("available", 3), word("excluded", 3), word("mastered", 3), word("available", 3))
        var state = snapshot(words, listOf(ReviewProgress("mastered", correctCount = 2)))
            .copy(excludedWordIds = setOf("excluded"))
        state = state.copy(calibration = scheduler.createCalibration(state, "eligible", 731L))
        assertEquals(1, requireNotNull(state.calibration).targetSamples)
        assertEquals(1, scheduler.placementStatus(state).maxSamples)
        state = state.answerProbe("available", false)
        assertFalse(scheduler.placementStatus(state).isCalibrating)
        assertNull(scheduler.nextCalibrationWord(state))

        val stale = state.copy(calibration = state.calibration!!.copy(targetSamples = 20))
        assertFalse(scheduler.placementStatus(stale).isCalibrating)
        assertNull(scheduler.nextCalibrationWord(stale))
    }

    @Test fun removingAllRemainingCalibrationCandidatesEndsTheDiagnostic() {
        var state = snapshot(listOf(word("first", 3), word("remaining", 3)))
        state = state.copy(calibration = scheduler.createCalibration(state, "removed", 731L))
        val first = requireNotNull(scheduler.nextCalibrationWord(state))
        state = state.answerProbe(first.id, true)
        state = state.copy(excludedWordIds = state.words.filterNot { it.id == first.id }.map { it.id }.toSet())
        assertEquals(1, scheduler.placementStatus(state).maxSamples)
        assertFalse(scheduler.placementStatus(state).isCalibrating)
        assertNull(scheduler.nextCalibrationWord(state))
    }

    @Test fun sparseCalibrationUsesTheClosestEasierFallbackBeforeRemoteHardWords() {
        val current = word("current", 3)
        val easy = word("easy", 1)
        val hard = word("hard", 10)
        val state = snapshot(listOf(current, easy, hard)).copy(calibration = CalibrationSession(
            "sparse-fallback", now, 731L, listOf(current.id), 0,
            listOf(CalibrationAnswer(current.id, true, now)), 3))
        assertEquals(easy.id, scheduler.nextCalibrationWord(state)?.id)
    }

    @Test fun sparseCalibrationFallsBackToAvailableWordsAndCompletesWithoutRepeats() {
        val words = listOf(word("the", 1).copy(partOfSpeech = "art."), word("arcane", 10))
        var state = snapshot(words)
        state = state.copy(calibration = scheduler.createCalibration(state, "sparse", 1L))
        val selected = mutableSetOf<String>()
        repeat(2) {
            val card = requireNotNull(scheduler.nextWord(state))
            assertTrue(selected.add(card.id))
            state = state.answerProbe(card.id, false)
        }
        assertEquals(2, scheduler.placementStatus(state).sampleCount)
        assertFalse(scheduler.placementStatus(state).isCalibrating)
        assertTrue(state.logs.isEmpty())
        assertTrue(state.progress.isEmpty())
        assertNull(scheduler.nextWord(state))
        now += DAY
        assertNotNull(scheduler.nextWord(state))
    }

    @Test fun twentyUnknownCalibrationAnswersNeverRepeatOrCreateLearningRecords() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "random", 731L))
        val selected = mutableListOf<String>()
        repeat(20) { index ->
            val card = requireNotNull(scheduler.nextWord(state))
            selected += card.id
            assertEquals(index, scheduler.placementStatus(state).sampleCount)
            assertEquals(card.id, scheduler.nextWord(state)?.id) // display reads are stable
            state = state.answerProbe(card.id, false)
            assertTrue(state.logs.isEmpty())
            assertTrue(state.progress.isEmpty())
            assertEquals(0L, state.profile.totalAnswers)
            assertEquals(index < 19, scheduler.placementStatus(state).isCalibrating)
        }
        assertEquals(20, selected.distinct().size)
        assertNotNull(scheduler.nextWord(state))
    }

    @Test fun twentyBroadSamplesSeparateBeginnerIntermediateAndAdvanced() {
        val estimates = listOf(1, 5, 9).map { ability ->
            var state = snapshot(probeVocabulary())
            state = state.copy(calibration = scheduler.createCalibration(state, "random", 731L))
            repeat(20) {
                val card = requireNotNull(scheduler.nextWord(state))
                state = state.answerProbe(card.id, card.difficulty <= ability)
            }
            val status = scheduler.placementStatus(state)
            assertEquals(20, status.sampleCount)
            assertFalse(status.isCalibrating)
            assertTrue("ability=$ability estimate=${status.estimatedLevel}", abs(status.estimatedLevel - ability) < 1.5)
            val next = requireNotNull(scheduler.nextWord(state))
            assertTrue("ability=$ability estimate=${status.estimatedLevel} next=${next.difficulty}",
                abs(next.difficulty - status.estimatedLevel) <= 2.0)
            status.estimatedLevel
        }
        assertTrue(estimates.zipWithNext().all { (a, b) -> b - a > 2.0 })
    }

    @Test fun reviewModeIgnoresAnActiveDiagnosticQueue() {
        val weak = word("weak")
        var state = snapshot(probeVocabulary() + weak, listOf(ReviewProgress("weak", incorrectCount = 2)))
        state = state.copy(calibration = scheduler.createCalibration(state, "random", 731L))
        assertEquals("weak", scheduler.nextWord(state, reviewOnly = true)?.id)
        assertNotEquals("weak", scheduler.nextWord(state)?.id)
    }









    @Test fun unknownRequiresBothTimeAndCardSpacingDuringRapidStudy() {
        var state = snapshot(probeVocabulary()).let { it.copy(profile = it.profile.copy(level = 5.0)) }
        val first = scheduler.nextWord(state)!!
        state = state.apply(scheduler.recordAnswer(state, first.id, false))
        repeat(25) {
            val next = scheduler.nextWord(state)!!
            assertNotEquals(first.id, next.id)
            state = state.apply(scheduler.recordAnswer(state, next.id, true))
        }
        assertNull(scheduler.nextWord(state, true))
        now += 30 * 60_000
        assertEquals(first.id, scheduler.nextWord(state, true)?.id)
    }







    @Test fun repeatedDisplayDoesNotRerollANewLearningWord() {
        val state = snapshot(probeVocabulary())
        assertEquals(scheduler.nextWord(state), scheduler.nextWord(state))
    }











    @Test fun undoAndCorrectedFirstAnswerUseExactlyOneIndependentObservation() {
        val before = snapshot(probeVocabulary()).let { it.copy(profile = it.profile.copy(level = 5.0)) }
        val card = before.words.first { it.difficulty == 5 }
        val mistaken = scheduler.recordAnswer(before, card.id, false)
        val after = before.apply(mistaken)
        // Match the repository transaction: remove this log/progress and restore the old profile.
        val undone = after.copy(
            logs = after.logs.filterNot { it.sequence == mistaken.log.sequence },
            progress = after.progress.filterNot { it.wordId == card.id } + listOfNotNull(mistaken.previousProgress),
            profile = requireNotNull(mistaken.previousProfile),
        )
        assertEquals(before, undone)
        val corrected = scheduler.recordAnswer(undone, card.id, true)
        assertEquals(scheduler.recordAnswer(before, card.id, true), corrected)
        assertEquals(before.profile.totalAnswers + 1, corrected.profile.totalAnswers)
        val correctedState = undone.apply(corrected)
        val repeat = scheduler.recordAnswer(correctedState, card.id, false)
        assertEquals("Repeating the corrected word is a review, not a second ability sample",
            corrected.profile.level, repeat.profile.level, .000001)
    }

    @Test fun calibrationWordsDoNotBecomeDuplicateAbilitySamplesWhenReviewed() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "once", 731L))
        repeat(20) {
            val card = requireNotNull(scheduler.nextWord(state))
            state = state.answerProbe(card.id, card.difficulty <= 5)
        }
        val baseline = state.profile.level
        requireNotNull(state.calibration).answers.forEach { diagnostic ->
            state = state.apply(scheduler.recordAnswer(state, diagnostic.wordId, !diagnostic.known))
        }
        assertEquals(baseline, state.profile.level, .000001)
    }

    @Test fun aCompletedInitialCalibrationStaysCompleteAfterLearningAndDayChanges() {
        var state = snapshot(probeVocabulary())
        state = state.copy(calibration = scheduler.createCalibration(state, "only-first-use", 731L))
        repeat(20) { val card = requireNotNull(scheduler.nextWord(state)); state = state.answerProbe(card.id, true) }
        val initial = state.calibration
        repeat(30) {
            val card = requireNotNull(scheduler.nextWord(state))
            state = state.apply(scheduler.recordAnswer(state, card.id, it % 3 != 0))
        }
        now += 200 * DAY
        assertFalse(scheduler.placementStatus(state).isCalibrating)
        assertEquals(initial, state.calibration)
        assertEquals(20, scheduler.placementStatus(state).sampleCount)
    }

    @Test fun legacyCompletedMarkerDescribesOngoingLearningRatherThanAnEmptyDictionary() {
        val status = scheduler.placementStatus(snapshot(probeVocabulary()))
        assertFalse(status.isCalibrating)
        assertEquals("持续更新", status.confidenceLabel)
        assertEquals("3.0 级", status.levelLabel)
    }

    @Test fun focusedReviewContainsOnlyUnmasteredWordsWithUnknownHistory() {
        val state = snapshot(listOf(word("new"), word("known"), word("mastered"), word("mistake", 9)), listOf(
            ReviewProgress("known", correctCount = 1),
            ReviewProgress("mastered", correctCount = 5, incorrectCount = 1, masteredAt = now),
            ReviewProgress("mistake", incorrectCount = 2, nextReviewAt = now - 1),
        ))
        assertEquals("mistake", scheduler.nextWord(state, reviewOnly = true)?.id)
    }

    @Test fun tinyFocusedReviewWaitsFiveMinutesAndDoesNotRequireTwentyOtherAnswers() {
        var state = snapshot(listOf(word("weak"), word("strong")))
        state = state.apply(scheduler.recordAnswer(state, "weak", false))
        state = state.apply(scheduler.recordAnswer(state, "strong", false))
        assertNull(scheduler.nextWord(state, true))
        assertNull(scheduler.nextWord(state))
        now += LearningScheduler.REVIEW_DELAY - 1
        assertNull(scheduler.nextWord(state, true))
        now++
        assertEquals(2, scheduler.dueReviewCount(state, true))
        assertTrue(scheduler.nextWord(state, true)?.id in setOf("weak", "strong"))
    }











    @Test fun exhaustedBandAdvancesAndEmptyVocabularyEndsCleanly() {
        val state = snapshot(listOf(word("easy"), word("next", 2)), listOf(
            ReviewProgress("easy", correctCount = 4, masteredAt = now, nextReviewAt = now + DAY)))
        assertEquals("next", scheduler.nextWord(state)?.id)
        assertNull(scheduler.nextWord(snapshot(emptyList())))
        assertNull(scheduler.nextWord(snapshot(emptyList()), true))
    }

    @Test fun tinyCalibrationCompletesWithoutAnInfiniteDiagnosticLoop() {
        var state = snapshot(listOf(word("apple")))
        state = state.copy(calibration = scheduler.createCalibration(state, "tiny", 1L))
        assertEquals(1, scheduler.placementStatus(state).maxSamples)
        state = state.answerProbe("apple", false)
        assertFalse(scheduler.placementStatus(state).isCalibrating)
        assertNull(scheduler.nextWord(state))
        now += DAY
        assertEquals("apple", scheduler.nextWord(state)?.id)
    }

    @Test fun calibrationKnownWordsReturnAfterADayAndNeverDisappearForever() {
        val w = word("apple", 5)
        var state = snapshot(listOf(w)).copy(calibration = CalibrationSession(
            "done", now, 99, listOf(w.id), 0, listOf(CalibrationAnswer(w.id, true, now)), 1))
        assertNull(scheduler.nextWord(state))
        now += DAY
        assertEquals(w.id, scheduler.nextWord(state)?.id)
        state = state.apply(scheduler.recordAnswer(state, w.id, true))
        assertNull(scheduler.nextWord(state))
        now += DAY
        assertEquals(w.id, scheduler.nextWord(state)?.id)
    }

    @Test fun calibrationUnknownWordsAlsoWaitADaySoTheyCannotLeakIntoTheFirstSession() {
        val w = word("apple", 5)
        val state = snapshot(listOf(w)).copy(calibration = CalibrationSession(
            "done", now, 99, listOf(w.id), 0, listOf(CalibrationAnswer(w.id, false, now)), 1))
        now += LearningScheduler.REVIEW_DELAY
        assertNull(scheduler.nextWord(state))
        assertEquals(0, scheduler.dueReviewCount(state))
        now += DAY
        assertEquals(w.id, scheduler.nextWord(state)?.id)
    }



    @Test fun wholeBandRandomizationDiffersAcrossInstallationsAndIsStableOnRedisplay() {
        val pool = (1..300).map { word("word$it", 5, it) }
        val selected = (1..50).map { seed ->
            val state = snapshot(pool).let { it.copy(profile = it.profile.copy(level = 4.0, createdAt = seed.toLong()),
                calibration = it.calibration!!.copy(seed = seed.toLong())) }
            val card = scheduler.nextWord(state)!!
            assertEquals(card, scheduler.nextWord(state))
            card.rank
        }
        assertTrue(selected.toSet().size > 30)
        assertTrue(selected.any { it > 40 })
    }


    @Test fun knownWaitsAnHourUnknownWaitsFiveMinutes() {
        for (known in listOf(true,false)) {
            val before=snapshot(listOf(word("sample",4)))
            val after=before.apply(scheduler.recordAnswer(before,"sample",known))
            val delay=if(known) 3_600_000L else 300_000L
            now+=delay-1
            assertNull(scheduler.nextWord(after))
            assertEquals(0,scheduler.dueReviewCount(after))
            now++
            assertEquals("sample",scheduler.nextWord(after)?.id)
        }
    }
    @Test fun twoTotalRecognitionsRetireEvenWithInterveningMistakes() {
        var state=snapshot(listOf(word("sample",3)))
        for(known in listOf(true,false,true)) {
            state=state.apply(scheduler.recordAnswer(state,"sample",known));now+=3_600_000L
        }
        assertTrue(state.progress.single().isMastered)
        now+=30*DAY
        assertNull(scheduler.nextWord(state));assertNull(scheduler.nextWord(state,true))
        assertEquals(0,scheduler.dueReviewCount(state))
    }
    @Test fun historicalTwoRecognitionsAreAlsoRetiredAndCannotLapseBack() {
        val state=snapshot(listOf(word("sample",3)),listOf(ReviewProgress("sample",correctCount=2,incorrectCount=5)))
        assertNull(scheduler.nextWord(state))
        assertTrue(scheduler.recordAnswer(state,"sample",false).progress.isMastered)
    }
    @Test fun mixedStreamHasFortyPercentDueUnfamiliarWords() {
        val state=snapshot(listOf(word("fresh",4),word("weak",4)),listOf(ReviewProgress("weak",incorrectCount=1,
            lastReviewedAt=now-LearningScheduler.REVIEW_DELAY,nextReviewAt=now)))
        var reviews=0
        repeat(10000) { i ->
            if(scheduler.nextWord(state.copy(profile=state.profile.copy(totalAnswers=i.toLong())))?.id=="weak")reviews++
        }
        assertTrue("reviews=$reviews",reviews in 3800..4200)
    }
    @Test fun newWordsFollowDistanceWeightsAndStayStableOnRedisplay() {
        val state=snapshot((1..10).map { word("grade$it", it) }).let { it.copy(profile=it.profile.copy(level=5.0)) }
        val counts = IntArray(11)
        repeat(10000) { i ->
            val current=state.copy(profile=state.profile.copy(totalAnswers=i.toLong()))
            val card=scheduler.nextWord(current)!!
            assertEquals(card,scheduler.nextWord(current))
            assertTrue("new difficulty=${card.difficulty}", card.difficulty in 3..7)
            counts[card.difficulty]++
        }
        for (grade in 3..7) {
            val expected = maxOf(.1, abs(grade - 5).toDouble()) / 6.1
            assertEquals("grade=$grade counts=${counts.toList()}", expected, counts[grade] / 10000.0,
                if (grade == 5) .006 else .02)
        }
    }

    @Test fun levelSixNeverExploresTenWhenNearbyUnseenWordsExist() {
        val state = snapshot(probeVocabulary()).let { it.copy(profile = it.profile.copy(level = 6.0)) }
        val selected = (0 until 2_000).map { sequence ->
            val current = state.copy(profile = state.profile.copy(totalAnswers = sequence.toLong()))
            requireNotNull(scheduler.nextWord(current)).difficulty
        }
        assertTrue(selected.all { it in 4..8 })
        assertTrue("Exploration should still occur", selected.any { it > 6 })
    }

    @Test fun fractionalLevelExplorationStaysWithinTwoBandsOfTheEstimate() {
        val state = snapshot(probeVocabulary()).let { it.copy(profile = it.profile.copy(level = 5.5)) }
        val selected = (0 until 2_000).map { sequence ->
            requireNotNull(scheduler.nextWord(state.copy(
                profile = state.profile.copy(totalAnswers = sequence.toLong())))).difficulty
        }
        assertTrue(selected.all { it in 4..7 })
        assertEquals(setOf(4, 5, 6, 7), selected.toSet())
        assertTrue(selected.any { it == 7 })
    }

    @Test fun legacyLearnersBuildConfidenceFromIndependentLearningWithoutCalibrationAnswers() {
        var state = snapshot((1..20).map { word("evidence$it", 3, it) })
        assertEquals(0.0, scheduler.placementStatus(state).confidence, 0.0)
        state.words.forEachIndexed { index, word ->
            state = state.apply(scheduler.recordAnswer(state, word.id, index % 3 != 0))
        }
        val status = scheduler.placementStatus(state)
        assertFalse(status.isCalibrating)
        assertEquals(0, status.sampleCount)
        assertTrue("Independent answers must contribute confidence", status.confidence > 0.0)
    }
    @Test fun newKnownRewardsUseSymmetricDistanceWhileUnknownKeepsItsDifficultyPenalty() {
        fun change(grade:Int,known:Boolean):Double {
            val state=snapshot(listOf(word("sample",grade))).let {it.copy(profile=it.profile.copy(level=5.0))}
            return scheduler.recordAnswer(state,"sample",known).profile.level-5.0
        }
        assertEquals(.002, change(5, true), 1e-12)
        assertEquals(.02, change(4, true), 1e-12)
        assertEquals(.02, change(6, true), 1e-12)
        assertEquals(.04, change(3, true), 1e-12)
        assertEquals(.04, change(7, true), 1e-12)
        assertTrue(abs(change(1,false))>abs(change(5,false)))
        assertTrue(abs(change(5,false))>abs(change(9,false)))
        assertTrue(change(9,true)>0);assertTrue(change(9,false)<0)
    }
    @Test fun firstReviewMasteryEarnsOneDistanceRewardAndLaterRecognitionsCannotFarmPoints() {
        val state=snapshot(listOf(word("sample",7))).let{it.copy(profile=it.profile.copy(level=5.0))}
        val first=state.apply(scheduler.recordAnswer(state,"sample",true))
        now+=3_600_000L
        val second=scheduler.recordAnswer(first,"sample",true)
        assertEquals(5.04, first.profile.level, 1e-12)
        assertEquals(5.0792,second.profile.level,1e-12)
        assertTrue(second.progress.isMastered)
        assertFalse(requireNotNull(second.previousProgress).isMastered)
        val after = first.apply(second)
        assertEquals(after.profile.level, scheduler.recordAnswer(after, "sample", true).profile.level, 0.0)
        val undone = after.copy(progress = listOf(requireNotNull(second.previousProgress)),
            logs = after.logs.dropLast(1), profile = requireNotNull(second.previousProfile))
        assertEquals(first, undone)
        assertEquals(second, scheduler.recordAnswer(undone, "sample", true))
    }

    @Test fun newWordsNeverFallBackOutsideTwoLevelsEvenWhenTheNearbyPoolIsEmpty() {
        val state = snapshot(listOf(word("far", 8))).copy(profile = UserProfile(level = 5.0))
        assertNull(scheduler.nextWord(state))
        val review = state.copy(progress = listOf(ReviewProgress("far", incorrectCount = 1)))
        assertEquals("far", scheduler.nextWord(review)?.id)
        assertEquals("far", scheduler.nextWord(review, true)?.id)
    }

    @Test fun exactSameLevelKeepsDrawRewardAndDueCountBeforeAndAfterLevelChanges() {
        val initial = snapshot(listOf(word("same", 5))).copy(profile = UserProfile(level = 5.0))
        assertEquals("same", scheduler.nextWord(initial)?.id)
        val state = initial.copy(progress = listOf(ReviewProgress("same", incorrectCount = 1)))
        assertEquals("same", scheduler.nextWord(state)?.id)
        assertEquals("same", scheduler.nextWord(state, true)?.id)
        assertEquals(1, scheduler.dueReviewCount(state))
        assertEquals(1, scheduler.dueReviewCount(state, true))
        val shifted = state.copy(profile = state.profile.copy(level = 5.25))
        assertEquals("same", scheduler.nextWord(shifted)?.id)
        assertEquals("same", scheduler.nextWord(shifted, true)?.id)
        assertEquals(1, scheduler.dueReviewCount(shifted))
        assertEquals(1, scheduler.dueReviewCount(shifted, true))
        assertEquals(5.002, scheduler.recordAnswer(initial, "same", true).profile.level, 1e-12)
        val oneKnown = state.copy(progress = listOf(ReviewProgress("same", correctCount = 1)))
        val mastered = scheduler.recordAnswer(oneKnown, "same", true)
        assertTrue(mastered.progress.isMastered)
        assertEquals(5.002, mastered.profile.level, 1e-12)
    }

    @Test fun sameLevelCalibrationReturnsRemainInTheReviewCountAndPool() {
        val state = snapshot(listOf(word("probe", 5))).copy(profile = UserProfile(level = 5.0), calibration =
            CalibrationSession("done", now - DAY, 731, listOf("probe"), 0,
                listOf(CalibrationAnswer("probe", false, now - DAY)), 1))
        assertEquals(1, scheduler.dueReviewCount(state))
        assertEquals("probe", scheduler.nextWord(state)?.id)
        val shifted = state.copy(profile = state.profile.copy(level = 5.5))
        assertEquals(1, scheduler.dueReviewCount(shifted))
        assertEquals("probe", scheduler.nextWord(shifted)?.id)
    }

    @Test fun sameLevelBacklogCountsTowardReviewPressureAndLevelTenWordsStillDraw() {
        val baseline = snapshot(listOf(word("fresh", 4), word("due", 4)),
            listOf(ReviewProgress("due", incorrectCount = 1, nextReviewAt = now)))
        val same = (1..25).map { word("same$it", 3) }
        val expanded = baseline.copy(words = baseline.words + same, progress = baseline.progress + same.map {
            ReviewProgress(it.id, incorrectCount = 1, nextReviewAt = now - 3 * DAY)
        })
        assertEquals(26, scheduler.dueReviewCount(expanded))
        val reviews = (0 until 2_000).count { sequence ->
            scheduler.nextWord(expanded.copy(profile = expanded.profile.copy(totalAnswers = sequence.toLong())))?.id != "fresh"
        }
        assertEquals(.60, reviews / 2000.0, .04)
        assertEquals("same", scheduler.nextWord(snapshot(listOf(word("same", 10)))
            .copy(profile = UserProfile(level = 10.0)))?.id)
    }

    @Test fun candidateStorageOrderCannotChangeNewOrReviewDraws() {
        val words = (3..7).map { word("grade$it", it) }
        for (review in listOf(false, true)) {
            val state = snapshot(words, if (review) words.map { ReviewProgress(it.id, incorrectCount = 1) } else emptyList())
                .copy(profile = UserProfile(level = 5.0))
            repeat(100) { sequence ->
                val current = state.copy(profile = state.profile.copy(totalAnswers = sequence.toLong()))
                assertEquals(scheduler.nextWord(current), scheduler.nextWord(current.copy(
                    words = current.words.reversed(), progress = current.progress.reversed())))
            }
        }
    }

    @Test fun equallyUrgentReviewsUseDistanceWeightsInNormalAndMistakeModes() {
        val words = (3..7).map { word("grade$it", it) }
        val state = snapshot(words, words.map { ReviewProgress(it.id, incorrectCount = 1) })
            .copy(profile = UserProfile(level = 5.0))
        for (reviewOnly in listOf(false, true)) {
            val counts = IntArray(11)
            repeat(10000) { sequence ->
                val card = requireNotNull(scheduler.nextWord(state.copy(
                    profile = state.profile.copy(totalAnswers = sequence.toLong())), reviewOnly))
                counts[card.difficulty]++
            }
            for (grade in 3..7) assertEquals("reviewOnly=$reviewOnly grade=$grade counts=${counts.toList()}",
                maxOf(.1, abs(grade - 5).toDouble()) / 6.1, counts[grade] / 10000.0,
                if (grade == 5) .006 else .02)
        }
    }

    @Test fun reviewDistanceWeightStillRespectsErrorsAndBoundedLateness() {
        // Both words are exactly one level away: the shared distance factor cancels.
        // A known-once on-time word has urgency 1, an unknown 48h-overdue word has 15.
        val state = snapshot(listOf(word("freshReview", 4), word("lateMistake", 6)), listOf(
            ReviewProgress("freshReview", correctCount = 1, nextReviewAt = now),
            ReviewProgress("lateMistake", incorrectCount = 1, nextReviewAt = now - 2 * DAY),
        )).copy(profile = UserProfile(level = 5.0))
        val lateCount = (0 until 5_000).count { sequence ->
            scheduler.nextWord(state.copy(profile = state.profile.copy(totalAnswers = sequence.toLong())))?.id == "lateMistake"
        }
        assertEquals(15.0 / 16, lateCount / 5_000.0, .02)
        val fartherOverdue = state.copy(progress = state.progress.map {
            if (it.wordId == "lateMistake") it.copy(nextReviewAt = now - 30 * DAY) else it
        })
        repeat(100) { sequence ->
            assertEquals(scheduler.nextWord(state.copy(profile = state.profile.copy(totalAnswers = sequence.toLong()))),
                scheduler.nextWord(fartherOverdue.copy(profile = state.profile.copy(totalAnswers = sequence.toLong()))))
        }
    }

    @Test fun reviewRewardRequiresMasteryIncludingReturningCalibrationWords() {
        val word = word("probe", 6)
        val initial = snapshot(listOf(word)).copy(profile = UserProfile(level = 5.0), calibration =
            CalibrationSession("done", now - DAY, 731, listOf(word.id), 0,
                listOf(CalibrationAnswer(word.id, true, now - DAY)), 1))
        val once = initial.apply(scheduler.recordAnswer(initial, word.id, true))
        assertEquals(5.0, once.profile.level, 0.0)
        now += LearningScheduler.KNOWN_REVIEW_DELAY
        val twice = once.apply(scheduler.recordAnswer(once, word.id, true))
        assertEquals(5.02, twice.profile.level, 1e-12)
        assertTrue(twice.progress.single().isMastered)
    }

    @Test fun retainedMasteryLogsPreventRewardWhenProgressIsStale() {
        val state = snapshot(listOf(word("sample", 5)), listOf(ReviewProgress("sample", correctCount = 1)))
            .copy(profile = UserProfile(level = 5.0, totalAnswers = 2), logs =
                listOf(AnswerLog(1, "sample", true, now - DAY), AnswerLog(2, "sample", true, now - DAY)))
        assertEquals(5.0, scheduler.recordAnswer(state, "sample", true).profile.level, 0.0)
    }

    @Test fun aRetainedOldLogPreventsScoringAWordAgainWhenItsProgressIsMissing() {
        val state = snapshot(listOf(word("sample", 8))).let {
            it.copy(profile = it.profile.copy(level = 5.0, totalAnswers = 1),
                logs = listOf(AnswerLog(1, "sample", false, now - DAY)))
        }
        assertEquals(5.0, scheduler.recordAnswer(state, "sample", true).profile.level, 0.0)
    }

    private fun snapshot(words: List<Word>, progress: List<ReviewProgress> = emptyList()) = LearningSnapshot(
        words, progress, emptyList(), UserProfile(createdAt = now),
        calibration = CalibrationSession("completed", now, 731L, emptyList(), 0),
    )
    private fun word(id: String, difficulty: Int = 1, rank: Int = 1) = Word(id, id, "/test/", "n.", "示例", difficulty, rank)
    private fun probeVocabulary(wordsPerBand: Int = 30) = (1..10).flatMap { difficulty -> (0 until wordsPerBand).map { index ->
        val suffix = ('a' + index / 26).toString() + ('a' + index % 26)
        word("probe${'a' + difficulty}$suffix", difficulty, difficulty * 100 + index)
    } }
    private fun LearningSnapshot.apply(result: AnswerResult) = copy(
        progress = progress.filterNot { it.wordId == result.word.id } + result.progress,
        logs = logs + result.log, profile = result.profile,
    )
    private fun LearningSnapshot.answerProbe(wordId: String, known: Boolean): LearningSnapshot {
        val session = requireNotNull(calibration)
        var next = copy(calibration = session.copy(answers = session.answers + CalibrationAnswer(wordId, known, now)))
        next = next.copy(profile = profile.copy(level = scheduler.calibratedLevel(next)))
        val answered = requireNotNull(next.calibration)
        if (!answered.isComplete && answered.nextWordId == null) {
            scheduler.nextCalibrationWord(next)?.let { probe ->
                next = next.copy(calibration = requireNotNull(next.calibration).copy(
                    wordIds = requireNotNull(next.calibration).wordIds + probe.id,
                ))
            }
        }
        return next
    }
    companion object { private const val DAY = 86_400_000L }
}
