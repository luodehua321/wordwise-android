package com.yuxuan.lingo.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.domain.DailyStudyTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyTimeRepositoryTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun midnightSplittingConcurrentAdditionsAndReopeningKeepExactElapsedTime() = runBlocking {
        val name = "lingo-study-time-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("Asia/Shanghai")
        val start = Instant.parse("2026-10-23T15:59:55Z").toEpochMilli()
        try {
            RoomLearningRepository(context, databaseName = name, zoneId = zone).use { repository ->
                val card = requireNotNull(repository.nextWord())
                repository.answer(card.id, known = false)
                val before = repository.snapshot()
                repository.recordStudyTime(start, 15_000L)
                coroutineScope {
                    List(20) { async { repository.recordStudyTime(start, 1_000L) } }.awaitAll()
                }
                repository.recordStudyTime(start, 0L)
                repository.recordStudyTime(start, -1_000L)
                val timed = repository.snapshot()
                assertEquals(listOf(DailyStudyTime("2026-10-23", 25_000L), DailyStudyTime("2026-10-24", 10_000L)), timed.studyTimes)
                assertEquals(before.profile, timed.profile)
                assertEquals(before.logs, timed.logs)
                assertEquals(before.progress, timed.progress)
            }
            RoomLearningRepository(context, databaseName = name, zoneId = zone).use { reopened ->
                val snapshot = reopened.snapshot()
                assertEquals(35_000L, snapshot.studyTimes.sumOf { it.elapsedMillis })
                assertEquals(1L, snapshot.profile.totalAnswers)
                assertEquals(1, snapshot.logs.size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun daylightSavingDaysUseActualTwentyThreeAndTwentyFiveHourBoundaries() = runBlocking {
        val name = "lingo-study-dst-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("America/New_York")
        try {
            RoomLearningRepository(context, databaseName = name, zoneId = zone).use { repository ->
                val springMidnight = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
                val fallMidnight = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli()
                repository.recordStudyTime(springMidnight, 23 * HOUR + 1_000L)
                repository.recordStudyTime(fallMidnight, 25 * HOUR + 1_000L)
                assertEquals(listOf(
                    DailyStudyTime("2026-03-08", 23 * HOUR),
                    DailyStudyTime("2026-03-09", 1_000L),
                    DailyStudyTime("2026-11-01", 25 * HOUR),
                    DailyStudyTime("2026-11-02", 1_000L),
                ), repository.snapshot().studyTimes)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun systemZoneChangesAffectFutureWritesWithoutRewritingSavedDatesOrLearningHistory() = runBlocking {
        val name = "lingo-study-zone-change-${UUID.randomUUID()}.db"
        val zone = AtomicReference(ZoneId.of("Asia/Shanghai"))
        val start = Instant.parse("2026-10-25T18:00:00Z").toEpochMilli()
        try {
            RoomLearningRepository(context, databaseName = name, systemZone = zone::get).use { repository ->
                val before = repository.snapshot()
                repository.recordStudyTime(start, 3_000L)
                zone.set(ZoneId.of("America/Los_Angeles"))
                repository.recordStudyTime(start, 7_000L)
                val after = repository.snapshot()
                assertEquals(listOf(DailyStudyTime("2026-10-25", 7_000L), DailyStudyTime("2026-10-26", 3_000L)), after.studyTimes)
                assertEquals(before.profile, after.profile)
                assertEquals(before.progress, after.progress)
                assertEquals(before.logs, after.logs)
            }
            RoomLearningRepository(context, databaseName = name, systemZone = zone::get).use { reopened ->
                assertEquals(listOf(DailyStudyTime("2026-10-25", 7_000L), DailyStudyTime("2026-10-26", 3_000L)), reopened.snapshot().studyTimes)
            }
        } finally { context.deleteDatabase(name) }
    }

    private companion object { const val HOUR = 3_600_000L }
}
