package com.makay.livetv.ui

import android.media.tv.TvInputManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.epg.ChannelEpg
import com.makay.livetv.player.AspectMode
import com.makay.livetv.player.PlaybackState
import com.makay.livetv.player.TunerPlayerController
import com.makay.livetv.player.TunerTvView
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.OverlayScrim
import com.makay.livetv.ui.theme.OverlaySurface
import com.makay.livetv.util.TimeFormat

/**
 * Canli TV ekrani: TvView yayini + uzerine binen bilgi bari + hata/durum
 * bindirmeleri.
 *
 * Uzaktan kumanda eslemesi (TV aliskanligina uygun):
 *  - DPAD Yukari/Asagi : kanal degistir
 *  - OK                : bilgi bari / kanal listesi
 *  - SOL               : uygulama menusu
 *  - GUIDE (EPG tusu)  : TV Rehberi
 *  - Rakamlar          : numaraya gore kanal
 *  - Renkli tuslar     : (varsa) yayin sagi tarafindan kullanilir
 */
@Composable
fun LiveTvScreen(
    activeChannel: Channel?,
    channelsLoading: Boolean,
    epg: ChannelEpg?,
    playbackState: PlaybackState,
    bannerVisible: Boolean,
    signalStrength: Int?,
    numberBuffer: String,
    isRecording: Boolean,
    focusToken: Int,
    liveRemoteEnabled: Boolean,
    aspectMode: AspectMode,
    onControllerReady: (TunerPlayerController) -> Unit,
    onState: (PlaybackState) -> Unit,
    onOpenChannelList: () -> Unit,
    onOpenMainMenu: () -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTunerSetup: () -> Boolean,
    onOpenOptions: () -> Unit,
    onToggleBanner: () -> Unit,
    onZap: (Int) -> Unit,
    onKeyDigit: (String) -> Unit,
    onCommitNumber: () -> Boolean,
    onForwardKey: (KeyEvent) -> Boolean,
) {
    val rootFocus = androidx.compose.runtime.remember { FocusRequester() }

    // Odak istegi: ilk acilista ve her bindirme kapandiktan sonra tekrarlanir
    // (bindirme ekrani odagi kendi uzerine aldigindan canli TV'ye geri donulur).
    LaunchedEffect(focusToken, activeChannel?.id, liveRemoteEnabled) {
        if (liveRemoteEnabled) rootFocus.requestFocusSafely()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            // TvView/native AndroidView, odak aldiginda ustteki onKeyEvent'e
            // ulasmayan D-pad tuslari olabiliyor. Kanal tuslarini odakli alt
            // gorunumlerden once yakala.
            .onPreviewKeyEvent { event ->
                if (!liveRemoteEnabled) return@onPreviewKeyEvent false
                handleKey(
                    event = event,
                    onZap = onZap,
                    onOpenChannelList = onOpenChannelList,
                    onOpenMainMenu = onOpenMainMenu,
                    onOpenEpg = onOpenEpg,
                    onToggleBanner = onToggleBanner,
                    onKeyDigit = onKeyDigit,
                    onCommitNumber = onCommitNumber,
                    onOpenOptions = onOpenOptions,
                    onForwardKey = onForwardKey,
                )
            },
    ) {
        if (activeChannel != null) {
            TunerTvView(
                channel = activeChannel,
                aspectMode = aspectMode,
                modifier = Modifier.fillMaxSize(),
                onState = onState,
                onControllerReady = onControllerReady,
            )
        } else {
            NoChannelPlaceholder(
                isLoading = channelsLoading,
                onScan = { onOpenTunerSetup() },
            )
        }

        // Durum bindirmesi (kanal ayarlaniyor / hata)
        PlaybackStatusOverlay(state = playbackState)

        // Bilgi bari
        AnimatedVisibility(
            visible = bannerVisible && activeChannel != null,
            enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            activeChannel?.let {
                val channelSignal = signalStrength.takeIf {
                    (playbackState as? PlaybackState.Playing)?.channel?.id == activeChannel.id
                }
                InfoBanner(channel = it, epg = epg, signalStrength = channelSignal)
            }
        }

        // Kanal numarasi tamponu (orn. "12_") — sure dolunca tune edilir
        if (numberBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xDD0B1220))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                    Text(
                    text = numberBuffer,
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Kayit suruyorsa kalici rozet (banttan bagimsiz — kullanici her
        // zaman kayit oldugunu gorebilsin).
        if (isRecording) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xDD8B1A1A))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.timer_recording_active),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Alt ipucu satiri, ust kanal bilgi bandiyla birlikte gorunup gizlenir.
        AnimatedVisibility(
            visible = bannerVisible && activeChannel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KeyHint("▲▼", stringResource(R.string.hint_up_down))
                KeyHint("OK", stringResource(R.string.channel_list))
                KeyHint("◀", stringResource(R.string.hint_menu))
                KeyHint("0-9", stringResource(R.string.channel_number))
                KeyHint("⚙", stringResource(R.string.playback_options))
            }
        }
    }
}

/**
 * Uzaktan kumanda tus eslemesi.
 *
 * Iki katman:
 *  1. Uygulamanin kendi tuslari (yon, sayi, OK, MENU, GUIDE, INFO) →
 *     burada ele alinir ve `true` doner.
 *  2. Yayina (TIF oturumuna) ait tuslar (renkli tuslar, teletext, altyazi,
 *     ses, medya/zoom tuslari) → [onForwardKey] ile `TvView`'a aktarilir.
 *     Boylece teletext ve renkli tuslar cihazin DTV girdisine ulasir.
 *
 * Islenmeyen ve iletilmeyen tuslar `false` doner → Compose olayi iptal eder;
 * GERI tusu boylece [androidx.activity.compose.BackHandler]'e ulasir.
 */
private fun handleKey(
    event: KeyEvent,
    onZap: (Int) -> Unit,
    onOpenChannelList: () -> Unit,
    onOpenMainMenu: () -> Unit,
    onOpenEpg: () -> Unit,
    onToggleBanner: () -> Unit,
    onKeyDigit: (String) -> Unit,
    onCommitNumber: () -> Boolean,
    onOpenOptions: () -> Unit,
    onForwardKey: (KeyEvent) -> Boolean,
): Boolean {
    val isDown = event.type == KeyEventType.KeyDown
    val isUp = event.type == KeyEventType.KeyUp
    if (!isDown && !isUp) return false
    val initialDown = isDown && event.nativeKeyEvent.repeatCount == 0

    // Kanal +/- tuslari: hem yukari/asagi hem de kanal tuslari calisir.
    when (event.nativeKeyEvent.keyCode) {
        NativeKeyCodes.CHANNEL_UP -> {
            if (initialDown) onZap(+1)
            return true
        }
        NativeKeyCodes.CHANNEL_DOWN -> {
            if (initialDown) onZap(-1)
            return true
        }
        // SETTINGS tusu: oynatma secenekleri paneli (goruntu/ses/altyazi).
        NativeKeyCodes.SETTINGS -> {
            if (isDown) onOpenOptions()
            return true
        }
    }

    // Basili tutma tekrar tetiklemesin; KeyUp yutulur (islem KeyDown'da yapildi).
    if (isUp) {
        return when {
            event.key in handledKeys -> true
            event.nativeKeyEvent.keyCode in forwardedKeyCodes -> onForwardKey(event)
            else -> false
        }
    }

    return when (event.key) {
        Key.DirectionUp -> { if (initialDown) onZap(+1); true }
        Key.DirectionDown -> { if (initialDown) onZap(-1); true }
        Key.DirectionLeft -> { onOpenMainMenu(); true }
        Key.DirectionRight -> false
        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
            // Numara yaziliyken OK hemen tune eder; degilse kanal listesini acar.
            if (onCommitNumber()) true else { onOpenChannelList(); true }
        }
        Key.Menu -> { onOpenMainMenu(); true }
        Key.Info -> { onToggleBanner(); true }
        Key.Guide -> { onOpenEpg(); true }
        Key.Zero, Key.NumPad0 -> { onKeyDigit("0"); true }
        Key.One, Key.NumPad1 -> { onKeyDigit("1"); true }
        Key.Two, Key.NumPad2 -> { onKeyDigit("2"); true }
        Key.Three, Key.NumPad3 -> { onKeyDigit("3"); true }
        Key.Four, Key.NumPad4 -> { onKeyDigit("4"); true }
        Key.Five, Key.NumPad5 -> { onKeyDigit("5"); true }
        Key.Six, Key.NumPad6 -> { onKeyDigit("6"); true }
        Key.Seven, Key.NumPad7 -> { onKeyDigit("7"); true }
        Key.Eight, Key.NumPad8 -> { onKeyDigit("8"); true }
        Key.Nine, Key.NumPad9 -> { onKeyDigit("9"); true }
        else -> if (event.nativeKeyEvent.keyCode in forwardedKeyCodes) onForwardKey(event) else false
    }
}

/** KeyUp'in yutulacagi tuslar (KeyDown'da islem yapildi). */
private val handledKeys = setOf(
    Key.DirectionUp, Key.DirectionDown,
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter,
    Key.Menu, Key.Info, Key.Guide,
    Key.Zero, Key.NumPad0, Key.One, Key.NumPad1, Key.Two, Key.NumPad2,
    Key.Three, Key.NumPad3, Key.Four, Key.NumPad4, Key.Five, Key.NumPad5,
    Key.Six, Key.NumPad6, Key.Seven, Key.NumPad7, Key.Eight, Key.NumPad8,
    Key.Nine, Key.NumPad9,
)

/**
 * TIF girdisine (TvView oturumuna) iletilen tus kodlari:
 * teletext, altyazi, ses, renkli tuslar, kanal +/- ve medya tuslari.
 * Compose `Key` sabitleri TV tuslarinin tamamini kapsamadigi icin dogrudan
 * `android.view.KeyEvent` kodlari kullanilir.
 */
private object NativeKeyCodes {
    const val CHANNEL_UP = android.view.KeyEvent.KEYCODE_CHANNEL_UP
    const val CHANNEL_DOWN = android.view.KeyEvent.KEYCODE_CHANNEL_DOWN
    const val SETTINGS = android.view.KeyEvent.KEYCODE_SETTINGS
}

private val forwardedKeyCodes = setOf(
    android.view.KeyEvent.KEYCODE_TV,
    android.view.KeyEvent.KEYCODE_TV_TELETEXT,
    android.view.KeyEvent.KEYCODE_CAPTIONS,
    android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
    android.view.KeyEvent.KEYCODE_PROG_RED,
    android.view.KeyEvent.KEYCODE_PROG_GREEN,
    android.view.KeyEvent.KEYCODE_PROG_YELLOW,
    android.view.KeyEvent.KEYCODE_PROG_BLUE,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    android.view.KeyEvent.KEYCODE_MEDIA_STOP,
    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    android.view.KeyEvent.KEYCODE_MEDIA_RECORD,
)

@Composable
private fun InfoBanner(
    channel: Channel,
    epg: ChannelEpg?,
    signalStrength: Int?,
) {
    val now = System.currentTimeMillis()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(Color(0xE62A3444), Color(0xE61B2432))))
                .border(1.dp, Color(0x557E9AB8), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MakayPrimary)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(channel.displayNumber, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    channel.displayName,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ChannelTags(channel) }
            }
            epg?.current?.let { program ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        program.title,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFE8EDF5),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${TimeFormat.range(program.startTimeUtcMillis, program.endTimeUtcMillis)} ┬À ${
                            TimeFormat.remaining(program.endTimeUtcMillis, now)
                        }",
                        color = Color(0xFFADB8C7),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { program.progressAt(now) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = MakayPrimary,
                    trackColor = Color(0x33FFFFFF),
                )
            }
            epg?.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.epg_next_program, next.title),
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF8FA6C8),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = TimeFormat.time(next.startTimeUtcMillis),
                        color = Color(0xFF8FA6C8),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val signalLabel = when (signalStrength) {
                    TvInputManager.SIGNAL_STRENGTH_STRONG -> stringResource(R.string.signal_level_strong)
                    TvInputManager.SIGNAL_STRENGTH_WEAK -> stringResource(R.string.signal_level_weak)
                    TvInputManager.SIGNAL_STRENGTH_LOST -> stringResource(R.string.signal_level_lost)
                    else -> stringResource(R.string.signal_level_unknown)
                }
                SignalMetric(
                    label = stringResource(R.string.signal_label),
                    value = signalLabel,
                    strength = signalStrength,
                )
                SignalMetric(
                    label = stringResource(R.string.signal_quality_label),
                    value = stringResource(R.string.signal_quality_unavailable),
                    strength = null,
                )
            }
        }
    }
}

@Composable
private fun SignalMetric(label: String, value: String, strength: Int?) {
    val segmentCount = when (strength) {
        TvInputManager.SIGNAL_STRENGTH_STRONG -> 3
        TvInputManager.SIGNAL_STRENGTH_WEAK -> 1
        else -> 0
    }
    val levelColor = when (strength) {
        TvInputManager.SIGNAL_STRENGTH_STRONG -> Color(0xFF4ED18A)
        TvInputManager.SIGNAL_STRENGTH_WEAK -> Color(0xFFFFC66D)
        TvInputManager.SIGNAL_STRENGTH_LOST -> Color(0xFFFF7777)
        else -> Color(0xFF8595A8)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF8595A8), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (strength == null) {
                Text("ÔÇö", color = Color(0xFF8595A8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .size(width = 5.dp, height = 10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (index < segmentCount) levelColor else Color(0xFF4B5868)),
                        )
                    }
                }
            }
            Text(
                value,
                color = if (strength == TvInputManager.SIGNAL_STRENGTH_WEAK || strength == TvInputManager.SIGNAL_STRENGTH_LOST) levelColor else Color(0xFFB8C9DC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelTags(channel: Channel) {
    val tags = buildList {
        if (channel.type.isPhysicalTuner) add(channel.type.label)
        if (channel.isHd) add(stringResource(R.string.tag_hd))
        if (channel.isRadio) add(stringResource(R.string.tag_radio))
        if (channel.isFavorite) add("Ôİà " + stringResource(R.string.favorites).uppercase())
    }
    tags.forEach { tag ->
        Text(
            text = tag,
            color = if (tag.startsWith("Ôİà")) Color(0xFFFFD54F) else Color(0xFF8FA6C8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlaybackStatusOverlay(state: PlaybackState) {
    when (state) {
        // Like the OEM Live TV app, keep the existing video surface unobscured
        // while the tuner changes channel. Show only actionable playback errors.
        is PlaybackState.Tuning -> Unit

        is PlaybackState.Error -> Box(
            modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.channel.displayName,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(text = state.reason, color = Color(0xFFB8C2CF), fontSize = 16.sp)
            }
        }

        else -> Unit
    }
}

@Composable
private fun NoChannelPlaceholder(isLoading: Boolean, onScan: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isLoading) {
                CircularProgressIndicator(color = MakayPrimary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.loading),
                    color = Color.White,
                    fontSize = 20.sp,
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.channel_not_found),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.live_no_channels_body),
                color = Color(0xFFB8C2CF),
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onScan) {
                Text(stringResource(R.string.action_scan_channels))
            }
        }
    }
}

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(OverlaySurface)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(text = key, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = Color(0xFF9AA4B2), fontSize = 11.sp)
    }
}
