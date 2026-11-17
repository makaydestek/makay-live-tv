package com.makay.livetv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvRecordingClient
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.makay.livetv.MainActivity
import com.makay.livetv.R
import com.makay.livetv.util.SafeLog

/** Arka planda TIF kaydini surdurur; yalnizca kayit destekleyen girislerde kullanilir. */
class RecordingService : Service() {

    private var client: TvRecordingClient? = null
    private var tuned = false
    private var requestedProgramUri: android.net.Uri? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent, startId)
            ACTION_STOP -> stopRecording()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent, startId: Int) {
        if (client != null) return
        val inputId = intent.getStringExtra(EXTRA_INPUT_ID)
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (inputId.isNullOrBlank() || channelId < 0) {
            stopSelf(startId)
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        // Hem manuel (RecordingState.start) hem zamanlanmis (TvAlarmReceiver)
        // baslatmada UI "● REC" gorur; hata durumunda fail() false'a cevirir.
        RecordingState.setRecording(true)
        requestedProgramUri = intent.getLongExtra(EXTRA_PROGRAM_ID, -1L)
            .takeIf { it >= 0L }
            ?.let(TvContract::buildProgramUri)

        client = TvRecordingClient(
            applicationContext,
            "MakayLiveTV-Recording",
            object : TvRecordingClient.RecordingCallback() {
                override fun onTuned(channelUri: android.net.Uri?) {
                    tuned = true
                    runCatching { client?.startRecording(requestedProgramUri) }
                        .onFailure { fail("Kayit baslatilamadi", it) }
                }

                override fun onRecordingStopped(recordedProgramUri: android.net.Uri?) {
                    SafeLog.i(TAG, "Kayit tamamlandi: $recordedProgramUri")
                    RecordingState.setRecording(false)
                    releaseAndStop()
                }

                override fun onConnectionFailed(inputId: String?) = fail("TV kayit oturumu acilamadi")

                override fun onDisconnected(inputId: String?) = fail("TV kayit girdisi baglantiyi kesti")

                override fun onError(error: Int) = fail("TV kayit hatasi ($error)")
            },
            Handler(Looper.getMainLooper()),
        )

        runCatching {
            client?.tune(inputId, TvContract.buildChannelUri(channelId))
        }.onFailure { fail("Kanal kayit icin ayarlanamadi", it) }
    }

    private fun stopRecording() {
        val active = client ?: run { stopSelf(); return }
        if (tuned) runCatching { active.stopRecording() }
            .onFailure { fail("Kayit durdurulamadi", it) }
        else releaseAndStop()
    }

    private fun fail(message: String, error: Throwable? = null) {
        SafeLog.w(TAG, message, error)
        RecordingState.setError(message)
        RecordingState.setRecording(false)
        releaseAndStop()
    }

    private fun releaseAndStop() {
        runCatching { client?.release() }
        client = null
        tuned = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.timer_recording_active))
            .setContentText(getString(R.string.timer_record_notification))
            .setOngoing(true)
            .addAction(0, getString(R.string.timer_record_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.timer_recording_active), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { client?.release() }
        client = null
        if (RecordingState.recording.value) RecordingState.setRecording(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.makay.livetv.recording.START"
        const val ACTION_STOP = "com.makay.livetv.recording.STOP"
        const val EXTRA_INPUT_ID = "input_id"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_PROGRAM_ID = "program_id"
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "live_recording"
        private const val NOTIFICATION_ID = 7001
        private const val REQUEST_STOP = 7002
    }
}
