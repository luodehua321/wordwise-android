package com.yuxuan.lingo.feedback

import android.content.Context
import com.yuxuan.lingo.ui.FeedbackUi
import com.yuxuan.lingo.ui.PronunciationAccent

/** Local interaction preferences are independent of vocabulary and learning history. */
class FeedbackPreferences(context: Context, name: String = "interaction-feedback") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun read() = FeedbackUi(
        soundEnabled = preferences.getBoolean("sound", true),
        hapticsEnabled = preferences.getBoolean("haptics", true),
        pronunciationAccent = PronunciationAccent.entries.firstOrNull {
            it.name == preferences.getString("pronunciation-accent", PronunciationAccent.US.name)
        } ?: PronunciationAccent.US,
    )

    fun setSoundEnabled(enabled: Boolean) = preferences.edit().putBoolean("sound", enabled).apply()
    fun setHapticsEnabled(enabled: Boolean) = preferences.edit().putBoolean("haptics", enabled).apply()
    fun setPronunciationAccent(accent: PronunciationAccent) =
        preferences.edit().putString("pronunciation-accent", accent.name).apply()
}
