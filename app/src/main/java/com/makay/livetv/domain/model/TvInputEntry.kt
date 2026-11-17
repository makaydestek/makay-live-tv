package com.makay.livetv.domain.model

/**
 * Cihazda bulunan bir Android TV girdisi (`TvInputInfo`) ozeti.
 *
 * TIF mimarisinde kanallar sistem `TvProvider`'inda tutulur ve her kanal
 * bir [android.media.tv.TvInputService] girdisine aittir. DVB-T/C/S icin
 * genellikle:
 *  - `org.dtvkit.inputsource`   (DroidLogic / Amlogic — Homatics)
 *  - `com.google.android.tv.dtvinput` (Google DTV)
 *  - `com.droidlogic.dtvkit.inputsource`
 * girdilerinden biri bulunur. Passthrough girdileri (HDMI1, AV...) kanal
 * listesine dahil edilmez; bunlar yalnizca [isPassthrough] ile isaretlenir.
 */
data class TvInputEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val isPassthrough: Boolean,
    val isRecordingSession: Boolean = false,
    val supportsRecord: Boolean = false,
    val supportsTuner: Boolean = true,
    val tunerCount: Int = 0,
    val priority: Int = 0,
) {
    /** Uygulama icinde gosterilecek kisa etiket: paket yerine yayin turu. */
    val shortLabel: String
        get() = when {
            isPassthrough -> "Harici giriş"
            packageName.contains("dtvkit") -> "DroidLogic DVB"
            packageName.contains("dtvinput") -> "Google DTV"
            else -> label
        }
}

/** Tuner altyapisinin durumu — UI bunun uzerinden yonlendirme yapar. */
sealed interface TunerAvailability {
    /** En az bir calisan DVB girdisi var. */
    data class Ready(val inputs: List<TvInputEntry>, val channelCount: Int) : TunerAvailability

    /** Girdi var ama kayitli kanal yok → tarama gerekli. */
    data class NeedsScan(val inputs: List<TvInputEntry>) : TunerAvailability

    /** Hic TV girdisi yok → TIF desteklenmiyor. */
    data object NotSupported : TunerAvailability
}
