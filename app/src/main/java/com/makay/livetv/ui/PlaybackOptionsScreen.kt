package com.makay.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.player.AspectMode
import com.makay.livetv.player.TrackState
import com.makay.livetv.player.TimeShiftState
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.OverlaySurface

/**
 * Oynatma secenekleri paneli (sag tarafta, yayinin uzerinde).
 *
 * Uzaktan kumandanin **SETTINGS** tusuyla acilir. Icerik:
 *  - Goruntu modu (16:9 tam ekran / 4:3 sutunlu)
 *  - Ses izi secimi (TvView izleri)
 *  - Altyazi izi secimi + kapatma
 *  - Kapat
 *
 * Tum liste tek sutun halindedir; odaklanan satir otomatik gorunur alana
 * kaydirilir (TV'de dokunma yoktur).
 */
@Composable
fun PlaybackOptionsScreen(
    trackState: TrackState,
    timeShiftState: TimeShiftState,
    aspectMode: AspectMode,
    onAspectChange: (AspectMode) -> Unit,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onToggleTimeShift: () -> Unit,
    onSeekTimeShift: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }

    val items = remember(trackState, aspectMode) {
        buildList<OptionItem> {
            add(OptionItem.Header(HeaderKind.ASPECT))
            AspectMode.entries.forEach { mode ->
                add(
                    OptionItem.Action(
                        label = null,
                        labelRes = mode.labelRes,
                        selected = aspectMode == mode,
                        onClick = { onAspectChange(mode) },
                    ),
                )
            }
            add(OptionItem.Header(HeaderKind.AUDIO))
            if (trackState.audio.isEmpty()) {
                add(OptionItem.Hint(R.string.track_none))
            } else {
                trackState.audio.forEach { track ->
                    add(
                        OptionItem.Action(
                            label = track.label,
                            labelRes = null,
                            selected = track.id == trackState.selectedAudioId,
                            onClick = { onSelectAudio(track.id) },
                        ),
                    )
                }
            }
            add(OptionItem.Header(HeaderKind.SUBTITLE))
            add(
                OptionItem.Action(
                    label = null,
                    labelRes = R.string.track_off,
                    selected = trackState.selectedSubtitleId == null,
                    onClick = { onSelectSubtitle(null) },
                ),
            )
            if (trackState.subtitles.isEmpty()) {
                add(OptionItem.Hint(R.string.track_none))
            } else {
                trackState.subtitles.forEach { track ->
                    add(
                        OptionItem.Action(
                            label = track.label,
                            labelRes = null,
                            selected = track.id == trackState.selectedSubtitleId,
                            onClick = { onSelectSubtitle(track.id) },
                        ),
                    )
                }
            }
            add(OptionItem.Header(HeaderKind.TIMESHIFT))
            if (!timeShiftState.available) {
                add(OptionItem.Hint(R.string.timeshift_unsupported))
            } else {
                add(OptionItem.Action(
                    label = null,
                    labelRes = if (timeShiftState.paused) R.string.timeshift_resume else R.string.timeshift_pause,
                    selected = timeShiftState.paused,
                    onClick = onToggleTimeShift,
                ))
                add(OptionItem.Action(null, R.string.timeshift_back, false) { onSeekTimeShift(-30_000L) })
                add(OptionItem.Action(null, R.string.timeshift_forward, false) { onSeekTimeShift(30_000L) })
            }
            add(OptionItem.Footer(onClick = onClose))
        }
    }

    val firstActionIndex = items.indexOfFirst { it is OptionItem.Action }

    // Odaklanan satir gorunur alana kayar.
    LaunchedEffect(focusedIndex) {
        runCatching { listState.animateScrollToItem(focusedIndex.coerceIn(0, items.lastIndex)) }
    }

    // Ekran acilirken ilk ogeye odaklan.
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PANEL_WIDTH)
                .background(Color(0xF20B1220))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text(
                text = stringResource(R.string.playback_options),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { index, _ -> index }) { index, item ->
                    when (item) {
                        is OptionItem.Header -> SectionHeader(headerLabel(item.header))

                        is OptionItem.Hint -> Text(
                            text = stringResource(item.textRes),
                            color = Color(0xFF6B7684),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        is OptionItem.Action -> OptionRow(
                            label = item.label ?: stringResource(item.labelRes!!),
                            selected = item.selected,
                            isFirstFocusable = index == firstActionIndex,
                            onFocus = { focusedIndex = index },
                            onClick = item.onClick,
                        )

                        is OptionItem.Footer -> OptionRow(
                            label = stringResource(item.labelRes),
                            selected = false,
                            isFirstFocusable = false,
                            onFocus = { focusedIndex = index },
                            onClick = item.onClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun headerLabel(kind: HeaderKind): String = stringResource(
    when (kind) {
        HeaderKind.ASPECT -> R.string.options_aspect
        HeaderKind.AUDIO -> R.string.options_audio_track
        HeaderKind.SUBTITLE -> R.string.options_subtitle_track
        HeaderKind.TIMESHIFT -> R.string.options_timeshift
    },
)

@Composable
private fun SectionHeader(label: String) {
    Column {
        Spacer(Modifier.height(10.dp))
        Text(
            text = label.uppercase(),
            color = MakayPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF232936)),
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    isFirstFocusable: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isFirstFocusable) {
        // Ilk oge odak istegini dener; agac hazir degilse yutulur.
        if (isFirstFocusable) runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) OverlaySurface else Color(0x14FFFFFF))
            .border(
                2.dp,
                if (focused) MakayPrimary else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(if (isFirstFocusable) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                color = MakayPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Panel satiri. */
private sealed interface OptionItem {
    data class Header(val header: HeaderKind) : OptionItem
    data class Hint(val textRes: Int) : OptionItem
    data class Action(
        val label: String?,
        val labelRes: Int?,
        val selected: Boolean,
        val onClick: () -> Unit,
    ) : OptionItem

    data class Footer(val labelRes: Int = R.string.epg_close, val onClick: () -> Unit) : OptionItem
}

private enum class HeaderKind { ASPECT, AUDIO, SUBTITLE, TIMESHIFT }

private val PANEL_WIDTH = 460.dp
