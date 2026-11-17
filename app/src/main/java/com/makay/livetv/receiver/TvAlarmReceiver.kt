package com.makay.livetv.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.makay.livetv.MainActivity
import com.makay.livetv.R
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType
import com.makay.livetv.timer.TimerEvent
import com.makay.livetv.timer.TimerEventBus
import com.makay.livetv.timer.TimerScheduler
import com.makay.livetv.timer.TimerStore
import com.makay.livetv.recording.RecordingService
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Zamanlayici (Timer) alarmlarini karsilar.
 *
 * Davranis:
 *  - `ACTION_TIMER`: kaydi `TimerStore` uzerinden okur, durumu FIRED yapar
 *    ve olayi [TimerEventBus]a gonderir. Uygulama ayaktaysa hatirlatma
 *    penceresi aninda acilir; degilse bildirim gosterilir (tiklaninca
 *    activity acilir ve olay intent extra'lari ile iletilir).
 *  - Sistem olaylari (boot / saat / dilim): tum zamanlayicilari yeniden
 *    planlar (AlarmManager reboot'da silinir).
 */
class TvAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        SafeLog.i(TAG, "Alarm alındı: $action")

        when (action) {
            ACTION_TIMER -> {
                val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L)
                if (timerId < 0) return
                val result = goAsync()
                scope.launch {
                    try {
                        handleTimer(context, timerId)
                    } finally {
                        result.finish()
                    }
                }
            }

            ACTION_RECORD_STOP -> {
                runCatching {
                    context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
                }.onFailure { SafeLog.w(TAG, "Zamanlanmis kayit durdurulamadi", it) }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val result = goAsync()
                scope.launch {
                    try {
                        TimerScheduler.getInstance(context).rescheduleAll()
                        SafeLog.d(TAG, "Sistem olayı: zamanlayıcılar yeniden kuruldu")
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }

    private suspend fun handleTimer(context: Context, timerId: Long) {
        val store = TimerStore.getInstance(context)
        val timer = store.get(timerId)
        if (timer == null || timer.status != TimerStatus.SCHEDULED) {
            SafeLog.d(TAG, "Zamanlayıcı yok veya zaten tetiklendi: $timerId")
            return
        }
        val nextOccurrence = store.advanceWeekly(timerId)
        nextOccurrence?.let { TimerScheduler.getInstance(context).schedule(it) }

        val event = TimerEvent(
            timerId = timer.id,
            channelId = timer.channelId,
            channelNumber = timer.channelNumber,
            channelName = timer.channelName,
            programTitle = timer.programTitle,
            isRecord = timer.type == TimerType.RECORD,
        )
        if (event.isRecord) {
            val startIntent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_INPUT_ID, timer.inputId)
                putExtra(RecordingService.EXTRA_CHANNEL_ID, timer.channelId)
                putExtra(RecordingService.EXTRA_PROGRAM_ID, timer.programId ?: -1L)
            }
            runCatching {
                scheduleRecordStop(context, timer.id, timer.endUtcMillis)
                if (timer.endUtcMillis > System.currentTimeMillis()) {
                    androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
                }
            }.onFailure { SafeLog.w(TAG, "Zamanlanmis kayit baslatilamadi", it) }
        }
        TimerEventBus.emit(event)
        postNotification(context, event)
        SafeLog.i(TAG, "Zamanlayıcı tetiklendi: ${timer.programTitle} (kayıt=${event.isRecord})")
    }

    private fun scheduleRecordStop(context: Context, timerId: Long, endUtcMillis: Long) {
        val intent = Intent(context, TvAlarmReceiver::class.java).apply {
            action = ACTION_RECORD_STOP
            putExtra(EXTRA_TIMER_ID, timerId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            timerId.toInt() xor RECORD_STOP_REQUEST_XOR,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, endUtcMillis, pending)
        } else {
            alarms.set(android.app.AlarmManager.RTC_WAKEUP, endUtcMillis, pending)
        }
    }

    /** Surec ayaktaysa UI olayi aninda alir; yine de bildirim gosterilir (kanit). */
    private fun postNotification(context: Context, event: TimerEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_timers),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TIMER_EVENT
            putExtra(EXTRA_TIMER_ID, event.timerId)
            putExtra(EXTRA_CHANNEL_ID, event.channelId)
            putExtra(EXTRA_PROGRAM_TITLE, event.programTitle)
            putExtra(EXTRA_IS_RECORD, event.isRecord)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            event.timerId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = context.getString(
            if (event.isRecord) R.string.notif_record_title else R.string.notif_remind_title,
            "${event.channelNumber} ${event.channelName}",
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(event.programTitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { manager.notify(event.timerId.toInt(), notification) }
            .onFailure { SafeLog.w(TAG, "Bildirim gösterilemedi", it) }
    }

    companion object {
        private const val TAG = "TvAlarmReceiver"
        const val ACTION_TIMER = "com.makay.livetv.ACTION_TIMER"
        const val ACTION_RECORD_STOP = "com.makay.livetv.ACTION_RECORD_STOP"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_TYPE = "timer_type"

        // Bildirim → Activity iletim anahtarlari
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_PROGRAM_TITLE = "program_title"
        const val EXTRA_IS_RECORD = "is_record"

        private const val CHANNEL_ID = "timers"
        private const val RECORD_STOP_REQUEST_XOR = 0x5253
    }
}
