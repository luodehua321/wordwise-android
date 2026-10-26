package com.yuxuan.lingo

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuxuan.lingo.data.RoomLearningRepository
import com.yuxuan.lingo.data.RelatedPronunciationCodec
import com.yuxuan.lingo.domain.AnswerResult
import com.yuxuan.lingo.domain.LearningSnapshot
import com.yuxuan.lingo.domain.LearningScheduler
import com.yuxuan.lingo.domain.LearningRepository
import com.yuxuan.lingo.domain.Word
import com.yuxuan.lingo.domain.LearningLevel
import com.yuxuan.lingo.feedback.FeedbackPreferences
import com.yuxuan.lingo.ui.*
import com.yuxuan.lingo.tracking.StudyClock
import com.yuxuan.lingo.tracking.StudySlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.Closeable
import kotlin.math.roundToInt

class MainViewModel internal constructor(
    application: Application,
    private val repository: LearningRepository,
    private val feedbackPreferences: FeedbackPreferences,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val currentZone: () -> ZoneId = ZoneId::systemDefault,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application, RoomLearningRepository(application), FeedbackPreferences(application),
    )

    private val scheduler = LearningScheduler(wallNow)
    private val mutableState = MutableStateFlow(AppUiState(feedback = feedbackPreferences.read()))
    val state = mutableState.asStateFlow()
    private var vocabularyIndex: Map<String, Word> = emptyMap()
    private var relationReadings: Map<String, Pair<String, String>> = emptyMap()
    private var busy = false
    private var sessionAnswers = 0
    private var lastAnswer: AnswerResult? = null
    private val studyClock = StudyClock(SystemClock::elapsedRealtime, wallNow)
    private val timeWriter = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timeWriteMutex = Mutex()
    private val pendingTime = mutableListOf<StudySlice>()
    private var writingTime: StudySlice? = null
    private val recordedTime = mutableMapOf<String, Long>()
    private var timeLoaded = false
    private var studyForeground = false
    private var latestSnapshot: LearningSnapshot? = null
    private var statsDate: LocalDate? = null
    private var statsZone: ZoneId? = null
    private var statsAsOf = Long.MIN_VALUE
    private var nextReviewUiRefreshAt = Long.MAX_VALUE

    init {
        load()
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                syncStudyMode()
                updateTimeUi()
                refreshTimeSensitiveUi()
                if (++ticks % 15 == 0) timeWriter.launch { flushStudyTime() }
            }
        }
    }

    fun dispatch(action: AppAction): Boolean =
        when (action) {
            is AppAction.SelectTab -> {
                if (state.value.selectedTab == action.tab) false else {
                    mutableState.update { it.copy(selectedTab = action.tab) }
                    syncStudyMode()
                    timeWriter.launch { flushStudyTime() }
                    true
                }
            }
            is AppAction.OpenWord -> {
                val complete = vocabularyIndex[action.word.wordId]?.toUi(vocabulary = vocabularyIndex, extraReadings = relationReadings) ?: action.word
                mutableState.update { it.copy(selectedWord = complete) }; true
            }
            is AppAction.MarkLearned -> perform {
                repository.markLearned(action.wordId)
                lastAnswer = null
                refresh(repository.snapshot()) { it.copy(canUndo = false) }
                if (state.value.currentWord?.wordId == action.wordId) showNext()
            }
            is AppAction.UnmarkLearned -> perform {
                repository.unmarkLearned(action.wordId)
                refresh(repository.snapshot())
            }
            AppAction.DismissWord -> { mutableState.update { it.copy(selectedWord = null) }; true }
            is AppAction.Answer -> if (state.value.currentWord != null && state.value.answerIsKnown == null) {
                perform {
                    val word = state.value.currentWord ?: return@perform
                    val isCalibration = state.value.placement.active && !state.value.reviewMode
                    var result: AnswerResult? = null
                    if (isCalibration) repository.answerCalibration(word.wordId, action.known)
                    else {
                        result = repository.answer(word.wordId, action.known)
                        sessionAnswers++
                    }
                    lastAnswer = result
                    // Publish placement progress and its answer feedback in one state emission.
                    // On answer 20 this keeps the calibration heading stable and prevents
                    // the study clock from briefly treating its feedback as normal learning.
                    refresh(repository.snapshot()) { current ->
                        current.copy(answerIsKnown = action.known, canUndo = result != null,
                            calibrationFeedback = isCalibration)
                    }
                }
            } else false
            AppAction.UndoAnswer -> {
                val pending = lastAnswer
                if (pending != null && state.value.answerIsKnown != null && state.value.canUndo) {
                    perform {
                        repository.undoAnswer(pending)
                        lastAnswer = null
                        sessionAnswers = (sessionAnswers - 1).coerceAtLeast(0)
                        refresh(repository.snapshot())
                        mutableState.update {
                            it.copy(answerIsKnown = null, canUndo = false,
                                sessionPosition = sessionAnswers + 1)
                        }
                    }
                } else false
            }
            AppAction.NextWord -> if (state.value.answerIsKnown != null) {
                perform {
                    lastAnswer = null
                    showNext()
                }
            } else false
            AppAction.ReviewMistakes -> startSession(review = true)
            AppAction.ContinueLearning -> startSession(review = false)
            is AppAction.SaveProfile -> perform {
                repository.updateProfile(action.displayName, action.dailyGoal)
                refresh(repository.snapshot())
            }
            is AppAction.SetSoundEnabled -> {
                feedbackPreferences.setSoundEnabled(action.enabled)
                mutableState.update { it.copy(feedback = feedbackPreferences.read()) }
                true
            }
            is AppAction.SetHapticsEnabled -> {
                feedbackPreferences.setHapticsEnabled(action.enabled)
                mutableState.update { it.copy(feedback = feedbackPreferences.read()) }
                true
            }
            is AppAction.SetPronunciationAccent -> {
                feedbackPreferences.setPronunciationAccent(action.accent)
                // Both readings stay on each displayed word, so this also updates an open
                // word detail and the current question without advancing or re-answering it.
                mutableState.update { it.copy(feedback = feedbackPreferences.read()) }
                true
            }
            AppAction.FeedbackTap -> true
            AppAction.Retry -> load()
        }

    fun refreshStats(): Boolean {
        // Foreground refresh must not dismiss a failed word preparation and replace
        // its retry screen with an apparently empty learning session.
        if (state.value.isLoading || state.value.errorMessage != null) return false
        return perform { refresh(repository.snapshot()) }
    }

    /** Refresh only at a calendar or review boundary; don't reread the database every second
     * or advance the card while the learner is reading its details. */
    private fun refreshTimeSensitiveUi(): Boolean {
        if (!studyForeground || busy || state.value.isLoading || state.value.errorMessage != null) return false
        val snapshot = latestSnapshot ?: return false
        val zone = currentZone()
        val today = Instant.ofEpochMilli(wallNow()).atZone(zone).toLocalDate()
        val now = wallNow()
        if (today == statsDate && zone == statsZone && now >= statsAsOf && now < nextReviewUiRefreshAt) return false
        return perform { refresh(snapshot) }
    }

    override fun onCleared() {
        queueStudyTime(studyClock.setForeground(false))
        timeWriter.launch {
            flushStudyTime()
            (repository as? Closeable)?.close()
            timeWriter.cancel()
        }
        super.onCleared()
    }

    private fun load() = perform {
        val started = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(isLoading = true, errorMessage = null, currentWord = null, canUndo = false) }
        syncStudyMode()
        lastAnswer = null
        val snapshot = repository.snapshot()
        Log.d("LingoStartup", "repositoryReadyMs=${SystemClock.elapsedRealtime() - started}")
        relationReadings = withContext(Dispatchers.IO) {
            val words = scheduler.indexedWords(snapshot)
            getApplication<Application>().assets.open("related_pronunciations.json").bufferedReader().use { reader ->
                RelatedPronunciationCodec.read(reader, words)
            }
        }
        Log.d("LingoStartup", "readingsReadyMs=${SystemClock.elapsedRealtime() - started}")
        refresh(snapshot)
        sessionAnswers = 0
        mutableState.update { it.copy(sessionSize = Int.MAX_VALUE, sessionPosition = 1) }
        showNext()
        Log.d("LingoStartup", "wordReadyMs=${SystemClock.elapsedRealtime() - started}")
    }

    private fun startSession(review: Boolean) = perform {
        beginSession(review)
    }

    private suspend fun beginSession(review: Boolean) {
        mutableState.update { it.copy(isLoading = true, currentWord = null, canUndo = false) }
        syncStudyMode()
        lastAnswer = null
        sessionAnswers = 0
        val snapshot = repository.snapshot()
        refresh(snapshot, reviewMode = review) {
            it.copy(selectedTab = AppTab.LEARN, reviewMode = review, sessionSize = Int.MAX_VALUE,
                sessionPosition = 1, sessionComplete = false, answerIsKnown = null,
                canUndo = false, selectedWord = null)
        }
        showNext()
    }

    private suspend fun showNext() {
        val word = repository.nextWord(state.value.reviewMode)
        mutableState.update {
            it.copy(currentWord = word?.toUi(vocabulary = vocabularyIndex, extraReadings = relationReadings), answerIsKnown = null, canUndo = false,
                sessionComplete = word == null, sessionPosition = sessionAnswers + 1, isLoading = false, calibrationFeedback = false)
        }
        syncStudyMode()
    }

    private suspend fun refresh(
        snapshot: LearningSnapshot,
        reviewMode: Boolean = state.value.reviewMode,
        updateUi: (AppUiState) -> AppUiState = { it },
    ) {
        if (!timeLoaded) {
            snapshot.studyTimes.forEach { recordedTime[it.date] = it.elapsedMillis }
            timeLoaded = true
        }
        val refreshNow = wallNow()
        val refreshZone = currentZone()
        withContext(Dispatchers.Default) {
        val now = refreshNow
        val zone = refreshZone
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDate = snapshot.logs.groupBy { Instant.ofEpochMilli(it.answeredAt).atZone(zone).toLocalDate() }
        val todayLogs = byDate[today].orEmpty()
        var streakDays = 0
        var streakCursor = today
        while (byDate[streakCursor].orEmpty().isNotEmpty()) {
            streakDays++
            streakCursor = streakCursor.minusDays(1)
        }
        val words = scheduler.indexedWords(snapshot)
        vocabularyIndex = words
        val placement = scheduler.placementStatus(snapshot)
        val level = LearningLevel.describe(snapshot.profile.level)
        val label = if (placement.isCalibrating) "校准中" else level.label
        val mistakes = snapshot.progress.filter { it.isMistake && it.wordId !in snapshot.excludedWordIds }
            .sortedWith(compareBy({ it.accuracy }, { it.lastReviewedAt ?: 0 }))
            .mapNotNull { progress ->
                words[progress.wordId]?.let { word ->
                    WordProgressUi(word.toUi(includeDetails = false), progress.correctCount, progress.incorrectCount,
                        (progress.accuracy * 100).roundToInt(),
                        if (scheduler.reviewEligibleAt(progress) <= now) "待巩固 · 优先复习" else "待巩固 · 间隔未到")
                }
            }
        mutableState.update {
            updateUi(it.copy(errorMessage = null, mistakes = mistakes, excludedWordIds = snapshot.excludedWordIds,
                sessionSize = Int.MAX_VALUE,
                placement = PlacementUi(placement.isCalibrating, placement.sampleCount, placement.maxSamples,
                    placement.confidence.toFloat(), label),
                dueCount = scheduler.dueReviewCount(snapshot, reviewOnly = reviewMode),
                stats = StatsUi(
                    todayReviewed = todayLogs.size,
                    todayKnown = todayLogs.count { log -> log.known },
                    totalReviewed = snapshot.logs.size,
                    learnedWords = snapshot.progress.size,
                    masteredWords = snapshot.progress.count { p -> p.isMastered || p.wordId in snapshot.excludedWordIds },
                    learningDays = byDate.size,
                    streakDays = streakDays,
                    todayLearnedWords = todayLogs.map { log -> log.wordId }.distinct().size,
                    todayStudySeconds = it.stats.todayStudySeconds,
                    totalStudySeconds = it.stats.totalStudySeconds,
                    currentLevelLabel = label,
                    currentLevelStage = if (placement.isCalibrating) "首次水平校准" else level.stage,
                    currentLevelDescription = if (placement.isCalibrating)
                        "完成首次 ${placement.maxSamples} 词后显示初步水平，从 3 级起步，根据回答逐步升降难度。" else level.description,
                    week = (6 downTo 0).map { offset ->
                        val date = today.minusDays(offset.toLong())
                        val logs = byDate[date].orEmpty()
                        StudyDayUi(if (offset == 0) "今天" else date.format(DateTimeFormatter.ofPattern("M/d")),
                            logs.size, logs.count { log -> log.known })
                    },
                ),
                profile = ProfileUi(snapshot.profile.name, snapshot.profile.dailyGoal,
                    Instant.ofEpochMilli(snapshot.profile.createdAt).atZone(zone).toLocalDate().toString()),
            ))
        }
        }
        latestSnapshot = snapshot
        statsZone = refreshZone
        statsDate = Instant.ofEpochMilli(refreshNow).atZone(refreshZone).toLocalDate()
        statsAsOf = refreshNow
        // Use the same eligibility rules as the scheduler. Excluded, retired and
        // already-due cards must not force a full stats refresh on every tick.
        val progressById = snapshot.progress.associateBy { it.wordId }
        val reviewDeadlines = snapshot.progress.asSequence().filter {
            it.answerCount > 0 && !it.isMastered && it.wordId in vocabularyIndex &&
                it.wordId !in snapshot.excludedWordIds && (!reviewMode || it.isMistake)
        }.map { scheduler.reviewEligibleAt(it) }
        val calibrationDeadlines = if (reviewMode) emptySequence() else snapshot.calibration?.answers.orEmpty()
            .asSequence().filter { it.wordId in vocabularyIndex && it.wordId !in snapshot.excludedWordIds &&
                (progressById[it.wordId]?.answerCount ?: 0) == 0 }
            .map { it.answeredAt + LearningScheduler.CALIBRATION_RETURN_DELAY }
        nextReviewUiRefreshAt = (reviewDeadlines + calibrationDeadlines).filter { it > refreshNow }.minOrNull() ?: Long.MAX_VALUE
        syncStudyMode()
        updateTimeUi()
    }

    fun setStudyForeground(active: Boolean) {
        studyForeground = active
        queueStudyTime(studyClock.setForeground(active))
        syncStudyMode()
        updateTimeUi()
        if (!active) timeWriter.launch { flushStudyTime() }
    }

    fun userInteracted() {
        queueStudyTime(studyClock.interact())
        syncStudyMode()
    }

    private fun syncStudyMode() {
        val current = state.value
        queueStudyTime(studyClock.setLearning(current.selectedTab == AppTab.LEARN &&
            !current.isLoading && current.errorMessage == null && current.currentWord != null &&
            ((!current.placement.active && !current.calibrationFeedback) || current.reviewMode)))
    }

    private fun queueStudyTime(slice: StudySlice?) {
        if (slice == null || slice.durationMillis <= 0) return
        val last = pendingTime.lastOrNull()
        if (last != null && kotlin.math.abs(last.startedAt + last.durationMillis - slice.startedAt) <= 500) {
            pendingTime[pendingTime.lastIndex] = last.copy(durationMillis = last.durationMillis + slice.durationMillis)
        } else pendingTime.add(slice)
    }

    private fun distribute(slice: StudySlice, target: MutableMap<String, Long>) {
        val zone = currentZone()
        var cursor = slice.startedAt
        val end = cursor + slice.durationMillis
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stop = minOf(end, nextDay)
            target[date.toString()] = (target[date.toString()] ?: 0) + stop - cursor
            cursor = stop
        }
    }

    private fun updateTimeUi() {
        val totals = recordedTime.toMutableMap()
        pendingTime.forEach { distribute(it, totals) }
        writingTime?.let { distribute(it, totals) }
        val today = Instant.ofEpochMilli(wallNow()).atZone(currentZone()).toLocalDate().toString()
        mutableState.update { current -> current.copy(stats = current.stats.copy(
            todayStudySeconds = (totals[today] ?: 0L) / 1_000,
            totalStudySeconds = totals.values.sum() / 1_000,
        )) }
    }

    private suspend fun flushStudyTime() = timeWriteMutex.withLock {
        while (pendingTime.isNotEmpty()) {
            val slice = pendingTime.removeAt(0)
            writingTime = slice
            try {
                repository.recordStudyTime(slice.startedAt, slice.durationMillis)
                distribute(slice, recordedTime)
            } catch (error: Exception) {
                pendingTime.add(0, slice)
                return@withLock
            } finally { writingTime = null }
        }
        updateTimeUi()
    }

    private fun perform(block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true
        mutableState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.e("LingoLearning", "Learning operation failed", error)
                mutableState.update { it.copy(isLoading = false,
                    errorMessage = "暂时无法完成操作，请重试。") }
                syncStudyMode()
                updateTimeUi()
            } finally {
                busy = false
                mutableState.update { it.copy(isWorking = false) }
            }
        }
        return true
    }
}

private fun Word.toUi(
    includeDetails: Boolean = true,
    vocabulary: Map<String, Word> = emptyMap(),
    extraReadings: Map<String, Pair<String, String>> = emptyMap(),
): WordUi {
    fun relation(text: String, meaning: String): WordNoteUi {
        val spelling = text.trim()
        val entry = vocabulary[spelling]
        val supplement = extraReadings[spelling]
        return WordNoteUi(spelling, meaning,
            PronunciationDisplay.primary(entry?.pronunciationUs?.takeIf { it.isNotBlank() } ?: supplement?.first.orEmpty(), spelling),
            PronunciationDisplay.primary(entry?.pronunciationUk?.takeIf { it.isNotBlank() } ?: supplement?.second.orEmpty()),
            pronunciationExpected = true)
    }
    return WordUi(
    wordId = id, word = word, difficulty = "难度 $difficulty / 10",
    americanIpa = PronunciationDisplay.primary(pronunciationUs, word), partOfSpeech = partOfSpeech, meaning = meaningZh,
    examples = if (!includeDetails) emptyList() else examples.map { ExampleUi(it.en, it.zh, it.attribution, it.sourceUrl) },
    collocations = if (!includeDetails) emptyList() else collocations.map { WordNoteUi(it.en, it.zh) },
    derivatives = if (!includeDetails) emptyList() else derivatives.map { relation(it.word, it.meaningZh) },
    synonyms = if (!includeDetails) emptyList() else synonyms.map {
        val sense = it.senseEn.trim()
        relation(it.word, detailLines(it.meaningZh, if (sense.isEmpty()) "" else "对应义项：$sense"))
    },
    confusables = if (!includeDetails) emptyList() else confusables.filterNot {
        it.note.trim().startsWith("形近对照")
    }.map {
        val originalNote = it.note.trim()
        val note = if (originalNote.startsWith("同音对照：")) "" else
            originalNote.removeSuffix("请对照两词释义，不能仅凭相似拼写替换。").trim()
        relation(it.word, detailLines(it.meaningZh, note))
    },
    wordForms = if (!includeDetails) emptyList() else wordForms.map { WordNoteUi(it.form, it.label) },
    sourceLabel = "释义与词形：ECDICT · 美式音标：ipa-dict (MIT) · 英式音标：ipa-dict / ipacards (GPL-3.0) 与项目复核补充 · 例句：原创 / Tatoeba / Open English WordNet · 搭配与词族：ECDICT / WordNet",
    britishIpa = PronunciationDisplay.primary(pronunciationUk),
    similarWords = if (!includeDetails) emptyList() else confusables.filter {
        it.note.trim().startsWith("形近对照")
    }.map {
        val comparison = it.note.trim().substringAfter('：', "")
            .removeSuffix("请对照两词释义，不能仅凭相似拼写替换。").trim()
        relation(it.word, detailLines(it.meaningZh, comparison))
    },
)
}

private fun detailLines(vararg lines: String): String =
    lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
