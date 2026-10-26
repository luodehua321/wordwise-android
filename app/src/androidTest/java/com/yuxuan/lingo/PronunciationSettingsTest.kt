package com.yuxuan.lingo

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuxuan.lingo.feedback.FeedbackPreferences
import com.yuxuan.lingo.ui.FeedbackUi
import com.yuxuan.lingo.ui.PronunciationAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PronunciationSettingsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun accentPersistsAlongsideExistingSoundAndHapticsPreferences() {
        val name = "pronunciation-test-${UUID.randomUUID()}"
        try {
            val preferences = FeedbackPreferences(context, name)
            assertEquals(FeedbackUi(), preferences.read())
            preferences.setSoundEnabled(false)
            preferences.setPronunciationAccent(PronunciationAccent.UK)
            preferences.setHapticsEnabled(false)
            val expected = FeedbackUi(false, false, PronunciationAccent.UK)
            assertEquals(expected, FeedbackPreferences(context, name).read())
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val timeout = SystemClock.elapsedRealtime() + 3_000
            while ((!file.exists() || !file.readText().contains(">UK<")) &&
                SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(25)
            assertTrue("Accent preference must reach local storage", file.exists() && file.readText().contains(">UK<"))
            preferences.setPronunciationAccent(PronunciationAccent.US)
            assertEquals(expected.copy(pronunciationAccent = PronunciationAccent.US), preferences.read())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun existingSettingsAndUnknownAccentSafelyDefaultToAmerican() {
        val name = "pronunciation-legacy-test-${UUID.randomUUID()}"
        try {
            val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue(stored.edit().putBoolean("sound", false).putBoolean("haptics", true).commit())
            assertEquals(FeedbackUi(false, true, PronunciationAccent.US), FeedbackPreferences(context, name).read())
            assertTrue(stored.edit().putString("pronunciation-accent", "unrecognized").commit())
            assertEquals(FeedbackUi(false, true, PronunciationAccent.US), FeedbackPreferences(context, name).read())
        } finally { context.deleteSharedPreferences(name) }
    }
}
