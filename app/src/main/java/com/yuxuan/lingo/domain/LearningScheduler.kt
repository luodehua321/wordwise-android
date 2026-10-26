package com.yuxuan.lingo.domain

import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pure scheduling logic. The supplied clock/zone make day boundaries reproducible in tests. */
class LearningScheduler(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private data class WordIndex(val source: List<Word>, val byId: Map<String, Word>)
    private data class ReviewCandidate(val word: Word, val errorRate: Double, val eligibleAt: Long)
    @Volatile private var cachedIndex: WordIndex? = null

    internal fun indexedWords(snapshot: LearningSnapshot): Map<String, Word> {
        val cached = cachedIndex
        if (cached?.source === snapshot.words) return cached.byId
        return snapshot.words.associateBy { it.id }.also { cachedIndex = WordIndex(snapshot.words, it) }
    }

    /** A persisted diagnostic queue is independent of the review queue and learning counters. */
    fun placementStatus(snapshot: LearningSnapshot): PlacementStatus {
        val session = snapshot.calibration
        val samples = calibrationSamples(snapshot)
        val evidence = abilitySamples(snapshot)
        val estimate = estimateAbility(evidence, snapshot.profile.level)
        val maximum = calibrationTarget(snapshot)
        val calibrating = isCalibrationActive(snapshot)
        return PlacementStatus(
            isCalibrating = calibrating,
            sampleCount = session?.answers?.size ?: 0,
            maxSamples = maximum,
            minSamples = maximum,
            confidenceLabel = when {
                snapshot.words.isEmpty() -> "词库为空"
                !calibrating && samples.isEmpty() -> "持续更新"
                calibrating && samples.isEmpty() -> "待校准"
                calibrating -> "自适应校准中"
                else -> "随学习持续更新"
            },
            estimatedLevel = if (calibrating && samples.isNotEmpty()) estimate.level else snapshot.profile.level,
            levelLabel = LearningLevel.describe(snapshot.profile.level).label,
            confidence = if (evidence.isEmpty()) 0.0 else (1.0 - estimate.deviation / 3.0).coerceIn(0.0, .95),
        )
    }

    /** Randomized, persisted probes with evidence-gated difficulty increases. */
    fun createCalibration(snapshot: LearningSnapshot, id: String, seed: Long): CalibrationSession {
        val random = kotlin.random.Random(seed)
        val previous = snapshot.calibration?.wordIds?.toSet().orEmpty()
        val start = calibrationStartWord(snapshot, previous, random)
        return CalibrationSession(
            id = id,
            startedAt = clock(),
            seed = seed,
            wordIds = listOfNotNull(start?.id),
            learningStartSequence = snapshot.profile.totalAnswers,
            targetSamples = calibrationTarget(snapshot.copy(calibration = null)),
        )
    }

    fun calibratedLevel(snapshot: LearningSnapshot): Double =
        estimateAbility(calibrationSamples(snapshot).map { it.copy(weight = INITIAL_CALIBRATION_WEIGHT) }, snapshot.profile.level).level

    /** Returns the queued probe, or chooses the next adaptive probe after an answer. */
    fun nextCalibrationWord(snapshot: LearningSnapshot): Word? {
        val session = snapshot.calibration ?: return null
        if (!isCalibrationActive(snapshot)) return null
        val answeredIds = session.answers.mapTo(HashSet()) { it.wordId }
        val available = calibrationWords(snapshot).filterNot { it.id in answeredIds }
        // Restored fixed queues must obey the same bounds as newly selected probes.
        // The repository persists a replacement before exposing it to the UI.
        calibrationCandidates(snapshot, session, available).find { it.id == session.nextWordId }?.let { return it }
        return chooseCalibrationProbe(snapshot, session)
    }

    /** Cards that can actually enter this mode's review stream now, rather than all cards
     * whose timestamp has passed. Calibration probes return only to normal learning. */
    fun dueReviewCount(snapshot: LearningSnapshot, reviewOnly: Boolean = false): Int {
        if (!reviewOnly && isCalibrationActive(snapshot)) return 0
        val now = clock()
        val ids = snapshot.words.mapTo(HashSet()) { it.id }
        val progress = snapshot.progress.associateBy { it.wordId }
        val reviews = progress.values.count {
            it.wordId in ids && it.wordId !in snapshot.excludedWordIds && isReviewReady(it, now) &&
                (!reviewOnly || it.isMistake)
        }
        return reviews + if (reviewOnly) 0 else dueCalibrationAnswers(snapshot, progress, now).size
    }

    fun nextWord(snapshot: LearningSnapshot, reviewOnly: Boolean = false): Word? {
        if (!reviewOnly && isCalibrationActive(snapshot)) {
            return nextCalibrationWord(snapshot)
        }
        val now = clock()
        val progress = snapshot.progress.associateBy { it.wordId }
        val available = snapshot.words.filterNot { it.id in snapshot.excludedWordIds || progress[it.id]?.isMastered == true }
            .distinctBy { it.id }
        val ready = available.filter { word ->
            progress[word.id]?.let { isReviewReady(it, now) &&
                (!reviewOnly || it.isMistake) } == true
        }
        val baseSeed = (snapshot.calibration?.seed ?: snapshot.profile.createdAt) xor
            (snapshot.profile.totalAnswers * 6364136223846793005L)
        val random = kotlin.random.Random(baseSeed)
        val reviewCandidates = ready.map { word ->
            val state = progress.getValue(word.id)
            ReviewCandidate(word, 1.0 - state.accuracy, reviewEligibleAt(state))
        }
        if (reviewOnly) return weightedReview(reviewCandidates, snapshot.profile.level, random.nextDouble(), now)

        val typeDraw = random.nextDouble()
        val diagnosticAnswers = dueCalibrationAnswers(snapshot, progress, now).associateBy { it.wordId }
        val diagnosticWords = available.filter { it.id in diagnosticAnswers }
        val candidates = reviewCandidates + diagnosticWords.map { word ->
            val answer = diagnosticAnswers.getValue(word.id)
            ReviewCandidate(word, if (answer.known) 0.0 else 1.0, answer.answeredAt + CALIBRATION_RETURN_DELAY)
        }
        val review = weightedReview(candidates, snapshot.profile.level, random.nextDouble(), now)
        val protectedIds = snapshot.calibration?.answers.orEmpty().mapTo(HashSet()) { it.wordId }
        val unseen = available.filter { (progress[it.id]?.answerCount ?: 0) == 0 && it.id !in protectedIds }
        val orderedLogs = snapshot.logs.sortedBy { it.sequence }
        val independent = independentLearningLogs(snapshot, orderedLogs)
        val newWord = chooseNewWord(snapshot, unseen)
        // Stable per answer sequence: refreshing or restarting cannot reroll the card.
        // When one pool is empty, use the other rather than ending the learning stream.
        if (newWord == null) return review
        if (review == null) return newWord
        val newSequences = independent.mapTo(HashSet()) { it.sequence }
        val recentKinds = orderedLogs.takeLast(3).map {
            if (it.sequence in newSequences) LearningCardKind.NEW else LearningCardKind.REVIEW
        }
        val recentNew = independent.takeLast(20)
        val words = indexedWords(snapshot)
        val expectedRecognition = recentNew.map {
            LearningAbilityUpdater.expectedRecognition(LearningDistanceWeight.safeLevel(snapshot.profile.level),
                words.getValue(it.wordId).difficulty.toDouble())
        }
        val probability = LearningStreamPolicy.reviewProbability(candidates.size,
            candidates.maxOf { (now - it.eligibleAt).coerceAtLeast(0L) }, recentNew.map { it.known }, expectedRecognition)
        val reviewSlot = LearningStreamPolicy.chooseReview(probability, recentKinds, typeDraw)
        return if (reviewSlot) review else newWord
    }

    private fun weightedReview(candidates: List<ReviewCandidate>, level: Double, drawUnit: Double, now: Long): Word? {
        if (candidates.isEmpty()) return null
        val ranked = candidates.sortedBy { it.word.id }
        val weights = ranked.map {
            LearningDistanceWeight.weight(level, it.word.difficulty) *
                LearningStreamPolicy.reviewWeight(it.errorRate, now - it.eligibleAt)
        }
        return LearningDistanceWeight.drawIndex(weights, drawUnit)?.let { ranked[it].word }
    }

    private fun chooseNewWord(snapshot: LearningSnapshot, words: List<Word>): Word? {
        val ranked = words.filter { LearningDistanceWeight.isNewEligible(snapshot.profile.level, it.difficulty) }
            .sortedBy { it.id }
        if (ranked.isEmpty()) return null
        val sequence = snapshot.profile.totalAnswers
        val seed = (snapshot.calibration?.seed ?: snapshot.profile.createdAt) xor (sequence * 6364136223846793005L)
        val weights = ranked.map { LearningDistanceWeight.weight(snapshot.profile.level, it.difficulty) }
        return LearningDistanceWeight.drawIndex(weights, kotlin.random.Random(seed).nextDouble())?.let { ranked[it] }
    }

    fun recordAnswer(snapshot: LearningSnapshot, wordId: String, known: Boolean): AnswerResult {
        val word = requireNotNull(snapshot.words.find { it.id == wordId }) { "词库中不存在该单词：$wordId" }
        val existing = snapshot.progress.find { it.wordId == wordId }
        val old = existing ?: ReviewProgress(wordId)
        val now = clock()
        val sequence = snapshot.profile.totalAnswers + 1
        val streak = if (known) old.consecutiveKnown + 1 else 0
        val streakStart = if (known) old.knownStreakStartedAt ?: now else null
        val mastered = old.isMastered || old.correctCount + (if (known) 1 else 0) >= 2
        val interval = if (known) KNOWN_REVIEW_DELAY else REVIEW_DELAY
        val updatedProgress = old.copy(
            correctCount = old.correctCount + if (known) 1 else 0,
            incorrectCount = old.incorrectCount + if (known) 0 else 1,
            consecutiveKnown = streak,
            firstSeenAt = old.firstSeenAt ?: now,
            lastReviewedAt = now,
            lastUnknownAt = if (known) old.lastUnknownAt else now,
            knownStreakStartedAt = streakStart,
            masteredAt = if (mastered) old.masteredAt ?: now else null,
            nextReviewAt = now + interval,
            lastAnsweredSequence = sequence,
            retryAfterSequence = sequence,
        )
        val log = AnswerLog(sequence, wordId, known, now)
        val independentAnswer = old.answerCount == 0 && snapshot.calibration?.wordIds?.contains(wordId) != true &&
            snapshot.logs.none { it.wordId == wordId }
        // Mastery earns one review reward. Retained logs also protect against a stale
        // or missing progress row making a historically mastered word earn it again.
        val reviewMastery = known && old.answerCount > 0 && !old.isMastered && updatedProgress.isMastered &&
            snapshot.logs.count { it.wordId == wordId && it.known } < 2
        val updatedProfile = snapshot.profile.copy(
            level = if ((independentAnswer && known) || reviewMastery) {
                LearningDistanceWeight.rewardedLevel(snapshot.profile.level, word.difficulty)
            } else if (independentAnswer) {
                val words = indexedWords(snapshot)
                val history = independentLearningLogs(snapshot).takeLast(20).map {
                    RecognitionEvidence(words.getValue(it.wordId).difficulty.toDouble(), it.known)
                }
                LearningAbilityUpdater.nextLevel(snapshot.profile.level, word.difficulty, known, history)
            } else snapshot.profile.level,
            totalAnswers = sequence,
            createdAt = snapshot.profile.createdAt.takeIf { it != 0L } ?: now,
        )
        return AnswerResult(
            word = word,
            progress = updatedProgress,
            profile = updatedProfile,
            log = log,
            previousProgress = existing,
            previousProfile = snapshot.profile,
        )
    }

    private fun dueCalibrationAnswers(
        snapshot: LearningSnapshot,
        progress: Map<String, ReviewProgress>,
        now: Long,
    ): List<CalibrationAnswer> {
        val ids = snapshot.words.mapTo(HashSet()) { it.id }
        // Calibration probes are a separate diagnostic sample. Returning an unknown
        // probe five minutes later (the normal review delay) mixed level-2 probes into
        // a level-8 learning stream right after calibration. The documented contract
        // is one day for both answers, so the first normal session stays coherent.
        return snapshot.calibration?.answers.orEmpty().filter {
            it.wordId in ids && it.wordId !in snapshot.excludedWordIds &&
                now - it.answeredAt >= CALIBRATION_RETURN_DELAY &&
                (progress[it.wordId]?.answerCount ?: 0) == 0
        }.distinctBy { it.wordId }
    }

    internal fun calibrationTarget(snapshot: LearningSnapshot): Int {
        val answeredIds = snapshot.calibration?.answers.orEmpty().mapTo(HashSet()) { it.wordId }
        val remaining = calibrationWords(snapshot).count { it.id !in answeredIds }
        return minOf(PLACEMENT_SAMPLE_TARGET, snapshot.calibration?.targetSamples ?: PLACEMENT_SAMPLE_TARGET,
            answeredIds.size + remaining).coerceAtLeast(0)
    }

    internal fun isCalibrationActive(snapshot: LearningSnapshot): Boolean =
        snapshot.calibration?.let { !it.isComplete && it.answers.size < calibrationTarget(snapshot) }
            ?: (calibrationTarget(snapshot) > 0)

    private fun calibrationWords(snapshot: LearningSnapshot): List<Word> {
        val retired = snapshot.progress.filter { it.isMastered }.mapTo(HashSet()) { it.wordId }
        return snapshot.words.filterNot { it.id in snapshot.excludedWordIds || it.id in retired }.distinctBy { it.id }
    }

    fun reviewEligibleAt(state: ReviewProgress): Long =
        if (state.isMastered) Long.MAX_VALUE else state.lastReviewedAt?.let {
            // Historical counts also honor the new policy without deleting records.
            val lastWasKnown = state.correctCount > 0 && (state.consecutiveKnown > 0 || state.lastUnknownAt == null || state.lastUnknownAt < it)
            it + if (lastWasKnown) KNOWN_REVIEW_DELAY else REVIEW_DELAY
        } ?: state.nextReviewAt

    private fun isReviewReady(state: ReviewProgress, now: Long): Boolean {
        if (state.answerCount == 0 || state.isMastered) return false
        // Apply recognition cooldown and retirement to existing schedules on upgrade.
        // Keep the original timestamps and all learning history intact.
        return now >= reviewEligibleAt(state)
    }

    private fun firstEncounterLogs(snapshot: LearningSnapshot, orderedLogs: List<AnswerLog>): List<AnswerLog> {
        val seen = HashSet<String>()
        val start = snapshot.calibration?.learningStartSequence ?: snapshot.profile.resetCalibrationSinceSequence
        return orderedLogs.filter { log -> seen.add(log.wordId) && log.sequence > start }
    }

    private fun independentLearningLogs(
        snapshot: LearningSnapshot,
        orderedLogs: List<AnswerLog> = snapshot.logs.sortedBy { it.sequence },
    ): List<AnswerLog> {
        val diagnosticIds = snapshot.calibration?.wordIds.orEmpty().toSet()
        val words = indexedWords(snapshot)
        val loggedCounts = orderedLogs.groupingBy { it.wordId }.eachCount()
        // Imported progress can predate the retained logs. Treat that word as a review
        // instead of mistaking the first surviving review for independent evidence.
        val missingHistory = snapshot.progress.filter {
            it.answerCount > (loggedCounts[it.wordId] ?: 0)
        }.mapTo(HashSet()) { it.wordId }
        return firstEncounterLogs(snapshot, orderedLogs).filter {
            it.wordId !in diagnosticIds && it.wordId !in missingHistory && it.wordId in words
        }
    }

    /** Select within each band so rich content in easier bands cannot hide the harder bands.
     * Sparse/custom dictionaries deliberately fall back instead of ending calibration early. */
    private fun representativeWords(snapshot: LearningSnapshot, excluded: Set<String> = emptySet()): List<Word> {
        val words = calibrationWords(snapshot)
        val assigned = words.filter { it.id in excluded }
        val family = assigned.flatMapTo(HashSet()) { relatedSpellings(it) }
        // Reverse links find the base of an assigned inflection, and then its siblings.
        if (family.isNotEmpty()) {
            val parents = words.filter { word -> relatedSpellings(word).any { it in family } }
            parents.forEach { family.addAll(relatedSpellings(it)) }
        }
        val inflections = words.flatMapTo(HashSet()) { word ->
            word.wordForms.map { it.form.lowercase(java.util.Locale.ROOT) }.filter { it != word.word }
        }
        return words.filter { it.id !in excluded }.groupBy { it.difficulty }.values.flatMap { band ->
            val unrelated = band.filter { word -> relatedSpellings(word).none { it in family } }.ifEmpty { band }
            val content = unrelated.filter {
                it.word !in FUNCTION_WORDS && it.word.matches(HEADWORD) && CONTENT_POS.containsMatchIn(it.partOfSpeech)
            }.ifEmpty { unrelated }
            val explained = content.filter { word ->
                CHINESE.containsMatchIn(word.meaningZh) && word.examples.any { it.en.isNotBlank() }
            }.ifEmpty { content }
            val bilingual = explained.filter { word ->
                word.examples.any { it.en.isNotBlank() && CHINESE.containsMatchIn(it.zh) }
            }.ifEmpty { explained }
            bilingual.filter { it.word !in inflections }.ifEmpty { bilingual }
        }
    }

    private fun relatedSpellings(word: Word): List<String> =
        (listOf(word.word) + word.wordForms.map { it.form } + word.derivatives.map { it.word })
            .map { it.lowercase(java.util.Locale.ROOT) }

    private fun chooseRepresentative(words: List<Word>, random: kotlin.random.Random): Word? {
        if (words.isEmpty()) return null
        // Favor the more common part of a frequency band, with a broad randomized pool.
        val ranked = words.sortedWith(compareBy<Word> { it.rank }.thenBy { it.id })
        val size = maxOf(24, ceil(ranked.size * .6).toInt()).coerceAtMost(ranked.size)
        return ranked[random.nextInt(size)]
    }

    private fun calibrationStartWord(snapshot: LearningSnapshot, previous: Set<String>, random: kotlin.random.Random): Word? {
        val pool = representativeWords(snapshot, previous).ifEmpty { representativeWords(snapshot) }
        if (pool.isEmpty()) return null
        val band = pool.filter { it.difficulty == INITIAL_LEVEL.toInt() }.ifEmpty {
            val bestDistance = pool.minOf { abs(it.difficulty - INITIAL_LEVEL) }
            pool.filter { abs(it.difficulty - INITIAL_LEVEL) <= bestDistance + .01 }
        }
        return chooseRepresentative(band, random)
    }

    private fun chooseCalibrationProbe(snapshot: LearningSnapshot, session: CalibrationSession): Word? {
        val used = session.answers.mapTo(HashSet()) { it.wordId }
        val pool = representativeWords(snapshot, used)
        if (pool.isEmpty()) return null
        val samples = calibrationSamples(snapshot)
        val estimate = estimateAbility(samples, INITIAL_LEVEL)
        val answered = session.answers.size
        val seed = session.seed xor ((answered + 1L) * 6364136223846793005L)
        val random = kotlin.random.Random(seed)
        val reachable = calibrationCandidates(snapshot, session, pool)
        // Coverage remains inside the same evidence-gated bounds as adaptive probes.
        val coverage = answered >= 6 && answered % 4 == 2
        val target = if (coverage) {
            val counts = samples.groupingBy { it.difficulty.toInt() }.eachCount()
            val bands = reachable.map { it.difficulty }.distinct().sorted()
            val least = bands.minOf { counts[it] ?: 0 }
            val underSampled = bands.filter { (counts[it] ?: 0) == least }
            underSampled[random.nextInt(underSampled.size)].toDouble()
        } else {
            val comfort = estimate.level + if (answered < 6) 0.0 else CALIBRATION_COMFORT_STRETCH
            (comfort + random.nextDouble(-CALIBRATION_COMFORT_JITTER, CALIBRATION_COMFORT_JITTER))
                .coerceIn(1.0, 10.0)
        }
        val bestDistance = reachable.minOf { abs(it.difficulty - target) }
        val band = reachable.filter { abs(it.difficulty - target) <= bestDistance + .01 }
        return chooseRepresentative(band, random)
    }

    private fun calibrationCandidates(
        snapshot: LearningSnapshot,
        session: CalibrationSession,
        pool: List<Word>,
    ): List<Word> {
        if (pool.isEmpty()) return emptyList()
        val samples = calibrationSamples(snapshot)
        val last = session.answers.lastOrNull()
        val previous = indexedWords(snapshot)[last?.wordId]?.difficulty ?: INITIAL_LEVEL.toInt()
        if (samples.isEmpty()) {
            val distance = pool.minOf { abs(it.difficulty - INITIAL_LEVEL) }
            return pool.filter { abs(it.difficulty - INITIAL_LEVEL) == distance }
        }
        val estimate = estimateAbility(samples, INITIAL_LEVEL)
        // Two distinct known words at a band (or harder) are needed to unlock the next
        // band. A high posterior from easy answers alone is not enough to unlock 10.
        val confirmed = (1..10).lastOrNull { band ->
            samples.count { it.known && it.difficulty >= band } >= 2
        } ?: 1
        val topCap = when {
            samples.size >= 12 && confirmed >= 9 && estimate.level >= 8.5 -> 10
            samples.size >= 8 && confirmed >= 8 -> 9
            else -> 8
        }
        var ceiling = minOf(topCap, maxOf(INITIAL_LEVEL.toInt(), confirmed + 1), previous + 1)
        if (samples.size < 6) ceiling = minOf(ceiling, 5)
        // An unknown answer immediately eases the next probe; coverage cannot raise it.
        if (last != null && !last.known) ceiling = minOf(ceiling, (previous - 1).coerceAtLeast(1))
        val lower = maxOf(1, previous - if (samples.size < 4) 1 else 2)
        val bounded = pool.filter { it.difficulty in lower..ceiling }
        if (bounded.isNotEmpty()) return bounded
        // A sparse dictionary may not contain any bounded probe. Exhaust the closest
        // easier band first; never choose a random remote band because it has more words.
        val easier = pool.filter { it.difficulty <= ceiling }
        val closest = if (easier.isNotEmpty()) easier.maxOf { it.difficulty } else pool.minOf { it.difficulty }
        return pool.filter { it.difficulty == closest }
    }

    private fun calibrationSamples(snapshot: LearningSnapshot): List<AbilitySample> {
        if (snapshot.calibration?.answers.isNullOrEmpty()) return emptyList()
        val words = indexedWords(snapshot)
        return snapshot.calibration?.answers.orEmpty().distinctBy { it.wordId }.mapNotNull { answer ->
            words[answer.wordId]?.let { AbilitySample(it.difficulty.toDouble(), answer.known) }
        }
    }

    private fun abilitySamples(snapshot: LearningSnapshot): List<AbilitySample> {
        if (snapshot.logs.isEmpty() && snapshot.calibration?.answers.isNullOrEmpty()) return emptyList()
        val words = indexedWords(snapshot)
        val encounters = independentLearningLogs(snapshot)
        val recent = encounters.takeLast(LEARNING_EVIDENCE_WINDOW)
        // A diagnostic answer is one observation, not three independent observations.
        // Its influence decays as independent new-word evidence accumulates.
        val calibrationWeight = (INITIAL_CALIBRATION_WEIGHT * exp(-encounters.size / CALIBRATION_DECAY_SCALE))
            .coerceAtLeast(.25)
        val baseline = calibrationSamples(snapshot).map { it.copy(weight = calibrationWeight) }
        return baseline + recent.mapIndexedNotNull { index, log ->
            words[log.wordId]?.let {
                AbilitySample(it.difficulty.toDouble(), log.known,
                    exp((index - recent.lastIndex) / LEARNING_RECENCY_SCALE))
            }
        }
    }

    /** A weak prior and a Rasch likelihood estimate the initial learning band.
     * The posterior also describes uncertainty; routine learning updates level online.
     * "level" means a band with about 70% expected first-encounter recognition. */
    private fun estimateAbility(samples: List<AbilitySample>, fallback: Double): AbilityEstimate {
        if (samples.isEmpty()) return AbilityEstimate(fallback.coerceIn(1.0, 10.0), 3.0)
        val levels = (0..72).map { 1.0 + it / 8.0 }
        val weights = levels.map { level ->
            var likelihood = -.5 * ((level - INITIAL_LEVEL) / 3.5).pow(2)
            samples.forEach { sample ->
                val probability = (1.0 / (1.0 + exp(-1.25 * (level - sample.difficulty) - .84729786)))
                    .coerceIn(.000001, .999999)
                likelihood += sample.weight * ln(if (sample.known) probability else 1.0 - probability)
            }
            likelihood
        }
        val maximum = weights.max()
        val posterior = weights.map { exp(it - maximum) }
        val normalization = posterior.sum()
        val level = levels.indices.sumOf { levels[it] * posterior[it] } / normalization
        val variance = levels.indices.sumOf { (levels[it] - level).pow(2) * posterior[it] } / normalization
        return AbilityEstimate(level.coerceIn(1.0, 10.0), sqrt(variance))
    }

    fun abilityDescription(snapshot: LearningSnapshot): String {
        val samples = abilitySamples(snapshot)
        if (samples.isEmpty()) return "样本不足，暂不能判断词汇水平。"
        return java.lang.String.format(java.util.Locale.ROOT,
            "当前参考 %d 个独立词的自报认识结果。持续认识更多难词或不认识基础词时，水平会加快调整；表现稳定时小步更新，单题最多变化 0.12 级。复习同一词不重复计入。难度尚未经实测标定，仅用于安排学习；不是词汇量或考试等级。",
            samples.size)
    }

    private data class AbilitySample(val difficulty: Double, val known: Boolean, val weight: Double = 1.0)
    private data class AbilityEstimate(val level: Double, val deviation: Double)

    companion object {
        const val MASTERY_STREAK = 2
        const val KNOWN_REVIEW_DELAY = 60 * 60_000L
        const val CALIBRATION_RETURN_DELAY = 24 * 60 * 60_000L
        const val INITIAL_LEVEL = 3.0
        const val REVIEW_DELAY = 5 * 60_000L
        const val REVIEW_PROBABILITY = .40
        private const val CALIBRATION_COMFORT_STRETCH = .3
        private const val CALIBRATION_COMFORT_JITTER = .4
        const val MIN_PLACEMENT_SAMPLES = 20
        const val MAX_PLACEMENT_SAMPLES = 20
        private val CONTENT_POS = Regex("(^|[\\s,;/])(?:n|v|vt|vi|adj|adv)(?:\\.|$|[\\s,;/])", RegexOption.IGNORE_CASE)
        private val HEADWORD = Regex("[a-z]{3,18}")
        private val CHINESE = Regex("[\\u3400-\\u9fff]")
        private val FUNCTION_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "those", "these", "there", "their",
            "have", "has", "had", "are", "was", "were", "been", "being", "shall", "will", "would",
            "could", "should", "does", "did", "can", "may", "must", "not", "but", "into", "onto",
            "you", "your", "they", "them", "its", "our", "ours", "who", "which", "what", "when", "where",
            "all", "any", "some", "each", "every", "both", "either", "neither", "none", "another", "other",
            "many", "much", "more", "most", "few", "less", "least", "her", "hers", "his", "him", "she",
        )
        private const val INITIAL_CALIBRATION_WEIGHT = 1.0
        // This window describes the accumulated evidence and uncertainty. Published
        // routine-learning levels are updated directly in recordAnswer, once per new word.
        private const val LEARNING_EVIDENCE_WINDOW = 200
        private const val LEARNING_RECENCY_SCALE = 96.0
        private const val CALIBRATION_DECAY_SCALE = 120.0
    }
}
