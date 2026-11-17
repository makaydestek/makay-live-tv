package com.makay.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.util.TimeFormat

@Composable
fun TimersScreen(
    timers: List<TimerEntry>,
    onCancel: (Long) -> Unit,
) {
    val scheduled = timers.filter { it.status == TimerStatus.SCHEDULED }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color.Black)))
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.timers_title),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.timers_count, scheduled.size),
            color = Color(0xFF8FA6C8),
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(18.dp))
        if (scheduled.isEmpty()) {
            Text(stringResource(R.string.timers_empty), color = Color(0xFF9AA4B2), fontSize = 16.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scheduled, key = { it.id }) { entry ->
                    TimerRow(entry = entry, onCancel = { onCancel(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun TimerRow(entry: TimerEntry, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x6614171D))
            .border(1.dp, Color(0x333F8CFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.repeatWeekly) "${entry.programTitle} · ${stringResource(R.string.timer_weekly_badge)}" else entry.programTitle,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${entry.channelNumber}  ${entry.channelName}  ·  ${TimeFormat.day(entry.startUtcMillis)} ${TimeFormat.time(entry.startUtcMillis)}–${TimeFormat.time(entry.endUtcMillis)}",
                color = Color(0xFF9AA4B2),
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.timer_remind_cancel)) }
    }
}
