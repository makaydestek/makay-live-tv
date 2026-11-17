package com.makay.livetv.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType
import com.makay.livetv.receiver.TvAlarmReceiver
import com.makay.livetv.util.SafeLog

/**
 * Zamanlayici alarmlarini [AlarmManager] ile planlar.
 *
 * Notlar
 *  - `setExactAndAllowWhileIdle` kullanilir: TV uygulamalari tam zamanli
 *    alarm icin `SCHEDULE_EXACT_ALARM` iznini manifestte bildirir (API 31+).
 *    Izin verilmezse [canScheduleExact] false doner ve yalnizca `set()`
 *    ile yaklasik planlama yapilir — hatirlatma saniyeler sapabilir.
 *  - PendingIntent requestCode'i timer id'dir; boylece ayni timer
 *    tekrar planlandiginda eski alarm otomatik iptal olur.
 */
class TimerScheduler(context: Context) {

    // Singleton instances must never retain an Activity or another UI context.
    private val context = context.applicationContext

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Timer'i planlar; gecmis zamanli/iptal edilmis timer'lari iptal eder. */
    fun schedule(entry: TimerEntry) {
        val triggerAt = entry.startUtcMillis
        val pending = pendingIntentFor(entry)

        if (entry.status != TimerStatus.SCHEDULED || triggerAt <= System.currentTimeMillis()) {
            cancel(entry.id)
            return
        }

        runCatching {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            SafeLog.d(TAG, "Alarm kuruldu: id=${entry.id} tip=${entry.type} at=$triggerAt")
        }.onFailure {
            SafeLog.w(TAG, "Alarm kurulamadi: id=${entry.id}", it)
        }
    }

    fun cancel(timerId: Long) {
        runCatching {
            alarmManager.cancel(pendingIntentForId(timerId, TimerType.REMIND))
            alarmManager.cancel(pendingIntentForId(timerId, TimerType.RECORD))
        }.onFailure { SafeLog.w(TAG, "Alarm iptal edilemedi: id=$timerId", it) }
    }

    /** Tum zamanlayicilari (store uzerinden) yeniden planlar — boot/time degisimi. */
    suspend fun rescheduleAll() {
        val store = TimerStore.getInstance(context)
        store.prunePast(System.currentTimeMillis())
        store.all().forEach { schedule(it) }
    }

    private fun pendingIntentFor(entry: TimerEntry): PendingIntent =
        pendingIntentForId(entry.id, entry.type)

    private fun pendingIntentForId(timerId: Long, type: TimerType): PendingIntent {
        val intent = Intent(context, TvAlarmReceiver::class.java).apply {
            action = TvAlarmReceiver.ACTION_TIMER
            putExtra(TvAlarmReceiver.EXTRA_TIMER_ID, timerId)
            putExtra(TvAlarmReceiver.EXTRA_TIMER_TYPE, type.name)
        }
        return PendingIntent.getBroadcast(
            context,
            // Tip bazli farkli requestCode: ayni id'li REMIND ve RECORD ayri kalsin.
            (timerId xor (type.ordinal.toLong() shl 32)).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "TimerScheduler"

        @Volatile
        private var instance: TimerScheduler? = null

        fun getInstance(context: Context): TimerScheduler =
            instance ?: synchronized(this) {
                instance ?: TimerScheduler(context.applicationContext).also { instance = it }
            }
    }
}
