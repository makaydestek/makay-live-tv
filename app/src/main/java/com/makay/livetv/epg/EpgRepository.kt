package com.makay.livetv.epg

import android.content.ContentResolver
import android.content.Context
import android.media.tv.TvContract
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.Program
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `TvContract.Programs` tablosundan EPG okur.
 *
 * Performans notu
 * ---------------
 * Resmi TvContract kanal/zaman URI'si kullanilir. Bazi DTVKit saglayicilari
 * SQL selection parametrelerini guvenlik nedeniyle reddeder; bu URI filtresini
 * ContentProvider tarafinda uygular ve her kanal icin yalniz istenen araligi okur.
 */
class EpgRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Verilen araliktaki tum programlar; `channelId` â†’ program listesi.
     *
     * @param fromUtcMillis aralik baslangici (dahil)
     * @param toUtcMillis aralik bitisi (haric)
     */
    suspend fun programsInRange(
        channels: List<Channel>,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Map<Long, List<Program>> = withContext(Dispatchers.IO) {
        if (channels.isEmpty() || toUtcMillis <= fromUtcMillis) return@withContext emptyMap()
        val grouped = LinkedHashMap<Long, MutableList<Program>>()
        val requestedChannelIds = channels.mapTo(HashSet()) { it.id }
        try {
            channels.forEach { channel ->
                val uri = TvContract.buildProgramsUriForChannel(channel.id, fromUtcMillis, toUtcMillis)
                val rows = queryPrograms(uri, PROJECTION)
                    ?: error("EPG saglayicisi bos cursor dondurdu: channel=${channel.id}")
                rows[channel.id].orEmpty()
                    .filter { it.endTimeUtcMillis > fromUtcMillis && it.startTimeUtcMillis < toUtcMillis }
                    .let { if (it.isNotEmpty()) grouped[channel.id] = it.toMutableList() }
            }
        } catch (error: Exception) {
            // Bazi OEM saglayicilar derived kolonlari veya kanal/zaman URI
            // filtresini desteklemiyor. Core kolonlarla tek, secimsiz sorguya
            // gecip kanallari ve zaman araligini cursor'dan sonra filtrele.
            SafeLog.w(TAG, "Filtreli EPG URI'si desteklenmedi; guvenli secimsiz sorguya geciliyor", error)
            runCatching { queryPrograms(TvContract.Programs.CONTENT_URI, CORE_PROJECTION) }
                .onFailure { SafeLog.w(TAG, "Secimsiz EPG sorgusu basarisiz", it) }
                .getOrNull()
                .orEmpty()
                .forEach { (channelId, rows) ->
                    if (channelId in requestedChannelIds) {
                        rows.filter { it.endTimeUtcMillis > fromUtcMillis && it.startTimeUtcMillis < toUtcMillis }
                            .let { if (it.isNotEmpty()) grouped[channelId] = it.toMutableList() }
                    }
                }
        }
        grouped.mapValues { (_, rows) -> rows.sortedBy { it.startTimeUtcMillis } }
    }

    private fun queryPrograms(
        uri: android.net.Uri,
        projection: Array<String>,
    ): Map<Long, List<Program>>? {
        return resolver.query(
            uri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val grouped = LinkedHashMap<Long, MutableList<Program>>()
            while (cursor.moveToNext()) {
                val program = readProgram(cursor) ?: continue
                grouped.getOrPut(program.channelId) { mutableListOf() }.add(program)
            }
            grouped.mapValues { (_, value) -> value.toList() }
        }
    }

    /** Her kanal icin "su an" ve "sonraki" programlari tek gecisde doner. */
    suspend fun nowAndNext(
        channels: List<Channel>,
        nowUtcMillis: Long,
        lookaheadMillis: Long = DEFAULT_LOOKAHEAD,
    ): Map<Long, ChannelEpg> {
        // Query end-time from "now" (rather than an arbitrary lookback): a
        // current film/program may have started hours ago and must still be
        // returned. The TvContract range URI returns programs overlapping
        // [now, end), including programs that began well before this window.
        val grouped = programsInRange(channels, nowUtcMillis, nowUtcMillis + lookaheadMillis)
        return channels.associate { ch ->
            val list = grouped[ch.id].orEmpty()
            val current = list.firstOrNull { it.isLiveAt(nowUtcMillis) }
            val next = list.firstOrNull { it.startTimeUtcMillis > nowUtcMillis }
            ch.id to ChannelEpg(current = current, next = next, timeline = list)
        }
    }

    /** Bir kanalin gunluk program akisi (EPG izgara satiri). */
    suspend fun dayTimeline(
        channel: Channel,
        dayStartUtcMillis: Long,
        dayEndUtcMillis: Long,
    ): List<Program> = programsInRange(listOf(channel), dayStartUtcMillis, dayEndUtcMillis)[channel.id].orEmpty()

    private fun readProgram(cursor: android.database.Cursor): Program? = runCatching {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Programs._ID))
        val channelId = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Programs.COLUMN_CHANNEL_ID))
        val start = cursor.getLongOrNull(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS) ?: return null
        val end = cursor.getLongOrNull(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS) ?: return null
        if (end <= start) return null

        val title = cursor.getStringOrNull(TvContract.Programs.COLUMN_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Program"

        Program(
            id = id,
            channelId = channelId,
            title = title,
            shortDescription = cursor.getStringOrNull(TvContract.Programs.COLUMN_SHORT_DESCRIPTION),
            longDescription = cursor.getStringOrNull(TvContract.Programs.COLUMN_LONG_DESCRIPTION),
            startTimeUtcMillis = start,
            endTimeUtcMillis = end,
            episodeTitle = cursor.getStringOrNull(TvContract.Programs.COLUMN_EPISODE_TITLE),
            seasonNumber = cursor.getStringOrNull(TvContract.Programs.COLUMN_SEASON_NUMBER),
            episodeNumber = cursor.getStringOrNull(TvContract.Programs.COLUMN_EPISODE_NUMBER),
            genre = cursor.getStringOrNull(TvContract.Programs.COLUMN_CANONICAL_GENRE),
            language = null,
            rating = cursor.getStringOrNull(TvContract.Programs.COLUMN_CONTENT_RATING),
            posterUri = cursor.getStringOrNull(TvContract.Programs.COLUMN_POSTER_ART_URI),
        )
    }.onFailure { SafeLog.d(TAG, "Program satiri atlandi: ${it.message}") }.getOrNull()

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getString(idx)
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getLong(idx)
    }

    companion object {
        private const val TAG = "EpgRepository"
        private const val DEFAULT_LOOKAHEAD = 12 * 60 * 60 * 1000L

        /** EPG'nin onbellek suresi - OEM'ler bu araliklarla gunceller. */
        const val EPG_CACHE_TTL_MS = 15 * 60 * 1000L

        private val PROJECTION = arrayOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_CHANNEL_ID,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION,
            TvContract.Programs.COLUMN_LONG_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_EPISODE_TITLE,
            TvContract.Programs.COLUMN_SEASON_NUMBER,
            TvContract.Programs.COLUMN_EPISODE_NUMBER,
            TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.Programs.COLUMN_CANONICAL_GENRE,
            TvContract.Programs.COLUMN_CONTENT_RATING,
            TvContract.Programs.COLUMN_POSTER_ART_URI,
        )

        /** OEM'lerin desteklemeyebilecegi turetilmis kolonlar olmadan temel liste. */
        private val CORE_PROJECTION = PROJECTION.filterNot {
            it == TvContract.Programs.COLUMN_CANONICAL_GENRE
        }.toTypedArray()
    }
}

/** Bir kanalin anlik EPG durumu. */
data class ChannelEpg(
    val current: Program?,
    val next: Program?,
    val timeline: List<Program> = emptyList(),
)
