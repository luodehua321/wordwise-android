package com.yuxuan.lingo

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.feedback.FeedbackPreferences
import com.yuxuan.lingo.feedback.InteractionFeedback
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.FeedbackUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeedbackIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun feedbackSwitchesPersistIndependentlyToLocalStorage() {
        val name = "feedback-test-${UUID.randomUUID()}"
        try {
            val first = FeedbackPreferences(context, name)
            assertEquals(FeedbackUi(), first.read())
            first.setSoundEnabled(false)
            assertEquals(FeedbackUi(soundEnabled = false), FeedbackPreferences(context, name).read())
            first.setHapticsEnabled(false)
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val timeout = SystemClock.elapsedRealtime() + 3_000
            while ((!file.exists() || !file.readText().contains("name=\"haptics\" value=\"false\"")) &&
                SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(25)
            assertTrue("Settings must reach disk", file.exists())
            assertTrue(file.readText().contains("name=\"sound\" value=\"false\""))
            assertEquals(FeedbackUi(false, false), FeedbackPreferences(context, name).read())
            first.setSoundEnabled(true)
            assertEquals(FeedbackUi(true, false), FeedbackPreferences(context, name).read())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun bundledEffectsDecodeAndCanPlayWithoutASpeechEngine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var player: InteractionFeedback
            scenario.onActivity { player = InteractionFeedback(it, it.window.decorView) }
            try {
                var loaded = 0
                val timeout = SystemClock.elapsedRealtime() + 5_000
                while (loaded < 3 && SystemClock.elapsedRealtime() < timeout) {
                    scenario.onActivity { loaded = player.loadedSampleCount }
                    if (loaded < 3) SystemClock.sleep(25)
                }
                assertEquals("All three offline effects must decode", 3, loaded)
                scenario.onActivity { activity ->
                    assertEquals(0, player.play(AppAction.Answer(true), FeedbackUi(false, false)))
                    assertEquals(PackageManager.PERMISSION_GRANTED,
                        activity.checkSelfPermission(Manifest.permission.VIBRATE))
                    listOf(AppAction.FeedbackTap, AppAction.Answer(true), AppAction.Answer(false)).forEach {
                        assertTrue("Decoded sample must start a media sound stream", player.play(it, FeedbackUi(true, false)) > 0)
                    }
                    player.stop()
                    assertTrue("Returning from background must still allow playback",
                        player.play(AppAction.FeedbackTap, FeedbackUi(true, false)) > 0)
                    player.setForeground(false)
                    assertEquals("An attached but paused view must not play feedback", 0,
                        player.play(AppAction.Answer(true), FeedbackUi(true, true)))
                    player.setForeground(true)
                    assertTrue("Feedback must resume after returning to the foreground",
                        player.play(AppAction.FeedbackTap, FeedbackUi(true, false)) > 0)
                    // Must be safe both with and without a motor/system view-haptics support.
                    player.play(AppAction.Answer(true), FeedbackUi(false, true))
                    player.close()
                    player.close()
                    assertEquals(0, player.play(AppAction.FeedbackTap, FeedbackUi()))
                }
            } finally { scenario.onActivity { player.close() } }
        }
    }
}
