package com.makay.livetv.tuner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.ChannelType
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.domain.model.TvInputEntry
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dahili tuner kanallarini `TvProvider`'dan okur ve degisikliklerini izler.
 *
 * Okuma kurali
 * ------------
 *  - Yalnizca [TvInputDiscovery] ile bulunan gercek TV girdilerine ait kanallar.
 *  - Passthrough girdileri (HDMI/AV) listeye alinmaz.
 *  - `COLUMN_BROWSABLE = 0` kanallar kullanici tarafindan gizlenmistir.
 *
 * Yazma kurali
 * ------------
 * Bu sinif kanal ve EPG verisini yalnizca okur. Favori, gizleme, yeniden
 * adlandirma ve ozel kanal kilitleri [ChannelPreferences] / ebeveyn kontrol
 * deposunda yerel tutulur; cihazdaki DTV kanal tablosuna yazilmaz.
 */
class ChannelRepository(
    private val context: Context,
    private val discovery: TvInputDiscovery = TvInputDiscovery(context),
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Tuner altyapisinin durumu (ekran yonlendirmesi icin). */
    suspend fun availability(): TunerAvailability = withContext(Dispatchers.IO) {
        if (!discovery.isTvInputAvailable) return@withContext TunerAvailability.NotSupported
        val inputs = discovery.discoverInputs()
        if (inputs.isEmpty()) return@withContext TunerAvailability.NotSupported
        val channels = loadChannels(inputs.map { it.id })
        if (channels.isEmpty()) {
            TunerAvailability.NeedsScan(inputs)
        } else {
            TunerAvailability.Ready(inputs, channels.size)
        }
    }

    /** Tum kanallari (varsayilan: numaraya gore) doner. */
    suspend fun loadChannels(inputIds: List<String>? = null): List<Channel> = withContext(Dispatchers.IO) {
        val resolvedInputIds = inputIds ?: discovery.discoverInputs().map { it.id }
        if (resolvedInputIds.isEmpty()) return@withContext emptyList()
        val list = queryChannels(resolvedInputIds)
        SafeLog.d(TAG, "loadChannels: ${list.size} kanal, ${resolvedInputIds.size} girdi")
        list
    }

    /**
     * `TvProvider` degisikliklerini (tarama bitti, kanal silindi, EPG guncellendi)
     * izleyen Flow. [ChannelBrowsable] degisimini filtreleyip gereksiz
     * yeniden okumalari engeller.
     */
    fun observeChannels(): Flow<List<Channel>> = callbackFlow {
        val inputs = discovery.discoverInputs()
        val inputIds = inputs.map { it.id }
        var refreshJob: Job? = null

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // DTVKit callback'i ana thread'de gelir. ContentResolver sorgusunu
                // orada yapmak kumanda/Compose cizimini bloklayabilir. Hizli arka
                // arkaya gelen bildirimleri birlestirip sorguyu IO'da calistir.
                refreshJob?.cancel()
                refreshJob = launch(Dispatchers.IO) {
                    delay(CHANNEL_REFRESH_DEBOUNCE_MS)
                    runCatching {
                        val fresh = discovery.discoverInputs().map { it.id }
                        trySend(loadChannels(fresh))
                    }.onFailure { SafeLog.w(TAG, "observeChannels onChange", it) }
                }
            }
        }

        runCatching {
            resolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer)
        }.onFailure { SafeLog.w(TAG, "ContentObserver kaydedilemedi", it) }
        // Gözlemci ilk sorgudan önce kaydedilir; sorgu sürerken tarama biterse
        // aradaki değişiklik, gecikmeli yenilemeyle mutlaka yakalanır.
        trySend(loadChannels(inputIds))

        awaitClose {
            refreshJob?.cancel()
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Ayni Flow'un yalnizca sayi degistiginde yayin yapan varyanti. */
    fun observeChannelCount(): Flow<Int> =
        observeChannels().map { it.size }.distinctUntilChanged()

    /** Tek kanal yeniden okuma (kanal degistiginde ayrinti tazelemek icin). */
    fun findChannel(channelId: Long): Channel? {
        val uri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId)
        return runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) readChannel(cursor) else null
            }
        }.getOrNull()
    }

    /**
     * Kanali favorilerden cikarirken `TvProvider`'da gizleme yapmaz; boylece
     * cihazin kendi DTV uygulamasiyla tutarlilik korunur. Favori durumu
     * [ChannelPreferences] katmaninda tutulur.
     */
    fun updateChannelDescription(channelId: Long, description: String?): Boolean {
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_DESCRIPTION, description)
        }
        return updateChannel(channelId, values)
    }

    private fun updateChannel(channelId: Long, values: ContentValues): Boolean {
        val uri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId)
        return runCatching {
            resolver.update(uri, values, null, null) > 0
        }.onFailure { SafeLog.w(TAG, "TvProvider guncellenemedi", it) }.getOrDefault(false)
    }

    private fun queryChannels(inputIds: List<String>): List<Channel> {
        // Homatics/DTVKit TV Provider blocks every caller supplied selection
        // with "Selection not allowed". Read the channel table without a
        // selection, then restrict rows to the discovered tuner IDs locally.
        val acceptedInputIds = inputIds.toHashSet()
        return runCatching {
            resolver.query(
                TvContract.Channels.CONTENT_URI,
                PROJECTION,
                null,
                null,
                ORDER_BY,
            )?.use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        val rowInputId = cursor.getStringOrNull(TvContract.Channels.COLUMN_INPUT_ID)
                        if (rowInputId in acceptedInputIds) {
                            readChannel(cursor)?.let { add(it) }
                        }
                    }
                }
            } ?: emptyList()
        }.onFailure {
            SafeLog.w(TAG, "Kanal sorgusu basarisiz (READ_TV_LISTINGS?)", it)
        }.getOrDefault(emptyList())
    }

    private fun readChannel(cursor: android.database.Cursor): Channel? {
        return runCatching {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Channels._ID))
            if (id <= 0L) return null

            val inputId = cursor.getStringOrNull(TvContract.Channels.COLUMN_INPUT_ID).orEmpty()
            val displayName = cursor.getStringOrNull(TvContract.Channels.COLUMN_DISPLAY_NAME)
                ?.takeIf { it.isNotBlank() } ?: "Kanal $id"
            val displayNumber = cursor.getStringOrNull(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                ?.takeIf { it.isNotBlank() } ?: id.toString()
            val typeRaw = cursor.getStringOrNull(TvContract.Channels.COLUMN_TYPE)
            val browsable = cursor.getIntOrNull(TvContract.Channels.COLUMN_BROWSABLE) ?: 1
            val searchable = cursor.getIntOrNull(TvContract.Channels.COLUMN_SEARCHABLE) ?: 1
            val locked = (cursor.getIntOrNull(TvContract.Channels.COLUMN_LOCKED) ?: 0) != 0
            val logo = cursor.getStringOrNull(TvContract.Channels.COLUMN_APP_LINK_POSTER_ART_URI)
            val serviceType = cursor.getStringOrNull(TvContract.Channels.COLUMN_SERVICE_TYPE)
            val videoFormat = cursor.getStringOrNull(TvContract.Channels.COLUMN_VIDEO_FORMAT)
            val description = cursor.getStringOrNull(TvContract.Channels.COLUMN_DESCRIPTION)

            if (browsable == 0) return null

            Channel(
                id = id,
                inputId = inputId,
                inputLabel = inputLabelOf(inputId),
                displayNumber = displayNumber,
                displayName = displayName,
                description = description,
                type = ChannelType.fromTvContract(typeRaw),
                isFavorite = false,
                isBrowsable = true,
                isSearchable = searchable != 0,
                isLocked = locked,
                logoUri = logo?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                serviceType = serviceType,
                videoFormat = videoFormat,
                isBlocked = locked,
            )
        }.onFailure { SafeLog.w(TAG, "Kanal satiri okunamadi", it) }.getOrNull()
    }

    private fun inputLabelOf(inputId: String): String {
        val pkg = inputId.substringBefore('/')
        return when {
            pkg.contains("droidlogic") || pkg.contains("dtvkit") -> "DroidLogic DVB"
            pkg.contains("dtvinput") -> "Google DTV"
            else -> pkg
        }
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getString(idx)
    }

    private fun android.database.Cursor.getIntOrNull(column: String): Int? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getInt(idx)
    }

    companion object {
        private const val TAG = "ChannelRepository"
        private const val CHANNEL_REFRESH_DEBOUNCE_MS = 180L

        private val PROJECTION = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_SEARCHABLE,
            TvContract.Channels.COLUMN_LOCKED,
            TvContract.Channels.COLUMN_APP_LINK_POSTER_ART_URI,
            TvContract.Channels.COLUMN_SERVICE_TYPE,
            TvContract.Channels.COLUMN_VIDEO_FORMAT,
            TvContract.Channels.COLUMN_DESCRIPTION,
        )

        private const val ORDER_BY = "${TvContract.Channels.COLUMN_DISPLAY_NUMBER} ASC"

        /** Numaraya gore dogal siralama: "7.2" â†’ 7.2, sayisal olmayanlar sona. */
        fun sortedChannels(channels: List<Channel>): List<Channel> =
            channels.sortedWith(compareBy({ it.sortKey }, { it.displayName }))
    }
}
