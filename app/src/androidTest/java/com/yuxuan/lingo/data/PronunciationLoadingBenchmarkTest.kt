package com.yuxuan.lingo.data

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Same asset and word index, alternating legacy/new parser to reduce order bias. */
class PronunciationLoadingBenchmarkTest {
    @Test fun compareParsersAndPreserveEveryEffectiveAccent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pronunciation-benchmark-${UUID.randomUUID()}.db"
        RoomLearningRepository(context, databaseName = name).use { repository ->
            val words = repository.snapshot().words.associateBy { it.id }
            val runtime = Runtime.getRuntime()
            val results = JSONArray()
            fun read(legacy: Boolean): Map<String, Pair<String, String>> =
                context.assets.open("related_pronunciations.json").bufferedReader().use { reader ->
                    if (!legacy) RelatedPronunciationCodec.read(reader, words) else {
                        val json = JSONObject(reader.readText())
                        json.keys().asSequence().associateWith { key ->
                            val item = json.getJSONObject(key)
                            item.optString("us") to item.optString("uk")
                        }
                    }
                }
            val reference = read(true)
            val optimized = read(false)
            for ((head, old) in reference) {
                val main = words[head]
                assertEquals(head, main?.pronunciationUs?.takeIf { it.isNotBlank() } ?: old.first,
                    main?.pronunciationUs?.takeIf { it.isNotBlank() } ?: optimized[head]?.first.orEmpty())
                assertEquals(head, main?.pronunciationUk?.takeIf { it.isNotBlank() } ?: old.second,
                    main?.pronunciationUk?.takeIf { it.isNotBlank() } ?: optimized[head]?.second.orEmpty())
            }
            repeat(5) { iteration ->
                for (legacy in if (iteration % 2 == 0) listOf(true, false) else listOf(false, true)) {
                    runtime.gc()
                    SystemClock.sleep(150)
                    val before = runtime.totalMemory() - runtime.freeMemory()
                    val started = SystemClock.elapsedRealtimeNanos()
                    val parsed = read(legacy)
                    val millis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    runtime.gc()
                    SystemClock.sleep(150)
                    results.put(JSONObject().put("iteration", iteration).put("legacy", legacy)
                        .put("millis", millis).put("retainedHeapDeltaBytes", runtime.totalMemory() - runtime.freeMemory() - before)
                        .put("retainedEntries", parsed.size))
                }
            }
            println("LINGO_PRONUNCIATION_BENCHMARK " + results)
        }
        context.deleteDatabase(name)
        Unit
    }
}
