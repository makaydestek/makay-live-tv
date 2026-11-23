package com.makay.livetv.tuner

import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import com.makay.livetv.domain.model.TvInputEntry
import com.makay.livetv.util.SafeLog

/**
 * Sistemdeki Android TV girdilerini (TvInputService) kesfeder.
 *
 * Neden TIF?
 * --------
 * Dahili DVB tuner'e dogrudan erisim yalnizca platform imzali (`android.uid.system`)
 * uygulamalara veya `android.hardware.tv.tuner` HAL'ina aciktir. Ucuncu taraf
 * uygulamalar icin desteklenen ve kararli yol **Tv Input Framework**'tur:
 * cihazdaki DTV `TvInputService` tuneri sahiplenir, kanallari `TvProvider`'a yazar,
 * biz de buradan okur ve `TvView` ile oynatiriz. Referans uygulamanin
 * (`com.nes.seilivetv`) `NesTvView.tuneChannel()` akisi da ayni mekanizmayi kullanir.
 */
class TvInputDiscovery(private val context: Context) {

    private val manager: TvInputManager? =
        context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager

    /** Cihazda TIF var mi? (TV olmayan cihazlarda servis null donebilir.) */
    val isTvInputAvailable: Boolean
        get() = manager != null

    /**
     * Kanallari barindirabilecek (passthrough olmayan) girdiler.
     * Oncelik: DroidLogic/DtvKit > Google DTV > diger.
     */
    fun discoverInputs(): List<TvInputEntry> {
        val mgr = manager ?: return emptyList()
        val raw = runCatching { mgr.tvInputList }.getOrElse {
            SafeLog.w(TAG, "tvInputList alinamadi", it)
            return emptyList()
        }
        return raw.mapNotNull { toEntry(mgr, it) }
            .filter { !it.isPassthrough && !it.isRecordingSession }
            .sortedWith(
                compareByDescending<TvInputEntry> { priorityOf(it) }
                    .thenBy { it.label }
            )
    }

    /** HDMI/AV gibi passthrough girdileri (kanal listesine girmez, ama bilgi amacli tutulur). */
    fun discoverPassthroughInputs(): List<TvInputEntry> {
        val mgr = manager ?: return emptyList()
        return runCatching { mgr.tvInputList }.getOrElse { emptyList() }
            .mapNotNull { toEntry(mgr, it) }
            .filter { it.isPassthrough }
    }

    /** Verilen girdi hala bagli mi? (cihazdan cikarilmis olabilir) */
    fun getInput(inputId: String): TvInputEntry? {
        val mgr = manager ?: return null
        val info = runCatching { mgr.getTvInputInfo(inputId) }.getOrNull() ?: return null
        return toEntry(mgr, info)
    }

    /** Girdinin Tuner sayisi — UI'da kapasite gostergesi icin. */
    fun tunerCapacity(inputId: String): Int {
        val mgr = manager ?: return 0
        val info = runCatching { mgr.getTvInputInfo(inputId) }.getOrNull() ?: return 0
        return tunerCount(info)
    }

    /** DTV girdisinin kendi kanal kurulum/tarama ekranini acar. */
    fun createSetupIntent(tkgs: Boolean = false): Intent? {
        val mgr = manager ?: return null
        val input = discoverInputs().firstOrNull() ?: return null
        // Homatics ships a DTVKit setup component whose generic TIF setup
        // intent crashes when its vendor-only satellite bean is absent.
        // Its licensed SEI Live TV app exposes the supported search flow and
        // internally initializes DTVKit with the selected satellite/LNB data.
        if (input.packageName == DROIDLOGIC_INPUT_PACKAGE && isPackageInstalled("com.nes.seilivetv")) {
            val intent = Intent("com.nes.intent.action.dvb.search")
                .setPackage("com.nes.seilivetv")
                .putExtra("tuner_type", "DVB-S")
            if (tkgs) {
                // DTVKit parses this JSON into its native TKGS scan flow.
                // 0 selects HD preference and the initial/automatic TKGS update mode.
                intent.putExtra(
                    "scan_params",
                    """{"isTkgsScan":true,"tkgsPreferredType":0,"tkgsUpdateType":0}""",
                )
            }
            return intent
        }
        val info = runCatching { mgr.getTvInputInfo(input.id) }.getOrNull() ?: return null
        return runCatching { info.createSetupIntent() }
            .onFailure { SafeLog.w(TAG, "DTV kurulum ekrani acilamadi", it) }
            .getOrNull()
    }

    /** TV girdisinin üreticiye ait gelişmiş ayar ekranini acar (uydu/TKGS vb.). */
    fun createInputSettingsIntent(): Intent? {
        val mgr = manager ?: return null
        val info = discoverInputs().firstOrNull()?.let { entry ->
            runCatching { mgr.getTvInputInfo(entry.id) }.getOrNull()
        } ?: return null
        return runCatching { info.createSettingsIntent() }
            .onFailure { SafeLog.w(TAG, "TV girdi ayarlari alinamadi", it) }
            .getOrNull()
    }

    private fun toEntry(mgr: TvInputManager, info: TvInputInfo): TvInputEntry? {
        // Yazma oturumu (recording session) girdileri kanal listesinde gorunmemeli.
        val isRecordingSession = info.infoContainsRecordingSession(this)

        // TV girdisi mi, harici giris mi? TYPE_TUNER = dahili tuner.
        val passthrough = runCatching { info.isPassthroughInput() }.getOrDefault(false)

        val label = runCatching { info.loadLabel(context)?.toString() }.getOrNull()

        return TvInputEntry(
            id = info.id,
            packageName = info.serviceInfo?.packageName.orEmpty(),
            label = label?.takeIf { it.isNotBlank() } ?: info.id.substringAfter('/'),
            isPassthrough = passthrough,
            isRecordingSession = isRecordingSession,
            supportsRecord = runCatching { info.canRecord() }.getOrDefault(false),
            tunerCount = tunerCount(info),
        )
    }

    /**
     * `TvInputInfo.isRecordingSession` API 36'da var; eski surumlerde
     * yansima ile okunur, bulunamazsa `false` kabul edilir.
     */
    private fun TvInputInfo.infoContainsRecordingSession(owner: TvInputDiscovery): Boolean {
        return runCatching {
            javaClass.getMethod("isRecordingSession").invoke(this) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun tunerCount(info: TvInputInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { info.tunerCount }.getOrDefault(0)
        } else {
            // API 26/27: tunerCount alani yok; dahili tuner girdisi icin 1 varsayilir.
            if (info.isPassthroughInput()) 0 else 1
        }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun priorityOf(entry: TvInputEntry): Int = when {
        entry.packageName.contains("droidlogic", ignoreCase = true) -> 100
        entry.packageName.contains("dtvkit", ignoreCase = true) -> 90
        entry.packageName.contains("dtvinput", ignoreCase = true) -> 80
        entry.packageName.contains("tv.dtv", ignoreCase = true) -> 70
        entry.label.contains("DVB", ignoreCase = true) -> 60
        else -> 10
    }

    companion object {
        private const val TAG = "TvInputDiscovery"

        /** DroidLogic / Amlogic varsayilan DTV girdi kimligi. */
        const val DROIDLOGIC_INPUT_PACKAGE = "org.dtvkit.inputsource"
        const val DROIDLOGIC_INPUT_PACKAGE_ALT = "com.droidlogic.dtvkit.inputsource"
        const val GOOGLE_DTV_INPUT_PACKAGE = "com.google.android.tv.dtvinput"
    }
}
