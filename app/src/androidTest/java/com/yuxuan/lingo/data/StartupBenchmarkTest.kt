package com.yuxuan.lingo.data

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

/** Identical headless repository workload before/after; heap delta excludes UI/GPU memory. */
class StartupBenchmarkTest {
    @Test fun freshRepositoryStartupAndRetainedMemory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "current"
        val results = JSONArray()
        repeat(3) { iteration ->
            Runtime.getRuntime().gc()
            SystemClock.sleep(100)
            val runtime = Runtime.getRuntime()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val name = "benchmark-${UUID.randomUUID()}.db"
            val started = SystemClock.elapsedRealtime()
            RoomLearningRepository(context, databaseName = name).use { repository ->
                val snapshot = repository.snapshot()
                assertTrue(snapshot.words.size >= 25_000)
                assertNotNull(repository.nextWord())
                val elapsed = SystemClock.elapsedRealtime() - started
                runtime.gc()
                SystemClock.sleep(100)
                val heap = runtime.totalMemory() - runtime.freeMemory() - before
                val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                results.put(JSONObject().put("iteration", iteration).put("startupMillis", elapsed)
                    .put("retainedHeapDeltaBytes", heap).put("processPssKb", info.totalPss))
            }
            context.deleteDatabase(name)
        }
        println("LINGO_BENCHMARK " + JSONObject().put("phase", phase).put("results", results))
    }
}
