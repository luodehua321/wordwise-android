package com.yuxuan.lingo.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.yuxuan.lingo.domain.CalibrationSession
import com.yuxuan.lingo.domain.AnswerResult
import com.yuxuan.lingo.domain.LearningRepository
import com.yuxuan.lingo.domain.LearningScheduler
import com.yuxuan.lingo.domain.LearningSnapshot
import com.yuxuan.lingo.domain.PLACEMENT_SAMPLE_TARGET
import com.yuxuan.lingo.domain.ProfileNames
import com.yuxuan.lingo.domain.UserProfile
import com.yuxuan.lingo.domain.Word
import com.yuxuan.lingo.domain.ReviewProgress
import java.io.Closeable
import java.io.File
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.security.SecureRandom
import java.util.concurrent.Callable

/** Local-only repository. No account, network connection, or device identifier is required. */
class RoomLearningRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: LearningScheduler = LearningScheduler(clock),
    databaseName: String = "lingo-learning.db",
    private val zoneId: ZoneId? = null,
    private val systemZone: () -> ZoneId = ZoneId::systemDefault,
) : LearningRepository, Closeable {
    private val appContext = context.applicationContext
    // Deferred until initialize() runs on Dispatchers.IO; constructing a view model must
    // never read a dictionary or open a database on the main thread.
    private val preparedAssets by lazy { readPreparedAssets() }
    private val databaseDelegate = lazy {
        Room.databaseBuilder(appContext, LearningDatabase::class.java, databaseName)
            .addMigrations(LearningDatabase.MIGRATION_1_2, LearningDatabase.MIGRATION_2_3, LearningDatabase.MIGRATION_3_4)
            .apply {
                preparedAssets?.let { prepared ->
                    // Used ONLY when no learner database exists. Room retains all existing
                    // databases and performs the normal non-destructive migrations.
                    createFromInputStream(Callable {
                        WordJsonCodec.checkedStream(
                            appContext.assets.open("prepared_vocabulary.db"), prepared.databaseHash,
                        ).buffered(65_536)
                    })
                }
            }.build()
    }
    private val database by databaseDelegate
    private val dao by lazy { database.learningDao() }
    private val initializationMutex = Mutex()
    @Volatile private var initialized = false
    @Volatile private var vocabulary: List<Word> = emptyList()

    override suspend fun snapshot(): LearningSnapshot = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction { readSnapshot() }
    }

    override suspend fun nextWord(reviewOnly: Boolean): Word? = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            val snapshot = readSnapshot()
            // New adaptive calibration queues grow one probe at a time. Persist a probe chosen
            // here before it reaches the UI so an app restart cannot reroll it.
            val session = snapshot.calibration
            if (!reviewOnly && scheduler.isCalibrationActive(snapshot) && session != null) {
                val probe = scheduler.nextCalibrationWord(snapshot)
                if (probe != null && probe.id != session.nextWordId) {
                    dao.updateCalibrationWordIds(session.id, JSONArray(session.wordIds.take(session.answers.size) + probe.id).toString())
                }
                probe
            } else scheduler.nextWord(snapshot, reviewOnly)
        }
    }

    override suspend fun answer(wordId: String, known: Boolean): AnswerResult = withContext(Dispatchers.IO) {
        initialize()
        // Progress, log and ability move together, including when two UI events arrive concurrently.
        database.withTransaction {
            val result = scheduler.recordAnswer(readSnapshot(), wordId, known)
            dao.saveProgress(ReviewProgressEntity.fromModel(result.progress))
            dao.insertLog(AnswerLogEntity.fromModel(result.log))
            dao.saveProfile(UserProfileEntity.fromModel(result.profile))
            result
        }
    }

    /** Rolls back the immediately preceding learning answer so a mis-tap can be corrected. */
    override suspend fun undoAnswer(result: AnswerResult): Unit = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            val currentProfile = requireNotNull(dao.profile()).toModel()
            // Only the still-current answer can be undone. A stale result (or a second undo)
            // must not rewind the sequence while newer logs/progress remain in the database.
            require(dao.latestLog()?.toModel() == result.log &&
                currentProfile.copy(name = result.profile.name, dailyGoal = result.profile.dailyGoal) == result.profile &&
                dao.progress(result.word.id)?.toModel() == result.progress) {
                "该回答已不是最近一次记录，无法反悔"
            }
            val previousProfile = requireNotNull(result.previousProfile) { "缺少反悔前的学习状态" }
            dao.deleteLog(result.log.sequence)
            val previous = result.previousProgress
            if (previous == null) dao.deleteProgress(result.word.id)
            else dao.saveProgress(ReviewProgressEntity.fromModel(previous))
            // Profile settings can be edited on another tab before returning to undo.
            // Revert the learning result while retaining the latest user-entered settings.
            dao.saveProfile(UserProfileEntity.fromModel(previousProfile.copy(
                name = currentProfile.name, dailyGoal = currentProfile.dailyGoal,
            )))
        }
    }

    override suspend fun answerCalibration(wordId: String, known: Boolean): Unit = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            val snapshot = readSnapshot()
            val session = requireNotNull(snapshot.calibration) { "校准尚未开始" }
            require(!session.isComplete && session.nextWordId == wordId) { "该单词不是当前校准题目" }
            dao.insertCalibrationAnswer(CalibrationAnswerEntity(
                session.id, session.answers.size, wordId, known, clock(),
            ))
            var updated = readSnapshot()
            val active = requireNotNull(updated.calibration)
            if (scheduler.isCalibrationActive(updated) && active.nextWordId == null) {
                val probe = scheduler.nextCalibrationWord(updated)
                if (probe != null) {
                    dao.updateCalibrationWordIds(active.id, JSONArray(active.wordIds + probe.id).toString())
                    updated = readSnapshot()
                }
            }
            dao.saveProfile(UserProfileEntity.fromModel(updated.profile.copy(
                level = scheduler.calibratedLevel(updated),
            )))
        }
    }

    override suspend fun markLearned(wordId: String): Unit = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            require(vocabulary.any { it.id == wordId }) { "词库中不存在该单词" }
            val excluded = readExcludedWords()
            if (wordId !in excluded) {
                require(dao.progress(wordId)?.toModel()?.isMistake == true) { "该单词已不在易错本中" }
                dao.saveMetadata(AppMetadataEntity(EXCLUDED_WORDS, JSONArray((excluded + wordId).sorted()).toString()))
            }
        }
    }

    override suspend fun unmarkLearned(wordId: String): Unit = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            dao.saveMetadata(AppMetadataEntity(EXCLUDED_WORDS,
                JSONArray((readExcludedWords() - wordId).sorted()).toString()))
        }
    }

    private suspend fun readExcludedWords(): Set<String> {
        val values = JSONArray(dao.metadata(EXCLUDED_WORDS) ?: "[]")
        return (0 until values.length()).mapTo(HashSet()) { values.getString(it) }
    }

    override suspend fun updateProfile(name: String, dailyGoal: Int): Unit = withContext(Dispatchers.IO) {
        initialize()
        database.withTransaction {
            val profile = requireNotNull(dao.profile()).toModel().copy(
                name = ProfileNames.normalize(name),
                dailyGoal = dailyGoal.coerceIn(1, 200),
            )
            dao.saveProfile(UserProfileEntity.fromModel(profile))
        }
    }

    private suspend fun createInitialCalibration(snapshot: LearningSnapshot) {
        val id = UUID.randomUUID().toString()
        val seed = SecureRandom().nextLong()
        // Returning users must not be forced through a first-use flow after an app upgrade.
        // An empty persisted session is a completed marker, preserving the existing estimate.
        val returning = snapshot.profile.totalAnswers > 0L || snapshot.logs.isNotEmpty() ||
            snapshot.progress.any { it.answerCount > 0 }
        val session = if (returning) CalibrationSession(id, clock(), seed, emptyList(), 0L)
            else scheduler.createCalibration(snapshot, id, seed)
        dao.insertCalibration(CalibrationSessionEntity(
            session.id, session.startedAt, session.seed, JSONArray(session.wordIds).toString(),
            session.learningStartSequence,
        ))
        dao.saveMetadata(AppMetadataEntity(CALIBRATION_TARGET_PREFIX + session.id, session.targetSamples.toString()))
        dao.saveMetadata(AppMetadataEntity(ACTIVE_CALIBRATION, session.id))
    }

    /** Adds elapsed foreground study time. Civil-day boundaries can be 23 or 25 hours apart. */
    override suspend fun recordStudyTime(startedAt: Long, durationMillis: Long): Unit = withContext(Dispatchers.IO) {
        if (durationMillis <= 0L) return@withContext
        val end = Math.addExact(startedAt, durationMillis)
        initialize()
        // Follow a system time-zone change while the same repository remains open.
        // Explicit test/import zones stay fixed; saved historical day keys are untouched.
        val studyZone = zoneId ?: systemZone()
        database.withTransaction {
            var cursor = startedAt
            while (cursor < end) {
                val date = Instant.ofEpochMilli(cursor).atZone(studyZone).toLocalDate()
                val nextDay = date.plusDays(1).atStartOfDay(studyZone).toInstant().toEpochMilli()
                val segmentEnd = minOf(end, nextDay)
                val elapsed = segmentEnd - cursor
                dao.insertStudyTimeIfAbsent(DailyStudyTimeEntity(date.toString(), 0L))
                dao.addStudyTime(date.toString(), elapsed)
                cursor = segmentEnd
            }
        }
    }

    private suspend fun initialize() {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return@withLock
            val manifest = appContext.assets.open("dictionary_sources.json").bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
            val declaredHash = manifest.optString("vocabularySha256")
            val needsImport = dao.wordCount() == 0 || declaredHash.isBlank() ||
                dao.metadata(VOCABULARY_HASH) != declaredHash
            // Upgrades retain the learner DB. Read validated, precompiled payloads
            // instead of decoding and re-encoding the entire source JSON again.
            val preparedImport = if (needsImport) openPreparedUpgrade() else null
            var importedHash = declaredHash
            try {
                if (needsImport && preparedImport == null) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    appContext.assets.open("vocabulary.json").use { input ->
                        val buffer = ByteArray(8192)
                        var count = input.read(buffer)
                        while (count != -1) {
                            digest.update(buffer, 0, count)
                            count = input.read(buffer)
                        }
                    }
                    importedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(declaredHash.isBlank() || declaredHash == importedHash) { "词库校验失败，请重新安装完整安装包" }
                }
                database.withTransaction {
                    if (needsImport) {
                        val activeIds = HashSet<String>()
                        if (preparedImport != null) {
                            preparedImport.forEachBatch { batch ->
                                // @Upsert updates parent rows without REPLACE/delete;
                                // foreign-key-linked progress and logs must survive.
                                dao.upsertWords(batch)
                                batch.forEach { activeIds.add(it.id) }
                            }
                        } else appContext.assets.open("vocabulary.json").bufferedReader(Charsets.UTF_8).use { reader ->
                            for (batch in WordJsonCodec.readVocabulary(reader).chunked(100)) {
                                dao.upsertWords(batch)
                                batch.forEach { activeIds.add(it.id) }
                            }
                        }
                        // Keep retired parent rows for their historical foreign keys, but never
                        // offer an entry removed from the authoritative packaged dictionary.
                        dao.saveMetadata(AppMetadataEntity(ACTIVE_VOCABULARY_IDS, JSONArray(activeIds.sorted()).toString()))
                        dao.saveMetadata(AppMetadataEntity(VOCABULARY_HASH, importedHash))
                        dao.saveMetadata(AppMetadataEntity(VOCABULARY_VERSION, manifest.optString("schemaVersion", "1")))
                    }
                    if (dao.profile() == null) {
                        dao.saveProfile(UserProfileEntity.fromModel(UserProfile(createdAt = clock())))
                    }
                }
            } finally { preparedImport?.close() }
            vocabulary = loadVocabulary(importedHash)
            database.withTransaction {
                restoreMissingProgress()
                val snapshot = readSnapshot()
                val existing = snapshot.calibration
                when {
                    existing == null -> createInitialCalibration(snapshot)
                    // v1.2 could start a new diagnostic for an existing learner. Keep old
                    // diagnostic rows for history, but stop that upgrade-only interruption.
                    !existing.isComplete && existing.learningStartSequence > 0L -> createInitialCalibration(snapshot)
                }
            }
            initialized = true
        }
    }

    /** Optional accelerated upgrade. A missing/stale/damaged cache uses JSON. */
    private fun openPreparedUpgrade(): PreparedUpgrade? {
        val prepared = preparedAssets ?: return null
        var file: File? = null
        var source: SQLiteDatabase? = null
        return try {
            val target = File.createTempFile("vocabulary-upgrade-", ".db", appContext.cacheDir)
            file = target
            WordJsonCodec.checkedStream(appContext.assets.open("prepared_vocabulary.db"), prepared.databaseHash).use { input ->
                target.outputStream().buffered(65_536).use { output -> input.copyTo(output, 65_536) }
            }
            val opened = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
            source = opened
            opened.rawQuery("SELECT value FROM app_metadata WHERE `key` = ?", arrayOf(VOCABULARY_HASH)).use {
                require(it.moveToFirst() && it.getString(0) == prepared.sourceHash)
            }
            opened.rawQuery("SELECT COUNT(*) FROM words", null).use {
                require(it.moveToFirst() && it.getInt(0) == prepared.wordCount)
            }
            PreparedUpgrade(opened, target)
        } catch (_: Exception) {
            source?.close()
            file?.delete()
            null
        }
    }

    private class PreparedUpgrade(private val source: SQLiteDatabase, private val file: File) : Closeable {
        suspend fun forEachBatch(consume: suspend (List<WordEntity>) -> Unit) {
            source.rawQuery("SELECT id, difficulty, frequencyRank, payload FROM words", null).use { cursor ->
                val batch = ArrayList<WordEntity>(100)
                while (cursor.moveToNext()) {
                    batch.add(WordEntity(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3)))
                    if (batch.size == 100) { consume(batch); batch.clear() }
                }
                if (batch.isNotEmpty()) consume(batch)
            }
        }
        override fun close() { try { source.close() } finally { file.delete() } }
    }

    private suspend fun loadVocabulary(sourceHash: String): List<Word> {
        val prepared = preparedAssets
        if (prepared != null && prepared.sourceHash == sourceHash && dao.wordCount() >= prepared.wordCount) {
            // A complete 20k-word cache: calibration and random sampling see the entire
            // vocabulary immediately, including every example, form and attribution.
            // Optional stale/damaged caches fall back to the authoritative Room payloads.
            val cached = runCatching {
                appContext.assets.open("prepared_vocabulary.bin").use { asset ->
                    WordJsonCodec.checkedStream(asset, prepared.binaryHash).buffered(65_536).use {
                        WordJsonCodec.readPreparedVocabulary(it, sourceHash, prepared.wordCount)
                    }
                }
            }.getOrNull()
            if (cached != null) return cached
        }
        // One streaming cursor avoids repeated ORDER BY / OFFSET sorting and giant
        // Lists of JSON strings. CursorWindow bounds temporary memory on older phones.
        val activeIds = dao.metadata(ACTIVE_VOCABULARY_IDS)?.let { saved ->
            val ids = JSONArray(saved)
            (0 until ids.length()).mapTo(HashSet()) { ids.getString(it) }
        } ?: appContext.assets.open("vocabulary.json").bufferedReader(Charsets.UTF_8).use {
            WordJsonCodec.readVocabularyIds(it)
        }
        val decoded = ArrayList<Word>()
        database.openHelper.readableDatabase.query(
            "SELECT id, payload FROM words ORDER BY difficulty, frequencyRank, id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) in activeIds) decoded.add(WordJsonCodec.decode(cursor.getString(1)))
            }
        }
        return decoded
    }

    /** Logs are durable learning evidence even if an old upgrade lost a derived row.
     * Replay only missing rows, using the same progress policy as live answers. Profile,
     * existing progress, calibration and original logs remain untouched in this transaction. */
    private suspend fun restoreMissingProgress() {
        for ((wordId, rows) in dao.logsWithoutProgress().groupBy { it.wordId }) {
            val word = dao.word(wordId)?.let { WordJsonCodec.decode(it.payload) } ?: continue
            var answerTime = 0L
            val recoveryScheduler = LearningScheduler({ answerTime })
            var restored: ReviewProgress? = null
            for (row in rows) {
                answerTime = row.answeredAt
                val replay = LearningSnapshot(
                    words = listOf(word), progress = listOfNotNull(restored), logs = emptyList(),
                    profile = UserProfile(totalAnswers = row.sequence - 1, createdAt = rows.first().answeredAt),
                )
                // The synthetic profile is never persisted: recovery cannot award ability
                // points again or change the learner's answer sequence.
                restored = recoveryScheduler.recordAnswer(replay, wordId, row.known).progress
            }
            restored?.let { dao.saveProgress(ReviewProgressEntity.fromModel(it)) }
        }
    }

    /** Derived files can be omitted or stale without invalidating the source dictionary. */
    private fun readPreparedAssets(): PreparedAssets? = runCatching {
        val source = appContext.assets.open("dictionary_sources.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val prepared = appContext.assets.open("prepared_vocabulary.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val hash = source.getString("vocabularySha256")
        require(hash.isNotBlank() && prepared.getString("sourceVocabularySha256") == hash)
        require(prepared.getInt("formatVersion") == 4 && prepared.getInt("roomVersion") == 4)
        require(prepared.getInt("wordCount") == source.getInt("wordCount"))
        PreparedAssets(hash, prepared.getInt("wordCount"),
            prepared.getString("databaseSha256"), prepared.getString("binarySha256"))
    }.getOrNull()

    private data class PreparedAssets(
        val sourceHash: String, val wordCount: Int, val databaseHash: String, val binaryHash: String,
    )

    private suspend fun readSnapshot(): LearningSnapshot {
        val progress = dao.progress().map { it.toModel() }
        val excluded = readExcludedWords()
        return LearningSnapshot(
            words = vocabulary,
            progress = progress,
            logs = dao.logs().map { it.toModel() },
            profile = requireNotNull(dao.profile()).toModel(),
            studyTimes = dao.studyTimes().map { it.toModel() },
            calibration = readCalibration(progress.filter { it.isMastered }.mapTo(HashSet()) { it.wordId }, excluded),
            excludedWordIds = excluded,
        )
    }

    private suspend fun readCalibration(mastered: Set<String>, excluded: Set<String>): CalibrationSession? {
        val id = dao.metadata(ACTIVE_CALIBRATION) ?: return null
        val row = dao.calibration(id) ?: return null
        val ids = JSONArray(row.wordIdsJson)
        val answers = dao.calibrationAnswers(id).map { it.toModel() }
        val eligibleIds = vocabulary.asSequence()
            .filterNot { it.id in mastered || it.id in excluded }
            .map { it.id }.toMutableSet()
        eligibleIds.addAll(answers.map { it.wordId })
        val targetKey = CALIBRATION_TARGET_PREFIX + id
        val savedTarget = dao.metadata(targetKey)?.toIntOrNull()
            ?.coerceIn(0, PLACEMENT_SAMPLE_TARGET) ?: PLACEMENT_SAMPLE_TARGET
        val target = minOf(savedTarget, eligibleIds.size)
        // readSnapshot runs in a transaction. Freeze a completed smaller diagnostic so
        // restoring excluded words or expanding the dictionary cannot restart first use.
        if (answers.size >= target && target < savedTarget) {
            dao.saveMetadata(AppMetadataEntity(targetKey, target.toString()))
        }
        return CalibrationSession(
            id = row.id,
            startedAt = row.startedAt,
            seed = row.seed,
            wordIds = (0 until ids.length()).map { ids.getString(it) },
            learningStartSequence = row.learningStartSequence,
            answers = answers,
            // Excluded, mastered and removed entries cannot supply new diagnostic probes.
            // Keep completed answers in the target even if their words later disappear.
            targetSamples = target,
        )
    }

    override fun close() {
        vocabulary = emptyList()
        initialized = false
        if (databaseDelegate.isInitialized()) database.close()
    }

    private companion object {
        const val EXCLUDED_WORDS = "excludedWordIds"
        const val ACTIVE_CALIBRATION = "activeCalibrationId"
        const val CALIBRATION_TARGET_PREFIX = "calibrationTarget:"
        const val VOCABULARY_HASH = "vocabularySha256"
        const val VOCABULARY_VERSION = "vocabularySchemaVersion"
        const val ACTIVE_VOCABULARY_IDS = "activeVocabularyWordIds"
    }
}
