package com.makay.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.ChannelFilter
import com.makay.livetv.domain.model.ChannelSort
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.epg.ChannelEpg
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.OverlaySurface
import com.makay.livetv.util.TimeFormat

/**
 * Canli TV goruntusu uzerine gelen sol kanal paneli.
 *
 * TV kumandasi icin hizli, dar ve yarı saydam kanal secim paneli.
 */
@Composable
fun ChannelListScreen(
    channels: List<Channel>,
    editing: Boolean,
    activeChannelId: Long?,
    epg: Map<Long, ChannelEpg>,
    showLogos: Boolean,
    availability: TunerAvailability,
    onToggleFavorite: (Channel) -> Unit,
    onRename: (Channel, String?) -> Unit,
    onSetHidden: (Channel, Boolean) -> Unit,
    onSetDeleted: (Channel, Boolean) -> Unit,
    onSetSkipped: (Channel, Boolean) -> Unit,
    onMoveChannel: (Channel, Int) -> Unit,
    onBeginMove: () -> Unit,
    onSetLocked: (Channel, Boolean) -> Boolean,
    onVerifyPin: (String) -> Boolean,
    onSelect: (Channel) -> Unit,
    onFocused: (Channel) -> Unit,
    onScanChannels: () -> Unit,
    filter: ChannelFilter,
    sort: ChannelSort,
    query: String,
    onCycleFilter: () -> Unit,
    onCycleSort: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenMainMenu: () -> Unit,
    onDismiss: () -> Unit,
    autoHideTimeoutMs: Long,
    onPanelActivity: () -> Unit,
) {
    val listState = rememberLazyListState()
    val quickActionsFocus = remember { FocusRequester() }
    val channelIds = channels.map { it.id }.toSet()
    val channelFocusRequesters = remember(channelIds) {
        channelIds.associateWith { FocusRequester() }
    }
    var returnToChannelRequest by remember { mutableIntStateOf(0) }
    var panelActivity by remember { mutableIntStateOf(0) }
    var movingChannelId by remember(editing) { mutableStateOf<Long?>(null) }
    var dialogChannelId by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = movingChannelId != null) { movingChannelId = null }
    // Kanal paneli ve bilgi bandi benzer bir sure sonra kapanir. Kullanici
    // gezinirken her odak hareketi sayaci yeniler; hareketsizlikte yayin
    // goruntusune kendiliginden donulur.
    LaunchedEffect(panelActivity, activeChannelId, editing, movingChannelId, dialogChannelId, autoHideTimeoutMs) {
        if (editing || movingChannelId != null || dialogChannelId != null) return@LaunchedEffect
        kotlinx.coroutines.delay(autoHideTimeoutMs)
        onDismiss()
    }
    val activeIndex = remember(channels, activeChannelId) {
        channels.indexOfFirst { it.id == activeChannelId }
    }
    LaunchedEffect(returnToChannelRequest, channels.isEmpty(), editing) {
        if (channels.isNotEmpty()) {
            val targetIndex = activeIndex.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(targetIndex)
            val targetId = channels[targetIndex].id
            withFrameNanos { }
            channelFocusRequesters[targetId]?.requestFocusSafely()
        }
    }
    val returnToChannelList = {
        returnToChannelRequest++
        panelActivity++
        onPanelActivity()
    }
    val openQuickActions: () -> Unit = {
        panelActivity++
        onPanelActivity()
        runCatching { quickActionsFocus.requestFocus() }
        Unit
    }

    LaunchedEffect(movingChannelId, channels) {
        val id = movingChannelId ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.id == id }
        if (index < 0) {
            movingChannelId = null
        } else {
            withFrameNanos { }
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
                listState.scrollToItem(index)
                withFrameNanos { }
            }
            channelFocusRequesters[id]?.requestFocusSafely()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x220A1018)),
        contentAlignment = Alignment.CenterStart,
    ) {
      Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(Brush.verticalGradient(listOf(Color(0xB9273448), Color(0xB9182233))))
            .padding(horizontal = 12.dp, vertical = 14.dp),
      ) {
        // ---- Baslik ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (editing) R.string.menu_channel_edit else R.string.channels),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.channel_count,
                    channels.size,
                    channels.size,
                ),
                color = Color(0xFF8FA6C8),
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                UiText.filterLabel(filter),
                selected = true,
                focusRequester = quickActionsFocus,
                onClick = { onCycleFilter(); panelActivity++; onPanelActivity() },
                onNavigateToChannels = returnToChannelList,
                onNavigateToMenu = onOpenMainMenu,
            )
            FilterChip(
                UiText.sortLabel(sort),
                selected = false,
                onClick = { onCycleSort(); panelActivity++; onPanelActivity() },
                onNavigateToChannels = returnToChannelList,
                onNavigateToMenu = onOpenMainMenu,
            )
        }
        Text(
            text = stringResource(when {
                movingChannelId != null -> R.string.channel_move_mode_hint
                editing -> R.string.channel_edit_keys_hint
                else -> R.string.channel_quick_access_hint
            }),
            color = Color(0xFF9AAFC6),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp, start = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it); panelActivity++; onPanelActivity() },
            singleLine = true,
            label = { Text(stringResource(R.string.search_channel_hint), maxLines = 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        if (channels.isEmpty()) {
            EmptyChannelState(availability = availability, onScanChannels = onScanChannels)
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        epg = epg[channel.id],
                        isActive = channel.id == activeChannelId,
                        showLogo = showLogos,
                        editing = editing,
                        moving = movingChannelId == channel.id,
                        focusRequester = channelFocusRequesters.getValue(channel.id),
                        compact = true,
                        onClick = { onSelect(channel) },
                        onFocused = { panelActivity++; onPanelActivity(); onFocused(channel) },
                        onStartMoving = { movingChannelId = channel.id; onBeginMove() },
                        onFinishMoving = { movingChannelId = null },
                        onDialogVisibilityChanged = { visible ->
                            if (visible) dialogChannelId = channel.id
                            else if (dialogChannelId == channel.id) dialogChannelId = null
                        },
                        onNavigateToActions = openQuickActions,
                        onNavigateToMenu = onOpenMainMenu,
                        onLongClickFavorite = { onToggleFavorite(channel) },
                        onRename = onRename,
                        onSetHidden = onSetHidden,
                        onSetDeleted = onSetDeleted,
                        onSetSkipped = onSetSkipped,
                        onMoveChannel = onMoveChannel,
                        onSetLocked = onSetLocked,
                        onVerifyPin = onVerifyPin,
                    )
                }
      }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    epg: ChannelEpg?,
    isActive: Boolean,
    showLogo: Boolean,
    editing: Boolean,
    moving: Boolean,
    focusRequester: FocusRequester,
    compact: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onStartMoving: () -> Unit,
    onFinishMoving: () -> Unit,
    onDialogVisibilityChanged: (Boolean) -> Unit,
    onNavigateToActions: () -> Unit,
    onNavigateToMenu: () -> Unit,
    onLongClickFavorite: () -> Unit,
    onRename: (Channel, String?) -> Unit,
    onSetHidden: (Channel, Boolean) -> Unit,
    onSetDeleted: (Channel, Boolean) -> Unit,
    onSetSkipped: (Channel, Boolean) -> Unit,
    onMoveChannel: (Channel, Int) -> Unit,
    onSetLocked: (Channel, Boolean) -> Boolean,
    onVerifyPin: (String) -> Boolean,
) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    var showActions by remember(channel.id) { mutableStateOf(false) }
    var showRename by remember(channel.id) { mutableStateOf(false) }
    var customName by remember(channel.id, channel.displayName) { mutableStateOf(channel.displayName) }
    var showUnlock by remember(channel.id) { mutableStateOf(false) }
    var pin by remember(channel.id) { mutableStateOf("") }
    var pinFailed by remember(channel.id) { mutableStateOf(false) }
    var pinUnlockChannel by remember(channel.id) { mutableStateOf(false) }
    var pinRequiredForLock by remember(channel.id) { mutableStateOf(false) }
    val okPress = remember(channel.id) { RemoteOkPress() }
    val openActions = { pinRequiredForLock = false; showActions = true }
    val click = {
        when {
            moving -> onFinishMoving()
            editing -> openActions()
            channel.isLocallyLocked -> { pinUnlockChannel = false; showUnlock = true }
            else -> onClick()
        }
    }
    val longClick = {
        if (editing && !moving) onStartMoving()
        else if (!moving) openActions()
    }
    val dialogOpen = showActions || showRename || showUnlock
    DisposableEffect(dialogOpen) {
        if (dialogOpen) onDialogVisibilityChanged(true)
        onDispose { if (dialogOpen) onDialogVisibilityChanged(false) }
    }
    val borderColor = if (focused) MakayPrimary else if (isActive) Color(0x663F8CFF) else Color.Transparent

    // Acilista aktif kanal satiri odagi alir; DPAD ile gezinme buradan baslar.

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
            .background(
                when {
                    compact && (focused || isActive) -> Color(0xCCDCE6F2)
                    focused -> OverlaySurface
                    compact -> Color(0x221B2534)
                    else -> Color(0x6614171D)
                },
            )
            .border(
                if (moving || !compact) 2.dp else 1.dp,
                if (moving) MakayPrimary else if (compact && (focused || isActive)) Color(0xBBFFFFFF) else borderColor,
                RoundedCornerShape(if (compact) 8.dp else 12.dp),
            )
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) okPress.reset()
                if (it.isFocused && !channel.isLocallyLocked) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (okPress.handle(event.nativeKeyEvent, click, longClick)) return@onPreviewKeyEvent true
                if (moving) {
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                onMoveChannel(channel, if (event.key == Key.DirectionUp) -1 else 1)
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> return@onPreviewKeyEvent true
                    }
                }
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onNavigateToMenu(); true }
                    Key.DirectionRight -> { onNavigateToActions(); true }
                    else -> false
                }
            }
            .tvClickable(
                interaction,
                onClick = click,
                onLongClick = longClick,
            )
            .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kanal numarasi
        Box(
            modifier = Modifier
                .size(width = if (compact) 40.dp else 64.dp, height = if (compact) 30.dp else 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isActive) MakayPrimary else Color(0x55262A33)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channel.displayNumber,
                color = Color.White,
                fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(if (compact) 8.dp else 14.dp))

        // Kanal logosu (ayar aciksa ve logo varsa; yoksa ilk harf)
        if (showLogo && !compact) {
            ChannelLogo(
                uri = channel.logoUri,
                fallbackLabel = channel.displayName,
                modifier = Modifier.size(width = 56.dp, height = 32.dp),
            )
            Spacer(Modifier.width(12.dp))
        }

        // Ad + EPG
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.isFavorite) {
                    Text(text = "★", color = Color(0xFFFFD54F), fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = channel.displayName,
                    color = if (compact && (focused || isActive)) Color(0xFF131A24) else Color.White,
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.isHd && !compact) {
                    Spacer(Modifier.width(8.dp))
                    Tag(stringResource(R.string.tag_hd))
                }
                if (channel.isRadio && !compact) {
                    Spacer(Modifier.width(8.dp))
                    Tag(stringResource(R.string.tag_radio))
                }
                channel.type.takeIf { it.isPhysicalTuner && !compact }?.let {
                    Spacer(Modifier.width(8.dp))
                    Tag(it.label)
                }
            }

            val program = epg?.current
            if (compact) {
                if (isActive) Text(
                    text = stringResource(R.string.live_badge),
                    color = if (focused || isActive) MakayPrimary else Color(0xFF9AA4B2),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else if (program != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.epg_time_title,
                            TimeFormat.time(program.startTimeUtcMillis),
                            program.title,
                        ),
                        color = Color(0xFF9AA4B2),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { program.progressAt(now) },
                    modifier = Modifier.fillMaxWidth(0.4f).height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = Color(0x883F8CFF),
                    trackColor = Color(0x22FFFFFF),
                )
            } else {
                Text(
                    text = epg?.next?.let {
                        stringResource(R.string.epg_next_program, it.title)
                    } ?: stringResource(R.string.epg_no_data),
                    color = Color(0xFF6B7684),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (moving) {
            Text(text = "↕", color = MakayPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else if (isActive && !compact) {
            Text(
                text = stringResource(R.string.live_badge),
                color = MakayPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (showActions) {
        val firstAction = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text(channel.displayName) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (pinRequiredForLock) Text(stringResource(R.string.parental_pin_required))
                    AutoFocus(firstAction, shouldRequest = true)
                    Row {
                        TextButton(modifier = Modifier.focusRequester(firstAction), onClick = {
                            onLongClickFavorite()
                            showActions = false
                        }) { Text(stringResource(if (channel.isFavorite) R.string.channel_remove_favorite else R.string.channel_add_favorite)) }
                        TextButton(onClick = {
                            showActions = false
                            showRename = true
                        }) { Text(stringResource(R.string.channel_rename)) }
                    }
                    Row {
                        TextButton(onClick = {
                            onSetHidden(channel, !channel.isLocallyHidden)
                            showActions = false
                        }) { Text(stringResource(if (channel.isLocallyHidden) R.string.channel_unhide else R.string.channel_hide)) }
                        TextButton(onClick = { onStartMoving(); showActions = false }) {
                            Text(stringResource(R.string.channel_move_start))
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            if (channel.isLocallyLocked) {
                                showActions = false
                                pinUnlockChannel = true
                                showUnlock = true
                            } else if (onSetLocked(channel, true)) {
                                showActions = false
                            } else {
                                pinRequiredForLock = true
                            }
                        }) {
                            Text(stringResource(if (channel.isLocallyLocked) R.string.channel_unlock else R.string.channel_lock))
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            onSetDeleted(channel, true)
                            showActions = false
                        }) {
                            Text(
                                stringResource(R.string.channel_delete),
                                color = Color(0xFFFF8A80),
                            )
                        }
                        TextButton(onClick = {
                            onSetSkipped(channel, !channel.isLocallySkipped)
                            showActions = false
                        }) {
                            Text(
                                stringResource(
                                    if (channel.isLocallySkipped) R.string.channel_unskip else R.string.channel_skip,
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActions = false }) {
                    Text(stringResource(R.string.epg_close))
                }
            },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.channel_rename_title)) },
            text = {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.channel_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = customName.trim()
                    if (name != channel.displayName) onRename(channel, name.ifEmpty { null })
                    showRename = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onRename(channel, null)
                        showRename = false
                    }) { Text(stringResource(R.string.channel_rename_reset)) }
                    TextButton(onClick = { showRename = false }) {
                        Text(stringResource(R.string.epg_close))
                    }
                }
            },
        )
    }

    if (showUnlock) {
        AlertDialog(
            onDismissRequest = { showUnlock = false; pin = ""; pinFailed = false; pinUnlockChannel = false },
            title = { Text(stringResource(R.string.parental_pin_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8); pinFailed = false },
                        singleLine = true,
                        label = { Text(stringResource(R.string.parental_pin_hint)) },
                    )
                    if (pinFailed) Text(stringResource(R.string.parental_pin_wrong), color = Color(0xFFFF8A80))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onVerifyPin(pin)) {
                        showUnlock = false
                        pin = ""
                        pinFailed = false
                        if (pinUnlockChannel) {
                            onSetLocked(channel, false)
                            pinUnlockChannel = false
                        } else onClick()
                    } else pinFailed = true
                }) { Text(stringResource(R.string.parental_unlock_watch)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnlock = false; pin = ""; pinFailed = false; pinUnlockChannel = false }) {
                    Text(stringResource(R.string.epg_close))
                }
            },
        )
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF2C3542))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text = text, color = Color(0xFF9AA4B2), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onNavigateToChannels: (() -> Unit)? = null,
    onNavigateToMenu: (() -> Unit)? = null,
) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
            .background(
                when {
                    selected -> MakayPrimary
                    focused -> OverlaySurface
                    else -> Color(0xFF1A1F29)
                }
            )
            .border(
                2.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(20.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                    event.key == Key.DirectionDown && onNavigateToChannels != null
                ) {
                    onNavigateToChannels()
                    true
                } else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 &&
                    event.key == Key.DirectionLeft && onNavigateToMenu != null
                ) {
                    onNavigateToMenu()
                    true
                } else false
            }
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyChannelState(availability: TunerAvailability, onScanChannels: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (availability) {
            is TunerAvailability.NotSupported -> {
                Text(
                    stringResource(R.string.no_tuner_title),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_tuner_body),
                    color = Color(0xFF9AA4B2),
                    fontSize = 15.sp,
                )
            }
            is TunerAvailability.NeedsScan -> {
                Text(
                    stringResource(R.string.no_channels_title),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.no_channels_body),
                    color = Color(0xFF9AA4B2),
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(20.dp))
                FilterChip(
                    label = stringResource(R.string.action_scan_channels),
                    selected = true,
                    onClick = onScanChannels,
                )
            }
            else -> {
                Text(
                    stringResource(R.string.channel_not_found),
                    color = Color.White, fontSize = 22.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.empty_filter_hint),
                    color = Color(0xFF9AA4B2),
                    fontSize = 15.sp,
                )
            }
        }
    }
}
