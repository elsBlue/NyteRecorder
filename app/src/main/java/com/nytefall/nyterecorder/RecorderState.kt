package com.nytefall.nyterecorder

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide state shared by the service and the UI. */
object RecorderState {
    val recording = MutableStateFlow(false)
    val waiting = MutableStateFlow(false) // armed, waiting for the screen to turn landscape
    val startedAt = MutableStateFlow(0L) // SystemClock.elapsedRealtime()
    val message = MutableStateFlow<String?>(null)
    val savedCount = MutableStateFlow(0) // bumps when a file is finalized, to refresh the list

    fun elapsedMs(): Long = if (recording.value) SystemClock.elapsedRealtime() - startedAt.value else 0L
}
