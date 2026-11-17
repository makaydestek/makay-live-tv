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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.makay.livetv.recording.RecordedProgram
import java.text.DateFormat
import java.util.Date

/** Live TV style quick menu opened with LEFT from live playback/channel list. */
@Composable
fun MainMenuScreen(
    recordings: List<RecordedProgram>,
    onSelect: (AppScreen) -> Unit,
    onOpenSetup: () -> Unit,
    onPlayRecording: (RecordedProgram) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(false) }
    val menuTabFocus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color(0x55040A12)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.88f)
                .width(310.dp)
                .padding(start = 20.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xD91A2940))
                .border(1.dp, Color(0x668DB8E8), RoundedCornerShape(22.dp))
                .padding(14.dp),
        ) {
            Text(
                text = stringResource(R.string.menu_search_hint),
                color = Color(0xFFAEC4DE),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Color(0x332C4564)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                MenuTab(stringResource(R.string.menu_tab), selected = !selectedTab, Modifier.weight(1f), menuTabFocus) { selectedTab = false }
                MenuTab(stringResource(R.string.recordings_tab), selected = selectedTab, Modifier.weight(1f)) { selectedTab = true }
            }
            Spacer(Modifier.height(10.dp))
            if (!selectedTab) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    item { MenuEntry("▦", stringResource(R.string.epg_title), onClick = { onSelect(AppScreen.Epg) }) }
                    item { MenuEntry("⌁", stringResource(R.string.menu_setup), onClick = onOpenSetup) }
                    item { MenuEntry("☷", stringResource(R.string.playback_options), onClick = { onSelect(AppScreen.Options) }) }
                    item { MenuEntry("✎", stringResource(R.string.menu_channel_edit), onClick = { onSelect(AppScreen.ChannelList) }) }
                    item { MenuEntry("▣", stringResource(R.string.menu_pvr_list), onClick = { onSelect(AppScreen.Recordings) }) }
                    item { MenuEntry("◴", stringResource(R.string.timers_title), onClick = { onSelect(AppScreen.Timers) }) }
                    item { MenuEntry("⚙", stringResource(R.string.settings), onClick = { onSelect(AppScreen.Settings) }) }
                }
            } else if (recordings.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.recordings_empty), color = Color(0xFFC5D2E2), fontSize = 14.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recordings, key = { it.id }) { item ->
                        MenuEntry("▶", item.title.ifBlank { stringResource(R.string.recordings_title) }, onClick = { onPlayRecording(item) },
                            subtitle = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.startUtcMillis)))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTab(label: String, selected: Boolean, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = rememberTvInteractionSource()
    Box(
        modifier.then(focusRequester?.let(Modifier::focusRequester) ?: Modifier).clip(RoundedCornerShape(12.dp))
            .background(if (selected || focused) Color(0xFFE6F0FC) else Color(0x332B4462))
            .border(1.dp, if (selected || focused) Color.White else Color(0x448DA9C6), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(interaction, onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (focusRequester != null) AutoFocus(focusRequester, shouldRequest = true, key = "main-menu-tab")
        Text(label, color = if (selected || focused) Color(0xFF182538) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MenuEntry(symbol: String, label: String, onClick: () -> Unit, subtitle: String? = null) {
    var focused by remember { mutableStateOf(false) }
    val interaction = rememberTvInteractionSource()
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color(0xDDE7F0FB) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(interaction, onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = if (focused) Color(0xFF263B54) else Color(0xFFE4EFFB), fontSize = 18.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (focused) Color(0xFF17263A) else Color.White, fontSize = 14.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = if (focused) Color(0xFF53677E) else Color(0xFFB0C1D5), fontSize = 11.sp)
        }
    }
}
