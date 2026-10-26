package com.yuxuan.lingo.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.yuxuan.lingo.R
import com.yuxuan.lingo.ui.AppAction
import com.yuxuan.lingo.ui.FeedbackUi
import java.io.Closeable

/** Offline media effects with a vibration fallback for OEMs without view haptics. */
class InteractionFeedback(context: Context, private val view: View) : Closeable {
    private val vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    private var pendingSample: Int? = null
    private var pendingUntil = 0L
    private val sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val ready = mutableSetOf<Int>()
    private var closed = false
    private var foreground = true
    private var activeStream = 0
    private val tap: Int
    private val known: Int
    private val unknown: Int

    internal val loadedSampleCount: Int get() = ready.size

    init {
        sounds.setOnLoadCompleteListener { _, sampleId, status ->
            view.post {
                if (status == 0 && !closed) {
                    ready.add(sampleId)
                    if (pendingSample == sampleId) {
                        pendingSample = null
                        if (foreground && SystemClock.uptimeMillis() <= pendingUntil && view.isAttachedToWindow) playSample(sampleId)
                    }
                }
            }
        }
        tap = sounds.load(context, R.raw.feedback_tap, 1)
        known = sounds.load(context, R.raw.feedback_known, 1)
        unknown = sounds.load(context, R.raw.feedback_unknown, 1)
    }

    fun play(action: AppAction, settings: FeedbackUi): Int {
        if (closed || !foreground || !view.isAttachedToWindow) return 0
        var stream = 0
        pendingSample = null
        if (settings.soundEnabled) {
            val sample = when (action) {
                is AppAction.Answer -> if (action.known) known else unknown
                else -> tap
            }
            if (sample in ready) stream = playSample(sample)
            else {
                pendingSample = sample
                pendingUntil = SystemClock.uptimeMillis() + 300
            }
        }
        if (settings.hapticsEnabled) {
            val effect = when {
                Build.VERSION.SDK_INT >= 30 && action is AppAction.Answer ->
                    if (action.known) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
            try {
                if (!view.performHapticFeedback(effect) && vibrator?.hasVibrator() == true) {
                    // Explicit in-app opt-in also works when OEM view haptics are unavailable.
                    // DEFAULT_AMPLITUDE supports motors without amplitude control (API 26+).
                    vibrator.vibrate(VibrationEffect.createOneShot(
                        if (action is AppAction.Answer) 24L else 12L, VibrationEffect.DEFAULT_AMPLITUDE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
                }
            } catch (_: SecurityException) {
                // Device policy can deny vibration even with the normal manifest permission.
            }
        }
        return stream
    }

    private fun playSample(sample: Int): Int {
        if (activeStream != 0) sounds.stop(activeStream)
        activeStream = sounds.play(sample, .55f, .55f, 1, 0, 1f)
        return activeStream
    }

    fun stop() {
        pendingSample = null
        if (!closed && activeStream != 0) sounds.stop(activeStream)
        activeStream = 0
        try {
            vibrator?.cancel()
        } catch (_: SecurityException) {
            // Some device policies deny cancellation as well as starting vibration.
        }
    }

    /** A decor view can remain attached after onPause; attachment alone is not a lifecycle gate. */
    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) stop()
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        ready.clear()
        sounds.release()
    }
}
