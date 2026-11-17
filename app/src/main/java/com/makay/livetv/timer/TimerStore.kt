package com.makay.livetv.timer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerResult
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.timerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "livetv_timers",
)

/**
 * Zamanlayici (hatirlatma + kayit) kayitlarinin kalici saklanmasi.
 *
 * Kayitlar [TimerCodec] ile tek bir metin olarak tutulur. Alarm planlamasi
 * burada degil [TimerScheduler]'da yapilir; bu sinif yalnizca **kayit**
 * uzerindedir. Boylece birim testleri saf kalmaya devam eder.
 */
class TimerStore private constructor(context: Context) {

    private val store = context.applicationContext.timerDataStore

    /** Tum zamanlayicilar (siralama: baslangic zamanina gore). */
    val timers: Flow<List<TimerEntry>> =
        store.data.map { prefs -> TimerCodec.decode(prefs[KEY_TIMERS] ?: "").sortedBy { it.startUtcMillis } }

    suspend fun all(): List<TimerEntry> = timers.first()

    suspend fun get(id: Long): TimerEntry? = all().firstOrNull { it.id == id }

    /** Kayit ekler; ayni program icin tekrar eklenmez, sinir asilamaz. */
    suspend fun add(entry: TimerEntry): TimerResult {
        var result = TimerResult.ADDED
        store.edit { prefs ->
            val current = TimerCodec.decode(prefs[KEY_TIMERS] ?: "").toMutableList()
            val duplicate = current.any {
                it.channelId == entry.channelId &&
                    ((it.programId != null && it.programId == entry.programId) ||
                        (it.repeatWeekly && entry.repeatWeekly &&
                            it.programTitle == entry.programTitle &&
                            it.startUtcMillis % WEEK_MILLIS == entry.startUtcMillis % WEEK_MILLIS)) &&
                    it.status == TimerStatus.SCHEDULED
            }
            val scheduled = current.count { it.status == TimerStatus.SCHEDULED }
            when {
                duplicate -> result = TimerResult.DUPLICATE
                scheduled >= MAX_SCHEDULED_TIMERS -> result = TimerResult.LIMIT
                else -> {
                    current.removeAll { it.id == entry.id }
                    current.add(entry)
                    prefs[KEY_TIMERS] = TimerCodec.encode(current)
                    result = TimerResult.ADDED
                }
            }
        }
        return result
    }

    /** Kaydi gunceller (durum degisimi vb.); bulunamazsa sessizce yok sayilir. */
    suspend fun update(id: Long, transform: (TimerEntry) -> TimerEntry) {
        store.edit { prefs ->
            val current = TimerCodec.decode(prefs[KEY_TIMERS] ?: "")
            val next = current.map { if (it.id == id) transform(it) else it }
            prefs[KEY_TIMERS] = TimerCodec.encode(next)
        }
    }

    suspend fun setStatus(id: Long, status: TimerStatus) = update(id) { it.copy(status = status) }

    /** Haftalik zamanlayiciyi bir sonraki haftaya tasir; bir defalik timer FIRED kalir. */
    suspend fun advanceWeekly(id: Long): TimerEntry? {
        var nextEntry: TimerEntry? = null
        store.edit { prefs ->
            val entries = TimerCodec.decode(prefs[KEY_TIMERS] ?: "")
            val next = entries.map { entry ->
                if (entry.id == id && entry.repeatWeekly) {
                    entry.copy(
                        programId = null,
                        startUtcMillis = entry.startUtcMillis + WEEK_MILLIS,
                        endUtcMillis = entry.endUtcMillis + WEEK_MILLIS,
                        status = TimerStatus.SCHEDULED,
                    ).also { nextEntry = it }
                } else if (entry.id == id) {
                    entry.copy(status = TimerStatus.FIRED)
                } else entry
            }
            prefs[KEY_TIMERS] = TimerCodec.encode(next)
        }
        return nextEntry
    }

    /** Kaydi siler (iptal edilmis zamanlayicilar listeden de kaldirilir). */
    suspend fun remove(id: Long) {
        store.edit { prefs ->
            val current = TimerCodec.decode(prefs[KEY_TIMERS] ?: "")
            prefs[KEY_TIMERS] = TimerCodec.encode(current.filterNot { it.id == id })
        }
    }

    /** Gecmis/iptal kayitlari temizler — gelecek zamanlamalar korunur. */
    suspend fun prunePast(nowUtcMillis: Long) {
        store.edit { prefs ->
            val current = TimerCodec.decode(prefs[KEY_TIMERS] ?: "")
            val keep = current.filter {
                it.status == TimerStatus.SCHEDULED && it.endUtcMillis > nowUtcMillis
            }
            prefs[KEY_TIMERS] = TimerCodec.encode(keep)
        }
    }

    suspend fun hasScheduledFor(channelId: Long, programId: Long?): Boolean =
        all().any {
            it.status == TimerStatus.SCHEDULED &&
                it.channelId == channelId &&
                (programId == null || it.programId == programId)
        }

    /** Suan icin gecerli tek bir kayit uretir (id: Unix millis). */
    suspend fun newEntry(
        channel: com.makay.livetv.domain.model.Channel,
        program: com.makay.livetv.domain.model.Program,
        type: TimerType,
        repeatWeekly: Boolean = false,
        remindLeadMillis: Long = 0,
    ): TimerEntry {
        val existing = all()
        val id = (existing.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(System.currentTimeMillis())
            .let { base ->
                var candidate = base + 1
                while (existing.any { it.id == candidate }) candidate++
                candidate
            }
        return TimerEntry(
            id = id,
            channelId = channel.id,
            channelNumber = channel.displayNumber,
            channelName = channel.displayName,
            inputId = channel.inputId,
            programId = program.id,
            programTitle = program.title,
            startUtcMillis = (program.startTimeUtcMillis - remindLeadMillis)
                .coerceAtLeast(System.currentTimeMillis() + MIN_LEAD_MS),
            endUtcMillis = program.endTimeUtcMillis,
            type = type,
            repeatWeekly = repeatWeekly,
        )
    }

    companion object {
        private val KEY_TIMERS = stringPreferencesKey("timers_v1")

        /** Referans uygulamayla ayni ust sinir: etkin zamanlayici sayisi. */
        const val MAX_SCHEDULED_TIMERS = 32

        /** Alarm en az bu kadar ileriden kurulabilir (gecmis anlamsiz). */
        private const val MIN_LEAD_MS = 1_000L
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: TimerStore? = null

        fun getInstance(context: Context): TimerStore =
            instance ?: synchronized(this) {
                instance ?: TimerStore(context.applicationContext).also { instance = it }
            }
    }
}
