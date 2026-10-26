package com.yuxuan.lingo.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yuxuan.lingo.domain.CalibrationAnswer
import com.yuxuan.lingo.domain.AnswerLog
import com.yuxuan.lingo.domain.DailyStudyTime
import com.yuxuan.lingo.domain.ReviewProgress
import com.yuxuan.lingo.domain.UserProfile

@Entity(tableName = "words", indices = [Index("difficulty"), Index("frequencyRank")])
internal data class WordEntity(
    @PrimaryKey val id: String,
    val difficulty: Int,
    val frequencyRank: Int,
    val payload: String,
)

@Entity(
    tableName = "review_progress",
    foreignKeys = [ForeignKey(
        entity = WordEntity::class,
        parentColumns = ["id"], childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class ReviewProgressEntity(
    @PrimaryKey val wordId: String,
    val correctCount: Int,
    val incorrectCount: Int,
    val consecutiveKnown: Int,
    val firstSeenAt: Long?,
    val lastReviewedAt: Long?,
    val lastUnknownAt: Long?,
    val knownStreakStartedAt: Long?,
    val masteredAt: Long?,
    val nextReviewAt: Long,
    val lastAnsweredSequence: Long,
    val retryAfterSequence: Long,
) {
    fun toModel() = ReviewProgress(
        wordId, correctCount, incorrectCount, consecutiveKnown, firstSeenAt,
        lastReviewedAt, lastUnknownAt, knownStreakStartedAt, masteredAt,
        nextReviewAt, lastAnsweredSequence, retryAfterSequence,
    )

    companion object {
        fun fromModel(value: ReviewProgress) = ReviewProgressEntity(
            value.wordId, value.correctCount, value.incorrectCount, value.consecutiveKnown,
            value.firstSeenAt, value.lastReviewedAt, value.lastUnknownAt,
            value.knownStreakStartedAt, value.masteredAt, value.nextReviewAt,
            value.lastAnsweredSequence, value.retryAfterSequence,
        )
    }
}

@Entity(tableName = "user_profile")
internal data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val dailyGoal: Int,
    val level: Double,
    val totalAnswers: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val resetCalibrationSinceSequence: Long = 0,
) {
    fun toModel() = UserProfile(name, dailyGoal, level, totalAnswers, createdAt, resetCalibrationSinceSequence)

    companion object {
        fun fromModel(value: UserProfile) = UserProfileEntity(
            name = value.name, dailyGoal = value.dailyGoal, level = value.level,
            totalAnswers = value.totalAnswers, createdAt = value.createdAt,
            resetCalibrationSinceSequence = value.resetCalibrationSinceSequence,
        )
    }
}

@Entity(tableName = "app_metadata")
internal data class AppMetadataEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "daily_study_time")
internal data class DailyStudyTimeEntity(@PrimaryKey val date: String, val elapsedMillis: Long) {
    fun toModel() = DailyStudyTime(date, elapsedMillis)
}

@Entity(
    tableName = "answer_logs",
    foreignKeys = [ForeignKey(
        entity = WordEntity::class,
        parentColumns = ["id"], childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("wordId"), Index("answeredAt")],
)
internal data class AnswerLogEntity(
    @PrimaryKey val sequence: Long,
    val wordId: String,
    val known: Boolean,
    val answeredAt: Long,
) {
    fun toModel() = AnswerLog(sequence, wordId, known, answeredAt)

    companion object {
        fun fromModel(value: AnswerLog) = AnswerLogEntity(
            value.sequence, value.wordId, value.known, value.answeredAt,
        )
    }
}

@Entity(tableName = "calibration_sessions")
internal data class CalibrationSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val seed: Long,
    val wordIdsJson: String,
    val learningStartSequence: Long,
)

@Entity(
    tableName = "calibration_answers",
    primaryKeys = ["sessionId", "position"],
    foreignKeys = [ForeignKey(
        entity = CalibrationSessionEntity::class,
        parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
    )],
)
internal data class CalibrationAnswerEntity(
    val sessionId: String,
    val position: Int,
    val wordId: String,
    val known: Boolean,
    val answeredAt: Long,
) {
    fun toModel() = CalibrationAnswer(wordId, known, answeredAt)
}

@Dao
internal interface LearningDao {
    @Query("SELECT * FROM calibration_sessions WHERE id = :id")
    suspend fun calibration(id: String): CalibrationSessionEntity?

    @Query("SELECT * FROM calibration_answers WHERE sessionId = :id ORDER BY position")
    suspend fun calibrationAnswers(id: String): List<CalibrationAnswerEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCalibration(session: CalibrationSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCalibrationAnswer(answer: CalibrationAnswerEntity)

    @Query("UPDATE calibration_sessions SET wordIdsJson = :wordIdsJson WHERE id = :id")
    suspend fun updateCalibrationWordIds(id: String, wordIdsJson: String)

    @Query("DELETE FROM answer_logs WHERE sequence = :sequence")
    suspend fun deleteLog(sequence: Long)

    @Query("DELETE FROM review_progress WHERE wordId = :wordId")
    suspend fun deleteProgress(wordId: String)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun wordCount(): Int

    @Query("SELECT * FROM words ORDER BY difficulty, frequencyRank")
    suspend fun words(): List<WordEntity>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun word(id: String): WordEntity?

    @Query("SELECT * FROM words ORDER BY difficulty, frequencyRank, id LIMIT :limit OFFSET :offset")
    suspend fun wordsPage(limit: Int, offset: Int): List<WordEntity>

    @Query("SELECT * FROM review_progress")
    suspend fun progress(): List<ReviewProgressEntity>

    @Query("SELECT * FROM review_progress WHERE wordId = :wordId")
    suspend fun progress(wordId: String): ReviewProgressEntity?

    @Query("SELECT * FROM answer_logs ORDER BY sequence")
    suspend fun logs(): List<AnswerLogEntity>

    @Query("SELECT answer_logs.* FROM answer_logs LEFT JOIN review_progress ON answer_logs.wordId = review_progress.wordId WHERE review_progress.wordId IS NULL ORDER BY sequence")
    suspend fun logsWithoutProgress(): List<AnswerLogEntity>

    @Query("SELECT * FROM answer_logs ORDER BY sequence DESC LIMIT 1")
    suspend fun latestLog(): AnswerLogEntity?

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun profile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWords(words: List<WordEntity>)

    // Upsert performs UPDATE for existing IDs. REPLACE would delete parent words and
    // cascade-delete the learner's progress and answer history.
    @Upsert
    suspend fun upsertWords(words: List<WordEntity>)

    @Query("SELECT value FROM app_metadata WHERE `key` = :key")
    suspend fun metadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMetadata(metadata: AppMetadataEntity)

    @Query("SELECT * FROM daily_study_time ORDER BY date")
    suspend fun studyTimes(): List<DailyStudyTimeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStudyTimeIfAbsent(value: DailyStudyTimeEntity): Long

    @Query("UPDATE daily_study_time SET elapsedMillis = elapsedMillis + :durationMillis WHERE date = :date")
    suspend fun addStudyTime(date: String, durationMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReviewProgressEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(log: AnswerLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: UserProfileEntity)
}

@Database(
    entities = [WordEntity::class, ReviewProgressEntity::class, UserProfileEntity::class, AnswerLogEntity::class, AppMetadataEntity::class, DailyStudyTimeEntity::class, CalibrationSessionEntity::class, CalibrationAnswerEntity::class],
    version = 4,
    exportSchema = true,
)
internal abstract class LearningDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN resetCalibrationSinceSequence INTEGER NOT NULL DEFAULT 0")
                // v1 only sampled easy cards and counted rehearsals toward ability. Start
                // independent v2 probes while preserving all answers and mistake history.
                db.execSQL("UPDATE user_profile SET resetCalibrationSinceSequence = totalAnswers")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_study_time (date TEXT NOT NULL, elapsedMillis INTEGER NOT NULL, PRIMARY KEY(date))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS calibration_sessions (id TEXT NOT NULL, startedAt INTEGER NOT NULL, seed INTEGER NOT NULL, wordIdsJson TEXT NOT NULL, learningStartSequence INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS calibration_answers (sessionId TEXT NOT NULL, position INTEGER NOT NULL, wordId TEXT NOT NULL, known INTEGER NOT NULL, answeredAt INTEGER NOT NULL, PRIMARY KEY(sessionId, position), FOREIGN KEY(sessionId) REFERENCES calibration_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}
