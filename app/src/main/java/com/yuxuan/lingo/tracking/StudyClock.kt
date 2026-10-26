package com.yuxuan.lingo.tracking

data class StudySlice(val startedAt: Long, val durationMillis: Long)

/** Elapsed time measures duration; civil time is used only for assigning a calendar date. */
class StudyClock(
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val idleTimeoutMillis: Long = 60_000,
) {
    private var foreground = false
    private var learning = false
    private var lastInteraction = elapsedNow()
    private var cursor: Long? = null
    private var cursorWall = wallNow()

    fun setForeground(value: Boolean): StudySlice? = change {
        if (value && !foreground) lastInteraction = elapsedNow()
        foreground = value
    }

    fun setLearning(value: Boolean): StudySlice? = change {
        if (value && !learning) lastInteraction = elapsedNow()
        learning = value
    }

    fun interact(): StudySlice? = change { lastInteraction = elapsedNow() }

    private fun change(mutation: () -> Unit): StudySlice? {
        val now = elapsedNow()
        val previous = cursor
        val stop = minOf(now, lastInteraction + idleTimeoutMillis)
        val slice = if (previous != null && stop > previous) StudySlice(cursorWall, stop - previous) else null
        mutation()
        cursor = if (foreground && learning && now < lastInteraction + idleTimeoutMillis) now else null
        cursorWall = wallNow()
        return slice
    }
}
