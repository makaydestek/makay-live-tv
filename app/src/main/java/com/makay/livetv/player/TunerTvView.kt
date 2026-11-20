package com.makay.livetv.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.makay.livetv.R
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** TvView'un oynatma durumu — UI katmani bunun uzerinden bindirme cizer. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Tuning(val channel: Channel) : PlaybackState
    data class Playing(val channel: Channel) : PlaybackState
    data class Error(val channel: Channel, val reason: String) : PlaybackState
}

/** Secilebilir bir ses/altyazi izi. */
data class TrackOption(val id: String, val label: String)

/**
 * Yayindan okunan izler ve secili olanlar.
 *
 * `TvView.onTracksChanged` ile tazelenir; oynatma secenekleri paneli
 * bunun uzerinden cizilir.
 */
data class TrackState(
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
)

data class TimeShiftState(
    val available: Boolean = false,
    val paused: Boolean = false,
    val startPositionMs: Long = 0L,
    val currentPositionMs: Long = 0L,
)

/**
 * Dahili tuner oynatmasini yoneten kontrolcu.
 *
 * `TvView` bir Android `View`'dir; Compose ile `AndroidView` uzerinden
 * yerlestirilir. Tune islemi `inputId` + `channelUri` cifti ile yapilir —
 * TIF'in resmi ve tek desteklenen yolu budur.
 */
@SuppressLint("StaticFieldLeak")
class TunerPlayerController(
    private val context: Context,
    private val onState: (PlaybackState) -> Unit,
) {

    var tvView: TvView? = null
        private set

    var currentChannel: Channel? = null
        private set

    /** TvView bu kanal için video onayı verdiğinde tune tekrar edilmez. */
    private var confirmedChannelId: Long? = null
    /** Bir kez goruntu basariliysa oturum hazirdir; zap sirasinda tune tekrari yapilmaz. */
    private var hasConfirmedPlayback = false
    private val tuneInProgress = MutableStateFlow(false)
    private var tuneStartedAtMs = 0L
    private var playbackConfirmedAtMs: Long? = null
    private var lastVideoUnavailableReason: Int? = null
    private var trackingTimeShift = false
    private var initialTuneRetried = false
    private var weakSignalDuringTune = false

    private val _trackState = MutableStateFlow(TrackState())
    private val _timeShiftState = MutableStateFlow(TimeShiftState())

    /** Yayindaki ses/altyazi izleri (Compose bunu toplar). */
    val trackState: StateFlow<TrackState> = _trackState.asStateFlow()
    val timeShiftState: StateFlow<TimeShiftState> = _timeShiftState.asStateFlow()

    private val timeShiftCallback = object : TvView.TimeShiftPositionCallback() {
        override fun onTimeShiftCurrentPositionChanged(inputId: String?, timeMs: Long) {
            if (!trackingTimeShift) return
            _timeShiftState.value = _timeShiftState.value.copy(currentPositionMs = timeMs)
        }

        override fun onTimeShiftStartPositionChanged(inputId: String?, timeMs: Long) {
            if (!trackingTimeShift) return
            _timeShiftState.value = _timeShiftState.value.copy(startPositionMs = timeMs)
        }
    }

    private val sessionCallback = object : TvView.TvInputCallback() {
        override fun onVideoAvailable(inputId: String?) {
            if (inputId == currentChannel?.inputId) confirmPlaying()
        }

        override fun onTracksChanged(inputId: String?, tracks: MutableList<TvTrackInfo>?) {
            refreshTracks()
        }

        override fun onTrackSelected(inputId: String?, type: Int, trackId: String?) {
            refreshTracks()
        }

        override fun onTimeShiftStatusChanged(inputId: String?, status: Int) {
            _timeShiftState.value = _timeShiftState.value.copy(
                available = status == TvInputManager.TIME_SHIFT_STATUS_AVAILABLE,
                paused = false,
            )
        }

        override fun onContentBlocked(inputId: String?, rating: android.media.tv.TvContentRating?) {
            val channel = currentChannel?.takeIf { it.inputId == inputId } ?: return
            tuneInProgress.value = false
            onState(PlaybackState.Error(channel, str(R.string.err_parental_blocked)))
        }

        override fun onVideoUnavailable(inputId: String?, reason: Int) {
            val ch = currentChannel?.takeIf { it.inputId == inputId } ?: return
            if (lastVideoUnavailableReason != reason) {
                lastVideoUnavailableReason = reason
                val elapsed = SystemClock.elapsedRealtime() - tuneStartedAtMs
                SafeLog.d(TAG, "Video unavailable: channel=${ch.id}, reason=$reason, sinceTuneMs=$elapsed")
            }
            when (reason) {
                TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING,
                -> Unit

                TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> {
                    // The tuner can briefly report weak signal while locking to a channel.
                    if (tuneInProgress.value &&
                        SystemClock.elapsedRealtime() - tuneStartedAtMs < TUNE_SIGNAL_GRACE_MS
                    ) {
                        weakSignalDuringTune = true
                        return
                    }
                    tuneInProgress.value = false
                    confirmedChannelId = null
                    onState(PlaybackState.Error(ch, str(R.string.err_weak_signal)))
                }

                TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> {
                    tuneInProgress.value = false
                    hasConfirmedPlayback = true
                    confirmedChannelId = ch.id
                    onState(PlaybackState.Playing(ch)) // radyo / ses-only yayin
                }

                TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_NO_CARD,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_NEED_PAIRING,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_NEED_ACTIVATION,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_LICENSE_EXPIRED,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_CARD_INVALID,
                -> {
                    tuneInProgress.value = false
                    onState(PlaybackState.Error(ch, str(R.string.err_scrambled_ca)))
                }

                TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE -> {
                    tuneInProgress.value = false
                    onState(PlaybackState.Error(ch, str(R.string.err_tuner_busy)))
                }

                else -> {
                    tuneInProgress.value = false
                    onState(
                        PlaybackState.Error(
                            ch,
                            context.getString(R.string.err_broadcast_unavailable, reason),
                        ),
                    )
                }
            }
        }
    }

    private fun confirmPlaying() {
        val ch = currentChannel ?: return
        lastVideoUnavailableReason = null
        tuneInProgress.value = false
        hasConfirmedPlayback = true
        if (confirmedChannelId != ch.id) {
            val now = SystemClock.elapsedRealtime()
            playbackConfirmedAtMs = now
            val elapsed = now - tuneStartedAtMs
            SafeLog.i(TAG, "Tune video available: channel=${ch.id}, elapsedMs=$elapsed")
        }
        confirmedChannelId = ch.id
        onState(PlaybackState.Playing(ch))
        refreshTracks()
    }

    /** Kisa yoldan yerellestirilmis metin. */
    private fun str(resId: Int): String = context.getString(resId)

    fun attach(view: TvView) {
        if (tvView === view) return
        detach()
        tvView = view
        runCatching { view.setCallback(sessionCallback) }
            .onFailure { SafeLog.w(TAG, "setCallback", it) }
        updateTimeShiftPositionCallback()
    }

    fun setTimeShiftPositionTracking(enabled: Boolean) {
        if (trackingTimeShift == enabled) return
        trackingTimeShift = enabled
        updateTimeShiftPositionCallback()
    }

    private fun updateTimeShiftPositionCallback() {
        runCatching { tvView?.setTimeShiftPositionCallback(timeShiftCallback.takeIf { trackingTimeShift }) }
            .onFailure { SafeLog.w(TAG, "setTimeShiftPositionCallback", it) }
    }

    fun detach() {
        tvView?.let { view ->
            runCatching {
                view.setCallback(null)
                view.setTimeShiftPositionCallback(null)
                view.reset()
            }.onFailure { SafeLog.w(TAG, "TvView reset", it) }
        }
        tvView = null
        currentChannel = null
        confirmedChannelId = null
        playbackConfirmedAtMs = null
        hasConfirmedPlayback = false
        tuneInProgress.value = false
        _trackState.value = TrackState()
        _timeShiftState.value = TimeShiftState()
    }

    /** Kanali ayarlar. Tekrar denemeler [waitForConfirmation] tarafindan yonetilir. */
    fun tune(channel: Channel, requestedAtMs: Long) {
        val view = tvView
        if (view == null) {
            onState(PlaybackState.Error(channel, str(R.string.err_player_not_ready)))
            return
        }
        if (currentChannel == channel && (confirmedChannelId == channel.id || tuneInProgress.value)) return
        val inputChanged = currentChannel?.inputId != channel.inputId
        if (inputChanged) hasConfirmedPlayback = false
        currentChannel = channel
        confirmedChannelId = null
        tuneStartedAtMs = SystemClock.elapsedRealtime()
        val previousWatchMs = playbackConfirmedAtMs?.let { tuneStartedAtMs - it }
        playbackConfirmedAtMs = null
        lastVideoUnavailableReason = null
        _timeShiftState.value = TimeShiftState(available = !inputChanged && _timeShiftState.value.available)
        SafeLog.i(TAG, "Tune sent: channel=${channel.id}, appWaitMs=${tuneStartedAtMs - requestedAtMs}, previousWatchMs=$previousWatchMs")
        initialTuneRetried = false
        weakSignalDuringTune = false
        tuneInProgress.value = true
        onState(PlaybackState.Tuning(channel))
        runCatching {
            view.tune(channel.inputId, TvContract.buildChannelUri(channel.id))
        }.onFailure { err ->
            tuneInProgress.value = false
            SafeLog.e(TAG, "tune basarisiz", err)
            onState(PlaybackState.Error(channel, str(R.string.err_tune_failed)))
        }
    }

    /** Wait for the active request's callback before sending the latest selection. */
    suspend fun awaitTuneSlot(channel: Channel) {
        if (!tuneInProgress.value || currentChannel == channel) return
        val remaining = MAX_TUNE_IN_FLIGHT_MS - (SystemClock.elapsedRealtime() - tuneStartedAtMs)
        if (remaining > 0) {
            withTimeoutOrNull(remaining) { tuneInProgress.first { !it } }
        }
    }

    suspend fun waitForConfirmation(channel: Channel) {
        if (!tuneInProgress.value || currentChannel != channel) return
        if (!hasConfirmedPlayback && !initialTuneRetried) {
            val retryIn = INITIAL_RETRY_DELAY_MS - (SystemClock.elapsedRealtime() - tuneStartedAtMs)
            val finished = withTimeoutOrNull(retryIn) {
                tuneInProgress.first { !it }
            }
            if (finished != null || !tuneInProgress.value || currentChannel != channel) return
            val view = tvView ?: return
            initialTuneRetried = true
            runCatching { view.tune(channel.inputId, TvContract.buildChannelUri(channel.id)) }
                .onFailure { SafeLog.w(TAG, "ilk tune yeniden deneme", it) }
        }
        val remaining = TUNE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - tuneStartedAtMs)
        val finished = withTimeoutOrNull(remaining) { tuneInProgress.first { !it } }
        if (finished == null && tuneInProgress.value && currentChannel == channel) {
            tuneInProgress.value = false
            val error = if (weakSignalDuringTune) R.string.err_weak_signal else R.string.err_tune_timeout
            onState(PlaybackState.Error(channel, str(error)))
        }
    }

    fun stop() {
        runCatching { tvView?.reset() }.onFailure { SafeLog.w(TAG, "reset", it) }
        currentChannel = null
        confirmedChannelId = null
        playbackConfirmedAtMs = null
        hasConfirmedPlayback = false
        tuneInProgress.value = false
        _trackState.value = TrackState()
        _timeShiftState.value = TimeShiftState()
        onState(PlaybackState.Idle)
    }

    fun playRecording(inputId: String, recordedProgramUri: android.net.Uri) {
        runCatching {
            tvView?.timeShiftPlay(inputId, recordedProgramUri)
                ?: error("TvView hazır değil")
        }.onFailure { SafeLog.w(TAG, "Kayit oynatilamadi", it) }
    }

    fun pauseTimeShift() {
        runCatching { tvView?.timeShiftPause() }.onFailure { SafeLog.w(TAG, "timeShiftPause", it) }
        _timeShiftState.value = _timeShiftState.value.copy(paused = true)
    }

    fun resumeTimeShift() {
        runCatching { tvView?.timeShiftResume() }.onFailure { SafeLog.w(TAG, "timeShiftResume", it) }
        _timeShiftState.value = _timeShiftState.value.copy(paused = false)
    }

    fun seekTimeShiftBy(deltaMillis: Long) {
        val state = _timeShiftState.value
        if (!state.available) return
        val target = (state.currentPositionMs + deltaMillis).coerceIn(state.startPositionMs, System.currentTimeMillis())
        runCatching { tvView?.timeShiftSeekTo(target) }.onFailure { SafeLog.w(TAG, "timeShiftSeekTo", it) }
    }

    /** Uzaktan kumanda tuslarini TvView'a iletir (OK, sayi tuslari, renkli tuslar...). */
    fun dispatchKey(event: KeyEvent): Boolean {
        val view = tvView ?: return false
        return runCatching { view.dispatchKeyEvent(event) }.getOrDefault(false)
    }

    /** Requests the same DTVKit full-page teletext service used by SEI Live TV. */
    fun toggleTeletext(): Boolean {
        val view = tvView ?: return false
        val requested = runCatching {
            view.sendAppPrivateCommand(ACTION_FULLPAGE_TELETEXT, null)
            true
        }.onFailure { SafeLog.d(TAG, "Tam sayfa teletext komutu desteklenmiyor: ${it.message}") }
            .getOrDefault(false)
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TV_TELETEXT, 0)
        val up = KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TV_TELETEXT, 0)
        val handledDown = dispatchKey(down)
        val handledUp = dispatchKey(up)
        return requested || handledDown || handledUp
    }

    /** Ses izi secer (`id = null` → otomatik secim). */
    fun selectAudioTrack(trackId: String?) = selectTrack(TvTrackInfo.TYPE_AUDIO, trackId)

    /**
     * Altyazi izi secer; `null` secildiginde altyazi/kapal (altyazi) da
     * kapatilir (`setCaptionEnabled(false)`), secildiginde acilir.
     */
    fun selectSubtitleTrack(trackId: String?) {
        runCatching { tvView?.setCaptionEnabled(trackId != null) }
            .onFailure { SafeLog.w(TAG, "setCaptionEnabled", it) }
        selectTrack(TvTrackInfo.TYPE_SUBTITLE, trackId)
    }

    private fun selectTrack(type: Int, trackId: String?) {
        val view = tvView ?: return
        runCatching {
            view.selectTrack(type, trackId)
            // secim bildirimi gelmeden panelin guncellenmesi icin aninda yansit
            _trackState.value = _trackState.value.let { state ->
                when (type) {
                    TvTrackInfo.TYPE_AUDIO -> state.copy(selectedAudioId = trackId)
                    TvTrackInfo.TYPE_SUBTITLE -> state.copy(selectedSubtitleId = trackId)
                    else -> state
                }
            }
        }.onFailure { SafeLog.w(TAG, "selectTrack", it) }
    }

    /** Izleri TvView'dan okur; tune sonrasi ve iz degisiminde cagrilir. */
    fun refreshTracks() {
        val view = tvView ?: return
        runCatching {
            _trackState.value = TrackState(
                audio = view.getTracks(TvTrackInfo.TYPE_AUDIO)
                    .orEmpty()
                    .mapNotNull { it.toOption() },
                subtitles = view.getTracks(TvTrackInfo.TYPE_SUBTITLE)
                    .orEmpty()
                    .mapNotNull { it.toOption() },
                selectedAudioId = view.getSelectedTrack(TvTrackInfo.TYPE_AUDIO),
                selectedSubtitleId = view.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE),
            )
        }.onFailure { SafeLog.d(TAG, "refreshTracks: ${it.message}") }
    }

    private fun TvTrackInfo.toOption(): TrackOption? {
        val trackId = id?.takeIf { it.isNotBlank() } ?: return null
        return TrackOption(id = trackId, label = describeTrack(this))
    }

    private fun describeTrack(track: TvTrackInfo): String {
        val language = languageLabel(track.language)
        val channels = if (track.type == TvTrackInfo.TYPE_AUDIO) track.audioChannelCount else 0
        return if (channels > 1) "$language · ${channels}ch" else language
    }

    /**
     * Dil kodunu (ISO 639) goruntulenebilir ada cevirir.
     * Diller **kendi yazimlariyla** gosterilir ("Türkçe", "English") —
     * boylece TR/EN arayuz dilinden bagimsiz dogru ad yazilir.
     */
    private fun languageLabel(code: String?): String {
        val raw = code?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty() || raw == "und" || raw == "mul" || raw == "zxx") {
            return str(R.string.track_unknown_language)
        }
        val primary = raw.split('-', '_').first()
        LANGUAGE_ENDONYMS[primary]?.let { return it }
        return primary.uppercase()
    }

    companion object {
        private const val TAG = "TunerPlayer"
        private const val TUNE_SIGNAL_GRACE_MS = 4_000L
        private const val ACTION_FULLPAGE_TELETEXT = "com.google.android.tv.dtvinput.play.mode.FULLPAGE_TELETEXT"

        /** Yaygin dil kodlari (ISO 639-1/2) → dilin kendi yazimi. */
        private val LANGUAGE_ENDONYMS = mapOf(
            "tr" to "Türkçe", "tur" to "Türkçe",
            "en" to "English", "eng" to "English",
            "de" to "Deutsch", "ger" to "Deutsch", "deu" to "Deutsch",
            "fr" to "Français", "fre" to "Français", "fra" to "Français",
            "es" to "Español", "spa" to "Español",
            "it" to "Italiano", "ita" to "Italiano",
            "ru" to "Русский", "rus" to "Русский",
            "ar" to "العربية", "ara" to "العربية",
            "nl" to "Nederlands", "dut" to "Nederlands", "nld" to "Nederlands",
            "pt" to "Português", "por" to "Português",
            "pl" to "Polski", "pol" to "Polski",
            "sv" to "Svenska", "swe" to "Svenska",
            "no" to "Norsk", "nor" to "Norsk",
            "da" to "Dansk", "dan" to "Dansk",
            "fi" to "Suomi", "fin" to "Suomi",
            "el" to "Ελληνικά", "gre" to "Ελληνικά", "ell" to "Ελληνικά",
            "he" to "עברית", "heb" to "עברית",
            "hi" to "हिन्दी", "hin" to "हिन्दी",
            "ja" to "日本語", "jpn" to "日本語",
            "ko" to "한국어", "kor" to "한국어",
            "zh" to "中文", "chi" to "中文", "zho" to "中文",
            "cs" to "Čeština", "cze" to "Čeština", "ces" to "Čeština",
            "hu" to "Magyar", "hun" to "Magyar",
            "ro" to "Română", "rum" to "Română", "ron" to "Română",
            "bg" to "Български", "bul" to "Български",
            "sr" to "Српски", "srp" to "Српски",
            "hr" to "Hrvatski", "hrv" to "Hrvatski",
            "sk" to "Slovenčina", "slo" to "Slovenčina", "slk" to "Slovenčina",
            "sl" to "Slovenščina", "slv" to "Slovenščina",
            "lt" to "Lietuvių", "lit" to "Lietuvių",
            "lv" to "Latviešu", "lav" to "Latviešu",
            "et" to "Eesti", "est" to "Eesti",
            "uk" to "Українська", "ukr" to "Українська",
            "kk" to "Қазақша", "kaz" to "Қазақша",
            "az" to "Azərbaycanca", "aze" to "Azərbaycanca",
            "ka" to "ქართული", "geo" to "ქართული", "kat" to "ქართული",
            "hy" to "Հայերեն", "arm" to "Հայերեն", "hye" to "Հայերեն",
            "be" to "Беларуская", "bel" to "Беларуская",
            "ms" to "Bahasa Melayu", "may" to "Bahasa Melayu", "msa" to "Bahasa Melayu",
            "id" to "Bahasa Indonesia", "ind" to "Bahasa Indonesia",
            "th" to "ไทย", "tha" to "ไทย",
            "vi" to "Tiếng Việt", "vie" to "Tiếng Việt",
            "ta" to "தமிழ்", "tam" to "தமிழ்",
            "bn" to "বাংলা", "ben" to "বাংলা",
            "ur" to "اردو", "urd" to "اردو",
            "fa" to "فارسی", "per" to "فارسی", "fas" to "فارسی",
            "sw" to "Kiswahili", "swa" to "Kiswahili",
            "zu" to "isiZulu", "zul" to "isiZulu",
            "af" to "Afrikaans", "afr" to "Afrikaans",
            "ga" to "Gaeilge", "gle" to "Gaeilge",
            "ca" to "Català", "cat" to "Català",
            "eu" to "Euskara", "baq" to "Euskara", "eus" to "Euskara",
            "gl" to "Galego", "glg" to "Galego",
        )
    }
}

/**
 * `TvView`'u Compose agacina yerlestirir ve [channel] ile senkronize eder.
 *
 * Not: `TvView` `SurfaceView` tabanlidir; Compose bindirmeleri (info bari,
 * kanal listesi) TvView'un **ustunde** ayri bir katman olarak cizilmelidir.
 */
@Composable
fun TunerTvView(
    channel: Channel?,
    aspectMode: AspectMode = AspectMode.FULL_16_9,
    modifier: Modifier = Modifier,
    onState: (PlaybackState) -> Unit,
    onControllerReady: (TunerPlayerController) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latestChannel by rememberUpdatedState(channel)
    val stateCallback by rememberUpdatedState(onState)
    val readyCallback by rememberUpdatedState(onControllerReady)
    val controller = remember { TunerPlayerController(context, { s -> stateCallback(s) }) }

    LaunchedEffect(controller) { readyCallback(controller) }

    DisposableEffect(controller) {
        onDispose { controller.detach() }
    }

    // Activity arka plana alınınca TIF oturumunu resetle; aksi halde bazı
    // DTVKit sürümleri ses çıkışını uygulama kapandıktan sonra da sürdürür.
    // Geri gelindiğinde seçili kanalı yeniden aç.
    DisposableEffect(controller, lifecycleOwner) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    controller.stop()
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    scope.launch {
                        val requestedAtMs = SystemClock.elapsedRealtime()
                        val target = latestChannel ?: return@launch
                        val view = controller.tvView ?: return@launch
                        view.awaitAttachedToWindow()
                        controller.tune(target, requestedAtMs)
                        controller.waitForConfirmation(target)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Goruntu modu: TvView'un pencere boyutuyla uygulanir (bkz. AspectMode).
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = when (aspectMode) {
                AspectMode.FULL_16_9 -> Modifier.fillMaxSize()
                AspectMode.PILLAR_4_3 -> Modifier.fillMaxHeight().aspectRatio(3f / 4f)
            },
            factory = { ctx ->
                SecureTvView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // D-pad odağı canlı görüntü yüzeyine geçerse Compose
                    // kanal tuşlarını alamıyor. Video yüzeyi etkileşimli değil;
                    // kumanda odağı LiveTvScreen'de kalmalı.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    controller.attach(this)
                }
            },
            update = { view ->
                controller.attach(view)
            },
        )
    }

    LaunchedEffect(channel?.id, channel?.inputId) {
        val requestedAtMs = SystemClock.elapsedRealtime()
        val target = channel ?: run {
            controller.stop()
            return@LaunchedEffect
        }
        val view = controller.tvView ?: return@LaunchedEffect
        // Coalesce rapid selections to one newest target before touching TIF.
        delay(CHANNEL_TUNE_DEBOUNCE_MS)
        view.awaitAttachedToWindow()
        // OEM Live TV waits for its current TV session/tune state. Keep one
        // request in flight and let newer selections replace the pending one.
        controller.awaitTuneSlot(target)
        if (!currentCoroutineContext().isActive) return@LaunchedEffect
        controller.tune(target, requestedAtMs)
        controller.waitForConfirmation(target)
    }
}

private const val CHANNEL_TUNE_DEBOUNCE_MS = 60L
private const val MAX_TUNE_IN_FLIGHT_MS = 1_600L
private const val INITIAL_RETRY_DELAY_MS = 1_800L
private const val TUNE_TIMEOUT_MS = 4_300L

private suspend fun TvView.awaitAttachedToWindow() {
    if (isAttachedToWindow) return
    suspendCancellableCoroutine { continuation ->
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        addOnAttachStateChangeListener(listener)
        if (isAttachedToWindow) {
            removeOnAttachStateChangeListener(listener)
            continuation.resume(Unit)
        }
        continuation.invokeOnCancellation { removeOnAttachStateChangeListener(listener) }
    }
}

/** Homatics OEM CustomerTvView ile ayni anda, her eklenen yuvayi guvenli acar. */
private class SecureTvView(context: Context) : TvView(context) {
    // Match the Homatics OEM Live TV LayerTvView behavior exactly.
    override fun hasWindowFocus(): Boolean = true

    override fun onViewAdded(child: android.view.View) {
        if (child is SurfaceView) child.setSecure(true)
        super.onViewAdded(child)
    }
}
