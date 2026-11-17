package com.makay.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.Program
import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.epg.ChannelEpg
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.OverlaySurface
import com.makay.livetv.util.TimeFormat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TV Rehberi (EPG): **5 gunluk tam izgara** (gun x saat) + program detayi.
 *
 * Referans uygulama `EpgView` adinda ozel bir `ViewGroup` ile kanvasta
 * ciziyordu. Burada ayni deneyim Compose ile kuruldu:
 *
 *  - **Gun secici**: 5 gun + "Simdi" cipi; izgaranin ustunde durur.
 *  - **Izgara**: her kanal bir satir, programlar zaman ekseninde mutlak
 *    konumla cizilir; tum satirlar **ayni yatay kaydirma** durumunu paylasir
 *    (boylece cetvel ve satirlar hizali kalir).
 *  - **Imlec modeli**: kumanda DPAD ile imlec gezer (Compose odak aramasina
 *    birakilmayi; TV'de daha ongorulebilir). OK tusu program detayini acar.
 *  - **Simdi cizgisi**: bugun gununde canli yayin konumunu gosterir.
 */
@Composable
fun EpgScreen(
    channels: List<Channel>,
    epg: Map<Long, ChannelEpg>,
    activeChannelId: Long?,
    onSelectChannel: (Channel) -> Unit,
    onLoadRange: suspend (List<Channel>, Long, Long) -> Map<Long, List<Program>>,
    scheduledProgramTimers: Map<Long, TimerEntry>,
    recordableInputIds: Set<String>,
    onScheduleReminder: (Channel, Program, Boolean) -> Unit,
    onScheduleRecording: (Channel, Program, Boolean) -> Unit,
    onCancelReminder: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val gridFocus = remember { FocusRequester() }

    // "Simdi" bilgisi: dakikada bir tazelenir (simdi cizgisi + canli vurgu).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    // 0..4 = gun, 5 = "Simdi" cipi (eylem).
    var chipIndex by remember { mutableIntStateOf(0) }
    // -1 = gun secici satiri, 0.. = kanal satiri.
    var cursorRow by remember { mutableIntStateOf(-1) }
    var cursorProgram by remember { mutableIntStateOf(0) }
    var dayPrograms by remember { mutableStateOf<Map<Long, List<Program>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<EpgSelection?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var jumpToken by remember { mutableIntStateOf(0) }
    var viewportPx by remember { mutableIntStateOf(0) }

    val dayOffset = chipIndex.coerceAtMost(DAYS_SHOWN - 1)
    val dayStart = remember(dayOffset) { TimeFormat.startOfDayUtc(dayOffset) }
    val dayEnd = remember(dayOffset) { TimeFormat.startOfDayUtc(dayOffset + 1) }

    val pxPerMinute = with(density) { MINUTE_WIDTH.toPx() }
    val dayWidthPx = MINUTES_PER_DAY * pxPerMinute

    /** Izgara satirinin programlari: once gunluk veri, bugunse "simdi/sonraki" yedeği. */
    fun programsOf(channel: Channel): List<Program> =
        dayPrograms[channel.id]?.takeIf { it.isNotEmpty() }
            ?: if (dayOffset == 0) {
                listOfNotNull(epg[channel.id]?.current, epg[channel.id]?.next)
                    .sortedBy { it.startTimeUtcMillis }
            } else {
                emptyList()
            }

    val filteredChannels = remember(channels, dayPrograms, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) channels else channels.filter { channel ->
            channel.displayName.lowercase().contains(query) ||
                channel.displayNumber.lowercase().contains(query) ||
                dayPrograms[channel.id].orEmpty().any { program ->
                    program.title.lowercase().contains(query) ||
                        program.genre?.lowercase()?.contains(query) == true
                }
        }
    }

    fun anchorDefault(): Long = if (dayOffset == 0) now else dayStart + DEFAULT_DAY_HOUR * 3_600_000L

    fun currentProgram(): Program? =
        if (cursorRow in filteredChannels.indices) programsOf(filteredChannels[cursorRow]).getOrNull(cursorProgram) else null

    /** Verilen anahtara en yakin programin indeksini bulur (satir degisiminde hizalama). */
    fun indexForTime(list: List<Program>, time: Long): Int {
        if (list.isEmpty()) return 0
        val containing = list.indexOfFirst { time >= it.startTimeUtcMillis && time < it.endTimeUtcMillis }
        if (containing >= 0) return containing
        val after = list.indexOfFirst { it.startTimeUtcMillis > time }
        return if (after < 0) list.lastIndex else after
    }

    fun anchorForMove(): Long = currentProgram()?.startTimeUtcMillis ?: anchorDefault()

    fun moveUp() {
        if (cursorRow > 0) {
            val anchor = anchorForMove()
            cursorRow--
            cursorProgram = indexForTime(programsOf(filteredChannels[cursorRow]), anchor)
        } else if (cursorRow == 0) {
            cursorRow = -1
        }
    }

    fun moveDown() {
        if (cursorRow >= filteredChannels.lastIndex) return
        val anchor = if (cursorRow == -1) anchorDefault() else anchorForMove()
        cursorRow++
        cursorProgram = indexForTime(programsOf(filteredChannels[cursorRow]), anchor)
    }

    fun moveLeft() {
        if (cursorRow == -1) chipIndex = (chipIndex - 1).coerceAtLeast(0)
        else if (cursorProgram > 0) cursorProgram--
    }

    fun moveRight() {
        if (cursorRow == -1) {
            chipIndex = (chipIndex + 1).coerceAtMost(DAYS_SHOWN)
        } else {
            val list = programsOf(filteredChannels[cursorRow])
            if (cursorProgram < list.lastIndex) cursorProgram++
        }
    }

    fun confirm() {
        if (cursorRow == -1) {
            // "Simdi": bugune don ve simdi konumuna kaydir.
            chipIndex = 0
            jumpToken++
        } else {
            val channel = filteredChannels[cursorRow]
            currentProgram()?.let { detail = EpgSelection(channel, it) }
        }
    }

    // Gun degisince programlari yukle ve imleci hizala.
    LaunchedEffect(dayOffset, channels.size) {
        loading = true
        dayPrograms = onLoadRange(channels, dayStart, dayEnd)
        loading = false
        if (cursorRow in filteredChannels.indices) {
            cursorProgram = indexForTime(programsOf(filteredChannels[cursorRow]), anchorDefault())
        }
    }

    // Imlec / gun degisince yatay kaydirmayi sagla.
    LaunchedEffect(cursorRow, cursorProgram, dayOffset, viewportPx, jumpToken) {
        if (viewportPx <= 0) return@LaunchedEffect
        val pad = with(density) { SCROLL_PAD.toPx() }
        val maxScroll = (dayWidthPx - viewportPx).coerceAtLeast(0f)
        val current = hScroll.value.toFloat()

        val x: Float
        var width = 0f
        if (cursorRow in filteredChannels.indices) {
            val program = programsOf(filteredChannels[cursorRow]).getOrNull(cursorProgram)
            x = if (program != null) {
                val start = program.startTimeUtcMillis.coerceAtLeast(dayStart)
                width = ((program.endTimeUtcMillis - program.startTimeUtcMillis) / 60_000f) * pxPerMinute
                ((start - dayStart) / 60_000f) * pxPerMinute
            } else {
                defaultX(dayOffset, now, dayStart, pxPerMinute, viewportPx)
            }
        } else {
            x = defaultX(dayOffset, now, dayStart, pxPerMinute, viewportPx)
        }

        val target = when {
            x < current + pad -> x - pad
            x + width > current + viewportPx -> x + width - viewportPx
            else -> current
        }.coerceIn(0f, maxScroll)

        if (abs(target - current) > 1f) hScroll.animateScrollTo(target.roundToInt())
    }

    // Odak her zaman izgarada (dialog kapaninca geri doner).
    LaunchedEffect(detail == null) {
        if (detail == null) runCatching { gridFocus.requestFocus() }
    }

    // Kanal sayisi azalirsak imleci gecerli tut.
    LaunchedEffect(channels.size) {
        if (cursorRow >= filteredChannels.size) cursorRow = filteredChannels.size - 1
        if (cursorRow < -1) cursorRow = -1
    }

    LaunchedEffect(searchQuery, filteredChannels.size) {
        if (cursorRow >= filteredChannels.size) cursorRow = filteredChannels.lastIndex
        if (filteredChannels.isEmpty()) cursorRow = -1
        cursorProgram = 0
    }

    // Imlec satirdan disari cikarsa listeyi kaydir.
    LaunchedEffect(cursorRow) {
        if (cursorRow >= 0) runCatching { listState.animateScrollToItem(cursorRow) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF000000))))
            .padding(horizontal = 48.dp, vertical = 28.dp)
            .focusRequester(gridFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { moveUp(); true }
                    Key.DirectionDown -> { moveDown(); true }
                    Key.DirectionLeft -> { moveLeft(); true }
                    Key.DirectionRight -> { moveRight(); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { confirm(); true }
                    else -> false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.epg_title),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = TimeFormat.fullDate(dayStart),
                color = Color(0xFF8FA6C8),
                fontSize = 15.sp,
            )
            if (loading) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.epg_loading),
                    color = Color(0xFF6B7684),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (cursorRow == -1) R.string.epg_hint_days else R.string.epg_hint_grid),
            color = Color(0xFF6B7684),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; cursorRow = -1 },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.epg_search_hint)) },
            modifier = Modifier.fillMaxWidth(0.55f),
        )
        Spacer(Modifier.height(8.dp))

        if (filteredChannels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.channel_not_found),
                    color = Color(0xFF9AA4B2),
                )
            }
            return
        }

        // ---- Gun secici ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(DAYS_SHOWN + 1) { index ->
                DayChip(
                    label = dayChipLabel(index),
                    selected = chipIndex == index,
                    active = cursorRow == -1 && chipIndex == index,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Saat cetveli (izgarayla ayni yatay kaydirma) ----
        TimeRuler(hScroll = hScroll, dayStart = dayStart)
        Spacer(Modifier.height(6.dp))

        // ---- Izgara ----
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(filteredChannels, key = { _, ch -> ch.id }) { index, channel ->
                EpgGridRow(
                    channel = channel,
                    programs = programsOf(channel),
                    isActive = channel.id == activeChannelId,
                    isCursorRow = cursorRow == index,
                    cursorProgramIndex = cursorProgram,
                    dayStart = dayStart,
                    dayOffset = dayOffset,
                    now = now,
                    hScroll = hScroll,
                    dayWidthPx = dayWidthPx,
                    pxPerMinute = pxPerMinute,
                    onViewportMeasured = { viewportPx = it },
                )
            }
        }
    }

    detail?.let { selection ->
        ProgramDetailDialog(
            selection = selection,
            timer = scheduledProgramTimers[selection.program.id],
            canRecord = selection.channel.inputId in recordableInputIds,
            onWatch = {
                detail = null
                onSelectChannel(selection.channel)
            },
            onScheduleReminder = { weekly -> onScheduleReminder(selection.channel, selection.program, weekly) },
            onScheduleRecording = { weekly -> onScheduleRecording(selection.channel, selection.program, weekly) },
            onCancelReminder = { timer -> onCancelReminder(timer.id) },
            onClose = { detail = null },
        )
    }
}

/** Imlecin gun icinde varsayilan yatay konumu (bugun -> simdi, digerleri -> 06:00). */
private fun defaultX(
    dayOffset: Int,
    now: Long,
    dayStart: Long,
    pxPerMinute: Float,
    viewportPx: Int,
): Float =
    if (dayOffset == 0) {
        (((now - dayStart) / 60_000f) * pxPerMinute) - viewportPx / 3f
    } else {
        DEFAULT_DAY_HOUR * 60f * pxPerMinute
    }

/** Gun cipi etiketi: Bugün / Yarın / "Çar 12 Kas" / Şimdi. */
@Composable
private fun dayChipLabel(index: Int): String = when {
    index >= DAYS_SHOWN -> stringResource(R.string.epg_now)
    index == 0 -> stringResource(R.string.epg_today)
    index == 1 -> stringResource(R.string.epg_tomorrow)
    else -> TimeFormat.relativeDayLabel(TimeFormat.startOfDayUtc(index))
}

@Composable
private fun DayChip(label: String, selected: Boolean, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MakayPrimary else Color(0xFF1A1F29))
            .border(
                2.dp,
                if (active) Color.White else Color.Transparent,
                RoundedCornerShape(20.dp),
            )
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

/** Saatlik cetvel: 24 saat, izgarayla ayni kaydirma durumunu paylasir. */
@Composable
private fun TimeRuler(hScroll: androidx.compose.foundation.ScrollState, dayStart: Long) {
    Row(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Spacer(Modifier.width(CHANNEL_COLUMN_WIDTH))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(hScroll),
        ) {
            Box(modifier = Modifier.width(DAY_WIDTH).fillMaxHeight()) {
                repeat(HOURS_PER_DAY) { hour ->
                    Box(
                        modifier = Modifier
                            .offset(x = HOUR_WIDTH * hour.toFloat(), y = 0.dp)
                            .fillMaxHeight()
                            .width(HOUR_WIDTH)
                            .padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = TimeFormat.time(dayStart + hour * 3_600_000L),
                            color = Color(0xFF8FA6C8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** Tek bir kanal satiri: sabit kanal hucresi + zaman ekseninde program seridi. */
@Composable
private fun EpgGridRow(
    channel: Channel,
    programs: List<Program>,
    isActive: Boolean,
    isCursorRow: Boolean,
    cursorProgramIndex: Int,
    dayStart: Long,
    dayOffset: Int,
    now: Long,
    hScroll: androidx.compose.foundation.ScrollState,
    dayWidthPx: Float,
    pxPerMinute: Float,
    onViewportMeasured: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCursorRow) OverlaySurface else Color(0x6614171D))
            .border(
                2.dp,
                if (isCursorRow) MakayPrimary else if (isActive) Color(0x663F8CFF) else Color.Transparent,
                RoundedCornerShape(10.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kanal sutunu
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.displayNumber,
                color = if (isActive) MakayPrimary else Color(0xFF8FA6C8),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
            )
            Text(
                text = channel.displayName,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Zaman ekseninde programlar
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(hScroll)
                .onSizeChanged { onViewportMeasured(it.width) },
        ) {
            Box(modifier = Modifier.width(DAY_WIDTH).fillMaxHeight()) {
                // Simdi cizgisi (yalnizca bugun)
                if (dayOffset == 0) {
                    val xNow = (((now - dayStart) / 60_000f) * pxPerMinute)
                        .coerceIn(0f, dayWidthPx)
                        .roundToInt()
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(xNow, 0) }
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MakayPrimary.copy(alpha = 0.85f)),
                    )
                }

                if (programs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = stringResource(
                                if (dayOffset == 0) R.string.epg_no_data
                                else R.string.epg_no_programs_in_day,
                            ),
                            color = Color(0xFF6B7684),
                            fontSize = 13.sp,
                        )
                    }
                }

                programs.forEachIndexed { index, program ->
                    val start = program.startTimeUtcMillis.coerceAtLeast(dayStart)
                    val x = (((start - dayStart) / 60_000f) * pxPerMinute).roundToInt()
                    val minutes = ((program.endTimeUtcMillis - program.startTimeUtcMillis) / 60_000f)
                    val width = (MINUTE_WIDTH * minutes).coerceAtLeast(MIN_BLOCK_WIDTH)
                    val isCursor = isCursorRow && cursorProgramIndex == index
                    val isLive = dayOffset == 0 && program.isLiveAt(now)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x, 0) }
                            .width(width)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isCursor -> MakayPrimary
                                    isLive -> Color(0xFF16305F)
                                    else -> Color(0xFF1A1F29)
                                },
                            )
                            .border(
                                2.dp,
                                if (isCursor) Color.White else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(
                                text = program.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (isCursor || isLive) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.epg_range,
                                    TimeFormat.time(program.startTimeUtcMillis),
                                    TimeFormat.time(program.endTimeUtcMillis),
                                ),
                                color = if (isCursor) Color.White else Color(0xFF8FA6C8),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Program detay penceresi: ozet, tur, sure, yas siniri + "Izle". */
@Composable
private fun ProgramDetailDialog(
    selection: EpgSelection,
    timer: TimerEntry?,
    canRecord: Boolean,
    onWatch: () -> Unit,
    onScheduleReminder: (Boolean) -> Unit,
    onScheduleRecording: (Boolean) -> Unit,
    onCancelReminder: (TimerEntry) -> Unit,
    onClose: () -> Unit,
) {
    val program = selection.program
    var repeatWeekly by remember(program.id) { mutableStateOf(false) }
    val description = program.longDescription?.takeIf { it.isNotBlank() }
        ?: program.shortDescription?.takeIf { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF141922),
        title = {
            Text(
                text = program.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                Text(
                    text = "${selection.channel.displayNumber}  ${selection.channel.displayName}",
                    color = MakayPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.epg_range,
                        TimeFormat.time(program.startTimeUtcMillis),
                        TimeFormat.time(program.endTimeUtcMillis),
                    ) + "  ·  " + TimeFormat.duration(program.durationMillis),
                    color = Color(0xFFB8C2CF),
                    fontSize = 14.sp,
                )
                program.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(text = it, color = Color(0xFFB8C2CF), fontSize = 14.sp, maxLines = 2)
                }
                val meta = buildList {
                    program.genre?.takeIf { it.isNotBlank() }?.let {
                        add(stringResource(R.string.epg_detail_genre) + ": " + it)
                    }
                    program.rating?.takeIf { it.isNotBlank() }?.let {
                        add(stringResource(R.string.epg_detail_rating) + ": " + it)
                    }
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = meta.joinToString("  ·  "),
                        color = Color(0xFF8FA6C8),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.epg_detail_description),
                    color = Color(0xFF6B7684),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description ?: stringResource(R.string.epg_no_description),
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
                if (timer == null && program.endTimeUtcMillis > System.currentTimeMillis()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = repeatWeekly,
                            onCheckedChange = { repeatWeekly = it },
                        )
                        Text(stringResource(R.string.timer_repeat_weekly), color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onWatch) {
                    Text(stringResource(R.string.epg_watch), color = MakayPrimary, fontWeight = FontWeight.Bold)
                }
                if (timer == null && program.endTimeUtcMillis > System.currentTimeMillis()) {
                    TextButton(onClick = { onScheduleReminder(repeatWeekly) }) {
                        Text(stringResource(R.string.timer_remind_action), color = MakayPrimary)
                    }
                    if (canRecord) TextButton(onClick = { onScheduleRecording(repeatWeekly) }) {
                        Text(stringResource(R.string.timer_record_schedule), color = MakayPrimary)
                    }
                }
            }
        },
        dismissButton = {
            Row {
                if (timer != null) {
                    TextButton(onClick = { onCancelReminder(timer) }) {
                        Text(stringResource(R.string.timer_remind_cancel), color = Color(0xFFFF8A80))
                    }
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.epg_close), color = Color(0xFF9AA4B2))
                }
            }
        },
    )
}

/** Detay penceresinin verisi. */
private data class EpgSelection(val channel: Channel, val program: Program)

private val CHANNEL_COLUMN_WIDTH = 260.dp
private val ROW_HEIGHT = 76.dp
private val RULER_HEIGHT = 26.dp
/** 1 dakikalik yayin = 4 dp (saat = 240 dp, gun = 5760 dp). */
private val MINUTE_WIDTH = 4.dp
private val HOUR_WIDTH = MINUTE_WIDTH * 60f
private val DAY_WIDTH = MINUTE_WIDTH * (MINUTES_PER_DAY.toFloat())
private val MIN_BLOCK_WIDTH = 48.dp
private val SCROLL_PAD = 24.dp
private const val MINUTES_PER_DAY = 24 * 60
private const val HOURS_PER_DAY = 24
private const val DAYS_SHOWN = 5
/** Gun disi saat varsayilari (izgaranin ilk gorunur saati). */
private const val DEFAULT_DAY_HOUR = 6
