package com.yuxuan.lingo.domain

import java.io.File
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Read-only probes against the application's real domain classes, not a copied model. */
fun main(args: Array<String>) {
    val output = File(args[0]).apply { mkdirs() }
    val counts = File(args[1]).readLines().filter { it.isNotBlank() }.associate {
        val parts = it.split('\t'); parts[0].toInt() to parts[1].toInt()
    }
    var now = 1_792_886_400_000L
    val scheduler = LearningScheduler({ now }, ZoneId.of("Asia/Shanghai"))
    fun word(id: String, difficulty: Int) = Word(id, id, "/test/", "n.", "审查示例", difficulty, 1)
    fun state(words: List<Word>, level: Double = 5.0, progress: List<ReviewProgress> = emptyList()) =
        LearningSnapshot(words, progress, emptyList(), UserProfile(level = level, createdAt = now),
            calibration = CalibrationSession("completed", now, 731L, emptyList(), 0))
    fun save(name: String, header: List<String>, rows: List<List<Any?>>) {
        output.resolve(name).writeText((listOf(header) + rows).joinToString("\n", postfix = "\n") { row ->
            row.joinToString(",") { value -> "\"" + value.toString().replace("\"", "\"\"") + "\"" }
        })
    }
    val steps = (3..7).map { grade ->
        val card = word("step", grade)
        val snapshot = state(listOf(card))
        val known = scheduler.recordAnswer(snapshot, card.id, true).profile.level - 5.0
        val unknown = scheduler.recordAnswer(snapshot, card.id, false).profile.level - 5.0
        val p = LearningAbilityUpdater.expectedRecognition(5.0, grade.toDouble())
        listOf(grade, known, unknown, p, p * known + (1 - p) * unknown)
    }
    save("answer-deltas.csv", listOf("difficulty", "knownDelta", "unknownDelta", "modelRecognition", "expectedDeltaIfModelMatches"), steps)
    val same = steps.single { it[0] == 5 }
    check(abs(same[1].toString().toDouble() - .002) < 1e-12)
    check(abs(same[2].toString().toDouble() + .084) < 1e-12)

    val distribution = mutableListOf<List<Any?>>()
    val poolSummary = (1..10).map { integerLevel ->
        val level = integerLevel.toDouble()
        val eligible = counts.keys.filter { LearningDistanceWeight.isNewEligible(level, it) }.sorted()
        val total = eligible.sumOf { counts.getValue(it) * LearningDistanceWeight.weight(level, it) }
        var sameChance = 0.0
        var harderChance = 0.0
        var expectedRecognition = 0.0
        var expectedDelta = 0.0
        for (grade in eligible) {
            val weight = LearningDistanceWeight.weight(level, grade)
            val chance = counts.getValue(grade) * weight / total
            val p = LearningAbilityUpdater.expectedRecognition(level, grade.toDouble())
            val card = word("pool", grade)
            val snapshot = state(listOf(card), level)
            val known = scheduler.recordAnswer(snapshot, card.id, true).profile.level - level
            val unknown = scheduler.recordAnswer(snapshot, card.id, false).profile.level - level
            if (grade == integerLevel) sameChance += chance
            if (grade > integerLevel) harderChance += chance
            expectedRecognition += chance * p
            expectedDelta += chance * (p * known + (1 - p) * unknown)
            distribution += listOf(level, grade, counts.getValue(grade), weight, chance, p)
        }
        listOf(level, sameChance, harderChance, expectedRecognition, expectedDelta)
    }
    save("new-word-distribution.csv", listOf("level", "difficulty", "candidateCount", "perWordWeight", "bandProbability", "modelRecognition"), distribution)
    save("new-pool-summary.csv", listOf("level", "sameBandProbability", "harderBandProbability", "expectedNewRecognition", "expectedFirstAnswerDelta"), poolSummary)

    val mix = listOf(.30, .40, .60).map { probability ->
        val random = Random(731)
        val recent = ArrayDeque<LearningCardKind>()
        val draws = 500_000
        var reviews = 0
        repeat(draws) {
            val review = LearningStreamPolicy.chooseReview(probability, recent.toList(), random.nextDouble())
            if (review) reviews++
            recent.addLast(if (review) LearningCardKind.REVIEW else LearningCardKind.NEW)
            if (recent.size > 3) recent.removeFirst()
        }
        val theoretical = (1 + probability) / (3 + (1 - probability) * (1 - probability))
        check(abs(reviews.toDouble() / draws - theoretical) < .004)
        listOf(probability, reviews.toDouble() / draws, theoretical, draws)
    }
    save("stream-mix.csv", listOf("baseReviewProbability", "observedReviewShare", "stationaryReviewShare", "draws"), mix)

    val allProgress = (0..10).flatMap { correct -> (1..100).map { incorrect ->
        ReviewProgress("mistake", correctCount = correct, incorrectCount = incorrect)
    } }
    val mistakes = allProgress.filter { it.isMistake }
    val maximumPercent = mistakes.maxOf { (it.accuracy * 100).roundToInt() }
    val familiar = mistakes.count { (it.accuracy * 100).roundToInt() >= 60 }
    check(maximumPercent == 50 && familiar == 0)

    val old = word("old", 5)
    val beforeMastery = state(listOf(old), progress = listOf(
        ReviewProgress(old.id, correctCount = 1, incorrectCount = 100, lastReviewedAt = now - 3_600_000),
    ))
    val learned = scheduler.recordAnswer(beforeMastery, old.id, true)
    val afterMastery = beforeMastery.copy(progress = listOf(learned.progress), profile = learned.profile, logs = listOf(learned.log))
    now += 30 * 86_400_000L
    check(learned.progress.isMastered && scheduler.nextWord(afterMastery) == null)
    check(scheduler.recordAnswer(afterMastery, old.id, false).progress.isMastered)

    val outside = state(listOf(word("remaining", 8)))
    check(scheduler.nextWord(outside) == null)
    val near = word("near", 5)
    val far = (1..100).map { word("far$it", 7) }
    val overdue = 2 * 86_400_000L
    val nearWeight = LearningDistanceWeight.weight(5.0, 5) * LearningStreamPolicy.reviewWeight(1.0, overdue)
    val farWeight = LearningDistanceWeight.weight(5.0, 7) * LearningStreamPolicy.reviewWeight(1.0, 0)
    val reviews = state(listOf(near) + far, progress = listOf(
        ReviewProgress(near.id, incorrectCount = 1, nextReviewAt = now - overdue),
    ) + far.map { ReviewProgress(it.id, incorrectCount = 1, nextReviewAt = now) })
    val reviewDraws = 20_000
    val nearDraws = (1..reviewDraws).count { seed ->
        scheduler.nextWord(reviews.copy(calibration = reviews.calibration!!.copy(seed = seed.toLong())))?.id == near.id
    }
    save("counterexamples.csv", listOf("case", "value", "detail"), listOf(
        listOf("maximumMistakePercent", maximumPercent, "All valid unmastered mistake states have correctCount <= 1"),
        listOf("familiarMistakeStates", familiar, "The UI requires masteryPercent >= 60"),
        listOf("retiredAfterTwoKnownAnd100Unknown", learned.progress.isMastered, "No cards even after 30 days; another unknown does not undo mastery"),
        listOf("retiredAccuracy", learned.progress.accuracy, "Two known, 100 unknown"),
        listOf("newWordOutsideWindowReturnsNull", scheduler.nextWord(outside) == null, "An unseen level-8 word remains at profile level 5"),
        listOf("overdueSameBandReviewWeight", nearWeight, "100% errors, 48h overdue"),
        listOf("freshTwoBandsAwayReviewWeight", farWeight, "100% errors, just eligible"),
        listOf("overdueSameBandReviewProbability", nearWeight / (nearWeight + far.size * farWeight), "100 other two-bands-away reviews"),
        listOf("observedOverdueSameBandDraws", nearDraws, "$reviewDraws actual scheduler draws"),
    ))

    val vocabulary = (1..10).flatMap { grade -> (0 until 30).map { index ->
        val spelling = "pro" + ('a' + grade) + ('a' + index / 26) + ('a' + index % 26)
        word(spelling, grade).copy(examples = listOf(Example("A test example.", "测试例句。")))
    } }
    val calibrations = listOf(0, 1, 5, 9, 10).map { threshold ->
        var snapshot = state(vocabulary, 3.0).copy(calibration = null)
        snapshot = snapshot.copy(calibration = scheduler.createCalibration(snapshot, "audit", 731L))
        val bands = mutableListOf<Int>()
        repeat(20) {
            val card = requireNotNull(scheduler.nextWord(snapshot))
            bands += card.difficulty
            val session = requireNotNull(snapshot.calibration)
            snapshot = snapshot.copy(calibration = session.copy(
                wordIds = session.wordIds.take(session.answers.size) + card.id,
                answers = session.answers + CalibrationAnswer(card.id, card.difficulty <= threshold, now),
            ))
            snapshot = snapshot.copy(profile = snapshot.profile.copy(level = scheduler.calibratedLevel(snapshot)))
        }
        check(bands.first() == 3 && bands.take(6).all { it <= 5 })
        check(!scheduler.placementStatus(snapshot).isCalibrating)
        listOf(threshold, snapshot.profile.level, bands.joinToString("|"), snapshot.logs.size, snapshot.profile.totalAnswers)
    }
    save("calibration-cases.csv", listOf("knownThroughDifficulty", "finalLevel", "probeBands", "learningLogCount", "learningAnswers"), calibrations)
    println("Read-only audit completed: ${output.absolutePath}")
}
