package com.makay.livetv.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Kayit servisinin UI tarafindan gozlemlenen durumu ve komutlari. */
object RecordingState {
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    internal fun setRecording(value: Boolean) { _recording.value = value }
    internal fun setError(value: String?) { _error.value = value }

    fun start(context: Context, inputId: String, channelId: Long, programId: Long? = null) {
        _error.value = null
        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_INPUT_ID, inputId)
            putExtra(RecordingService.EXTRA_CHANNEL_ID, channelId)
            putExtra(RecordingService.EXTRA_PROGRAM_ID, programId ?: -1L)
        }
        ContextCompat.startForegroundService(context, intent)
        _recording.value = true
    }

    fun stop(context: Context) {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }
}
