package com.makay.livetv.domain.model

/**
 * Kanal turu. TIF kanallari [TvContract.Channels.COLUMN_TYPE] ile gelir;
 * deger taninmazsa [OTHER] kullanilir (passthrough / IP kanallari).
 */
enum class ChannelType {
    /** DVB-T / DVB-T2, ATSC, ISDB-T gibi karasal yayinlar. */
    TERRESTRIAL,

    /** DVB-C / DVB-C2 kablo yayinlari. */
    CABLE,

    /** DVB-S / DVB-S2 uydu yayinlari. */
    SATELLITE,

    /** Passthrough girisleri (HDMI1, CVBS...) → TvContract.TYPE_PASSTHROUGH. */
    PASSTHROUGH,

    /** Uygulama saglayicili (IPTV vb.) kanallar. */
    OTHER,
    ;

    val isPhysicalTuner: Boolean
        get() = this == TERRESTRIAL || this == CABLE || this == SATELLITE

    val label: String
        get() = when (this) {
            TERRESTRIAL -> "DVB-T"
            CABLE -> "DVB-C"
            SATELLITE -> "DVB-S"
            PASSTHROUGH -> "Giriş"
            OTHER -> "Diğer"
        }

    companion object {
        /** `TvContract.Channels.TYPE_*` sabitlerinin karsiligi (API 26+). */
        fun fromTvContract(type: String?): ChannelType = when (type) {
            "TYPE_DVB_T", "TYPE_DVB_T2", "TYPE_ATSC_T", "TYPE_ATSC3_T",
            "TYPE_ISDB_T", "TYPE_DTMB", "TYPE_ISDB_TB",
            -> TERRESTRIAL

            "TYPE_DVB_C", "TYPE_DVB_C2", "TYPE_ATSC_C", "TYPE_ISDB_C",
            -> CABLE

            "TYPE_DVB_S", "TYPE_DVB_S2", "TYPE_ISDB_S",
            -> SATELLITE

            // TvContract sabitleri literal olarak da gelebilir.
            "TYPE_PASSTHROUGH" -> PASSTHROUGH
            else -> OTHER
        }
    }
}

/** Kanallistesi filtreleri (remount'ta korunur). */
enum class ChannelFilter {
    ALL,
    FAVORITES,
    RADIO,
    HD,
    HIDDEN,
    RECENT,
    ;

    fun matches(channel: Channel): Boolean = when (this) {
        ALL -> true
        FAVORITES -> channel.isFavorite
        RADIO -> channel.isRadio
        HD -> channel.isHd
        HIDDEN -> channel.isLocallyHidden
        RECENT -> channel.isRecentlyWatched
    }
}

/** Kanal siralama secenekleri. */
enum class ChannelSort {
    NUMBER,
    NAME,
    CUSTOM,
    ;

    fun label(): String = when (this) {
        NUMBER -> "Numara"
        NAME -> "İsim"
        CUSTOM -> "Özel"
    }
}
