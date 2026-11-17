package com.makay.livetv.timer

import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType

/**
 * [TimerEntry] listesini satir tabanli bir metne cevirir/okur.
 *
 * Neden ozel bicim? `org.json` yalnizca Android'de calisir (birim testte
 * stub oldugundan `RuntimeException: Stub!` atar). Satir tabanli bicim
 * saf Kotlin'dir → `TimerCodecTest` ile dogrulanabilir. Ayrac olarak
 * `|` kullanilir; degerlerdeki `|` ve satir sonu karakterleri kaydedilirken
 * temizlenir (alan sayisi bozulmasin).
 */
object TimerCodec {

    private const val FIELD_SEP = '|'
    private const val FIELD_COUNT = 12

    fun encode(timers: List<TimerEntry>): String =
        timers.joinToString("\n") { t ->
            listOf(
                t.id.toString(),
                t.channelId.toString(),
                t.inputId,
                t.channelNumber,
                t.channelName,
                t.programId?.toString().orEmpty(),
                t.programTitle,
                t.startUtcMillis.toString(),
                t.endUtcMillis.toString(),
                t.type.name,
                t.status.name,
                t.repeatWeekly.toString(),
            ).joinToString(FIELD_SEP.toString()) { sanitize(it) }
        }

    fun decode(raw: String): List<TimerEntry> =
        raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = line.split(FIELD_SEP)
                if (f.size !in 11..FIELD_COUNT) return@mapNotNull null
                TimerEntry(
                    id = f[0].toLongOrNull() ?: return@mapNotNull null,
                    channelId = f[1].toLongOrNull() ?: return@mapNotNull null,
                    inputId = f[2],
                    channelNumber = f[3],
                    channelName = f[4],
                    programId = f[5].toLongOrNull(),
                    programTitle = f[6],
                    startUtcMillis = f[7].toLongOrNull() ?: return@mapNotNull null,
                    endUtcMillis = f[8].toLongOrNull() ?: return@mapNotNull null,
                    type = f[9].let { runCatching { TimerType.valueOf(it) }.getOrNull() }
                        ?: TimerType.REMIND,
                    status = f[10].let { runCatching { TimerStatus.valueOf(it) }.getOrNull() }
                        ?: TimerStatus.SCHEDULED,
                    repeatWeekly = f.getOrNull(11)?.toBooleanStrictOrNull() ?: false,
                )
            }
            .toList()

    private fun sanitize(value: String): String =
        value.replace('|', '/').replace('\n', ' ').replace('\r', ' ')
}
