package com.makay.livetv.tuner

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.makay.livetv.domain.model.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.channelPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "livetv_channel_prefs"
)

/**
 * `TvProvider`'da karsiligi olmayan kullanici tercihlerini tutar:
 *
 *  - **Favoriler**: `TvContract.Channels` tablosunda favori kolonu yoktur
 *    (bazi OEM'ler `COLUMN_SYSTEM_*` genisletmesi kullanir, tasinabilir degil).
 *  - **Son izlenenler**: uygulama acilirken son kanala donmek icin.
 *  - **Kanal yeniden adlandirma**: cihazin kendi listesi bozulmadan yerel etiket.
 *
 * Anahtar olarak [Channel.id] degil `inputId + displayNumber` kullanilir;
 * yeniden tarama sonrasi kanal kimlikleri degisir ama numara kalir.
 */
class ChannelPreferences private constructor(context: Context) {

    private val store = context.applicationContext.channelPrefsDataStore

    val favorites: Flow<Set<String>> =
        store.data.map { it[KEY_FAVORITES] ?: emptySet() }

    val recent: Flow<List<Long>> =
        store.data.map { prefs ->
            prefs[KEY_RECENT]?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?: emptyList()
        }

    val recentKeys: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_RECENT]?.split(',')?.filter(String::isNotBlank).orEmpty()
    }

    val lastChannelId: Flow<Long?> =
        store.data.map { it[KEY_LAST_CHANNEL]?.toLongOrNull() }

    /** Tarama sonrasinda kanal ID'si degisse bile son kanali bulmak icin kalici anahtar. */
    val lastChannelKey: Flow<String?> = store.data.map { prefs ->
        prefs[KEY_LAST_CHANNEL]?.takeIf { it.toLongOrNull() == null }
    }

    val renamedChannels: Flow<Map<String, String>> =
        store.data.map { prefs ->
            prefs[KEY_RENAMES]?.lineSequence()
                ?.mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }
                ?.toMap()
                ?: emptyMap()
        }

    suspend fun toggleFavorite(channel: Channel): Boolean {
        val key = favoriteKeyOf(channel)
        var nowFavorite = false
        store.edit { prefs ->
            val current = (prefs[KEY_FAVORITES] ?: emptySet()).toMutableSet()
            nowFavorite = if (current.contains(key)) {
                current.remove(key)
                false
            } else {
                current.add(key)
                true
            }
            prefs[KEY_FAVORITES] = current
        }
        return nowFavorite
    }

    suspend fun setHidden(channel: Channel, hidden: Boolean) {
        val key = favoriteKeyOf(channel)
        store.edit { prefs ->
            val values = (prefs[KEY_HIDDEN] ?: emptySet()).toMutableSet()
            if (hidden) values.add(key) else values.remove(key)
            prefs[KEY_HIDDEN] = values
        }
    }

    /** Yerel kanal silme (TvProvider satiri degistirilmez; geri getirilebilir). */
    suspend fun setDeleted(channel: Channel, deleted: Boolean) {
        val key = favoriteKeyOf(channel)
        store.edit { prefs ->
            val values = (prefs[KEY_DELETED] ?: emptySet()).toMutableSet()
            if (deleted) values.add(key) else values.remove(key)
            prefs[KEY_DELETED] = values
        }
    }

    /** CH+/- zap'ta atlanacak kanal isareti. */
    suspend fun setSkipped(channel: Channel, skipped: Boolean) {
        val key = favoriteKeyOf(channel)
        store.edit { prefs ->
            val values = (prefs[KEY_SKIPPED] ?: emptySet()).toMutableSet()
            if (skipped) values.add(key) else values.remove(key)
            prefs[KEY_SKIPPED] = values
        }
    }

    /** Gizli + silinen tum yerel isaretleri kaldirir (Bakim > geri getir). */
    suspend fun restoreHiddenAndDeleted() {
        store.edit { prefs ->
            prefs.remove(KEY_HIDDEN)
            prefs.remove(KEY_DELETED)
        }
    }

    /** Kanal tablosuna dokunmadan uygulama ici kanal tercihlerini JSON'a aktarir. */
    suspend fun exportBackup(): String {
        val current = store.data.first()
        val backup = JsonObject().apply {
            addProperty("formatVersion", BACKUP_FORMAT_VERSION)
            addProperty("createdAtUtcMillis", System.currentTimeMillis())
            add("favorites", current.stringSetArray(KEY_FAVORITES))
            add("hidden", current.stringSetArray(KEY_HIDDEN))
            add("deleted", current.stringSetArray(KEY_DELETED))
            add("skipped", current.stringSetArray(KEY_SKIPPED))
            add("order", current.stringArray(KEY_ORDER))
            add("recent", current.stringArray(KEY_RECENT))
            addProperty("lastChannel", current[KEY_LAST_CHANNEL].orEmpty())
            addProperty("renamedChannels", current[KEY_RENAMES].orEmpty())
        }
        return Gson().toJson(backup)
    }

    /**
     * Uygulama tercih yedegini dogrulayip tek DataStore yaziminda uygular.
     * DTVKit/TvProvider kanal tablosu degistirilmez.
     */
    suspend fun importBackup(raw: String): Boolean {
        if (raw.length > MAX_BACKUP_CHARS) return false
        val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return false
        if (runCatching { parsed.get("formatVersion")?.asInt }.getOrNull() != BACKUP_FORMAT_VERSION) return false

        fun readList(name: String): List<String>? = runCatching {
            parsed.getAsJsonArray(name).map { it.asString }
                .takeIf { it.size <= MAX_BACKUP_ITEMS && it.all(String::isNotBlank) }
        }.getOrNull()

        fun readSet(name: String): Set<String>? = readList(name)
            ?.filter { it.length <= MAX_BACKUP_KEY_CHARS && it.contains('|') }
            ?.toSet()

        val favorites = readSet("favorites") ?: return false
        val hidden = readSet("hidden") ?: return false
        val deleted = readSet("deleted") ?: return false
        val skipped = readSet("skipped") ?: return false
        val order = readList("order")?.filter { it.length <= MAX_BACKUP_KEY_CHARS } ?: return false
        val recent = readList("recent")?.filter { it.length <= MAX_BACKUP_KEY_CHARS } ?: return false
        val lastChannel = runCatching { parsed.get("lastChannel")?.asString.orEmpty() }.getOrNull() ?: return false
        val renamed = runCatching { parsed.get("renamedChannels")?.asString.orEmpty() }.getOrNull() ?: return false
        if (lastChannel.length > MAX_BACKUP_KEY_CHARS || renamed.length > MAX_BACKUP_CHARS) return false

        store.edit { prefs ->
            prefs[KEY_FAVORITES] = favorites
            prefs[KEY_HIDDEN] = hidden
            prefs[KEY_DELETED] = deleted
            prefs[KEY_SKIPPED] = skipped
            prefs[KEY_ORDER] = order.joinToString("\n")
            prefs[KEY_RECENT] = recent.joinToString(",")
            if (lastChannel.isBlank()) prefs.remove(KEY_LAST_CHANNEL) else prefs[KEY_LAST_CHANNEL] = lastChannel
            prefs[KEY_RENAMES] = renamed
        }
        return true
    }

    /** Uygulama ici kanal sirasinda bir kanali komsu satira tasir. */
    suspend fun move(channel: Channel, delta: Int, channels: List<Channel>) {
        if (delta == 0 || channels.size < 2) return
        store.edit { prefs ->
            val keys = channels.sortedWith(compareBy({ it.sortKey }, { it.displayName }))
                .map(::favoriteKeyOf)
                .distinct()
                .toMutableList()
            val saved = prefs[KEY_ORDER].orEmpty().split('\n').filter { it in keys }
            val ordered = (saved + keys.filterNot { it in saved }).toMutableList()
            val from = ordered.indexOf(favoriteKeyOf(channel))
            val to = (from + delta).coerceIn(0, ordered.lastIndex)
            if (from >= 0 && from != to) {
                val item = ordered.removeAt(from)
                ordered.add(to, item)
                prefs[KEY_ORDER] = ordered.joinToString("\n")
            }
        }
    }

    suspend fun markWatched(channel: Channel, limit: Int = RECENT_LIMIT) {
        store.edit { prefs ->
            val current = prefs[KEY_RECENT]?.split(',')?.filter(String::isNotBlank)?.toMutableList()
                ?: mutableListOf()
            val key = favoriteKeyOf(channel)
            current.remove(key)
            current.remove(channel.id.toString()) // legacy id format
            current.add(0, key)
            prefs[KEY_RECENT] = current.take(limit).joinToString(",")
            prefs[KEY_LAST_CHANNEL] = favoriteKeyOf(channel)
        }
    }

    suspend fun clearRecent() {
        store.edit { prefs ->
            prefs.remove(KEY_RECENT)
            prefs.remove(KEY_LAST_CHANNEL)
        }
    }

    suspend fun rename(channel: Channel, name: String?) {
        store.edit { prefs ->
            val current = (prefs[KEY_RENAMES] ?: "").lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMutableMap()
            if (name.isNullOrBlank()) current.remove(favoriteKeyOf(channel))
            else current[favoriteKeyOf(channel)] = name.replace('\n', ' ')
            prefs[KEY_RENAMES] = current.entries.joinToString("\n") { "${it.key}=${it.value}" }
        }
    }

    /** Favori kumesini kanal listesine uygular (tek geciste, immutable kopya). */
    suspend fun applyTo(channels: List<Channel>): List<Channel> {
        val favs = favorites.first()
        val renames = renamedChannels.first()
        val hidden = store.data.map { it[KEY_HIDDEN] ?: emptySet() }.first()
        val deleted = store.data.map { it[KEY_DELETED] ?: emptySet() }.first()
        val skipped = store.data.map { it[KEY_SKIPPED] ?: emptySet() }.first()
        val order = store.data.map { it[KEY_ORDER].orEmpty().split('\n').filter(String::isNotBlank) }.first()
        val recent = recentKeys.first().toSet()
        return channels.map { ch ->
            val key = favoriteKeyOf(ch)
            ch.copy(
                isFavorite = key in favs,
                displayName = renames[key] ?: ch.displayName,
                isLocallyHidden = key in hidden,
                isLocallyDeleted = key in deleted,
                isLocallySkipped = key in skipped,
                sortPosition = order.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE,
                isRecentlyWatched = key in recent || ch.id.toString() in recent,
            )
        }
    }

    companion object {
        private const val BACKUP_FORMAT_VERSION = 1
        private const val MAX_BACKUP_CHARS = 1_000_000
        private const val MAX_BACKUP_ITEMS = 20_000
        private const val MAX_BACKUP_KEY_CHARS = 512
        private val KEY_FAVORITES = stringSetPreferencesKey("favorites")
        private val KEY_HIDDEN = stringSetPreferencesKey("hidden_channels")
        private val KEY_DELETED = stringSetPreferencesKey("deleted_channels")
        private val KEY_SKIPPED = stringSetPreferencesKey("skipped_channels")
        private val KEY_ORDER = stringPreferencesKey("channel_order")
        private val KEY_RECENT = stringPreferencesKey("recent_ids")
        private val KEY_LAST_CHANNEL = stringPreferencesKey("last_channel_id")
        private val KEY_RENAMES = stringPreferencesKey("renamed_channels")
        private const val RECENT_LIMIT = 10

        private fun Preferences.stringSetArray(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>): JsonArray =
            JsonArray().apply { this@stringSetArray[key].orEmpty().sorted().forEach { add(it) } }

        private fun Preferences.stringArray(key: androidx.datastore.preferences.core.Preferences.Key<String>): JsonArray =
            JsonArray().apply {
                this@stringArray[key].orEmpty().split(if (key == KEY_ORDER) '\n' else ',')
                    .filter(String::isNotBlank)
                    .forEach { add(it) }
            }

        @Volatile
        private var instance: ChannelPreferences? = null

        fun getInstance(context: Context): ChannelPreferences =
            instance ?: synchronized(this) {
                instance ?: ChannelPreferences(context.applicationContext).also { instance = it }
            }

        /** Kanal kimliginden bagimsiz favori anahtari. */
        fun favoriteKeyOf(channel: Channel): String = "${channel.inputId}|${channel.displayNumber}"
    }
}
