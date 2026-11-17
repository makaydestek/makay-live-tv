package com.makay.livetv.domain.model

import android.net.Uri

/**
 * TIF (android.media.tv) kanalinin uygulama ici temsili.
 *
 * Tum alanlar sistem `TvProvider`'indan okunur; [id] kanal kimligi
 * (`TvContract.Channels._ID`), [inputId] ise kanalin ait oldugu
 * [android.media.tv.TvInputInfo] kimligidir. Oynatma icin
 * `TvView.tune(inputId, TvContract.buildChannelUri(id))` kullanilir.
 *
 * @property displayNumber kullaniciya gosterilen kanal numarasi (or. "101", "7.2").
 * @property sortKey numara siralamsinda "7.2" gibi degerlerin dogru siralanmasi icin.
 */
data class Channel(
    val id: Long,
    val inputId: String,
    val inputLabel: String,
    val displayNumber: String,
    val displayName: String,
    val description: String? = null,
    val type: ChannelType = ChannelType.OTHER,
    val isFavorite: Boolean = false,
    val isBrowsable: Boolean = true,
    val isSearchable: Boolean = true,
    val isLocked: Boolean = false,
    val logoUri: Uri? = null,
    val serviceType: String? = null,
    val videoFormat: String? = null,
    val sortPosition: Int = 0,
    /** `TvContract.Channels.COLUMN_LOCKED` → ebeveyn kilidi. */
    val isBlocked: Boolean = false,
    /** Yalnizca bu uygulamada gizlenen kanal; sistem TV listesine yazilmaz. */
    val isLocallyHidden: Boolean = false,
    /** PIN ile korunan, yalnizca bu uygulamadaki kanal kilidi. */
    val isLocallyLocked: Boolean = false,
    /**
     * Yerel silme isareti: sistem `TvProvider` satiri dokunulmaz (bazi
     * OEM'ler yazma izni vermez), kanal yalnizca bu uygulamadan kaldirilir.
     * Ayarlar > Bakim bolumunden geri getirilebilir.
     */
    val isLocallyDeleted: Boolean = false,
    /** Kanal listesinde gorunur ama CH+/- ile yapilan zap'ta atlanir. */
    val isLocallySkipped: Boolean = false,
    val isRecentlyWatched: Boolean = false,
) {
    val isRadio: Boolean
        get() = type == ChannelType.TERRESTRIAL && looksAudioOnly

    /**
     * Kanal adinda yaygin radyo kaliplari veya `serviceType` "DIGITAL RADIO"
     * oldugunda ses kanali kabul edilir. Kesin bilgi yoksa video var sayilir
     * (yanlis "radyo" etiketi kanalin listeden gizlenmesine yol acmasin).
     */
    private val looksAudioOnly: Boolean
        get() {
            val st = serviceType?.uppercase().orEmpty()
            if (st.contains("RADIO") && !st.contains("TV") && !st.contains("VIDEO")) return true
            val vf = videoFormat?.uppercase().orEmpty()
            return vf.isBlank() && st.isBlank()
        }

    val isHd: Boolean
        get() {
            val name = displayName.uppercase()
            if (name.contains("HD") || name.contains("UHD") || name.contains("4K")) return true
            val format = videoFormat?.uppercase().orEmpty()
            return format.contains("HD") || format.contains("UHD") || format.contains("3840")
        }

    /** Arama ve hizli erisim icin normalize edilmis anahtar. */
    val searchKey: String
        get() = "${displayNumber.lowercase()} ${displayName.lowercase()}"

    val sortKey: Double
        get() {
            val cleaned = displayNumber.replace(',', '.').trim()
            return cleaned.toDoubleOrNull() ?: Double.MAX_VALUE
        }
}
