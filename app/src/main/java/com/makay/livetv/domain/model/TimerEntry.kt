package com.makay.livetv.domain.model

/**
 * Zamanlayici (timer) girisi: EPG programina bagli hatirlatma veya kayit.
 *
 * Uygulama ici tek kaynak [com.makay.livetv.timer.TimerStore]'dur; alarm
 * ateslendiginde [com.makay.livetv.receiver.TvAlarmReceiver] bu kaydi okuyup
 * ilgili islemi baslatir.
 *
 * @property startUtcMillis program baslangici (UTC) — alarm tetikleme ani
 *   ([TimerType.REMIND] icin birkac dakika onceye alinir).
 * @property programId TvProvider program kimligi (kayit icin gerekli).
 */
data class TimerEntry(
    val id: Long,
    val channelId: Long,
    val channelNumber: String,
    val channelName: String,
    val inputId: String,
    val programId: Long?,
    val programTitle: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val type: TimerType,
    val repeatWeekly: Boolean = false,
    val status: TimerStatus = TimerStatus.SCHEDULED,
)

enum class TimerType {
    /** Program basinda hatirlat. */
    REMIND,

    /** Program basinda kayit baslat. */
    RECORD,
}

enum class TimerStatus {
    SCHEDULED,
    FIRED,
    CANCELLED,
}
