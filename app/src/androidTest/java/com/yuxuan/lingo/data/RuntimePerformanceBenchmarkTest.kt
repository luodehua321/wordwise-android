package com.yuxuan.lingo.data

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.domain.LearningScheduler
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.UUID

/** Diagnostic workload: temporary databases only; no learner data is cleared. */
class RuntimePerformanceBenchmarkTest {
    @Test fun freshAndReturningRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "current"
        val results = JSONArray()
        val runtime = Runtime.getRuntime()
        repeat(3) { iteration ->
            val name = "perf-${UUID.randomUUID()}.db"
            for (fresh in listOf(true, false)) {
                runtime.gc()
                SystemClock.sleep(150)
                val before = runtime.totalMemory() - runtime.freeMemory()
                val started = SystemClock.elapsedRealtime()
                // Reflection permits this same test APK to exercise shipped v209
                // (5 parameters) and the current timezone-aware repository (6).
                val ctor = RoomLearningRepository::class.java.constructors.first {
                    it.parameterTypes.size in 5..6 && it.parameterTypes[0] == android.content.Context::class.java
                }
                val clock: () -> Long = System::currentTimeMillis
                val args = mutableListOf<Any?>(context, clock, LearningScheduler(clock), name, null)
                if (ctor.parameterTypes.size == 6) args.add({ ZoneId.systemDefault() })
                (ctor.newInstance(*args.toTypedArray()) as RoomLearningRepository).use { repository ->
                    val snapshot = repository.snapshot()
                    assertTrue(snapshot.words.size >= 28_000)
                    assertNotNull(repository.nextWord())
                    val millis = SystemClock.elapsedRealtime() - started
                    runtime.gc()
                    SystemClock.sleep(150)
                    val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    results.put(JSONObject().put("iteration", iteration).put("fresh", fresh)
                        .put("readyMillis", millis)
                        .put("retainedHeapDeltaBytes", runtime.totalMemory() - runtime.freeMemory() - before)
                        .put("processPssKb", memory.totalPss))
                }
            }
            context.deleteDatabase(name)
        }
        println("LINGO_RUNTIME_BENCHMARK " + JSONObject().put("phase", phase).put("results", results))
    }
}
