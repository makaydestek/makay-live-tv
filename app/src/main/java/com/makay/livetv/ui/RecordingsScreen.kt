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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
import com.makay.livetv.recording.RecordedProgram
import com.makay.livetv.util.TimeFormat
import android.os.StatFs
import android.text.format.Formatter

@Composable
fun RecordingsScreen(
    recordings: List<RecordedProgram>,
    onPlay: (RecordedProgram) -> Unit,
    onDelete: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var sortByTitle by remember { mutableStateOf(false) }
    val filteredRecordings = remember(recordings, query, sortByTitle) {
        val q = query.trim().lowercase()
        recordings.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.channelId.toString().contains(q) }
            .let { list -> if (sortByTitle) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.startUtcMillis } }
    }
    val availableBytes = remember {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color.Black)))
            .padding(horizontal = 48.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recordings_title), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { sortByTitle = !sortByTitle }) {
                Text(stringResource(if (sortByTitle) R.string.recordings_sort_date else R.string.recordings_sort_title))
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
        }
        Text(
            stringResource(R.string.recordings_storage_info, Formatter.formatShortFileSize(context, availableBytes)),
            color = Color(0xFF8FA6C8),
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.recordings_search_hint)) },
            modifier = Modifier.fillMaxWidth(0.55f),
        )
        Spacer(Modifier.height(14.dp))
        if (filteredRecordings.isEmpty()) {
            Text(stringResource(R.string.recordings_empty), color = Color(0xFF9AA4B2), fontSize = 16.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRecordings, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x6614171D))
                            .border(1.dp, Color(0x333F8CFF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title.ifBlank { stringResource(R.string.recording_untitled) }, color = Color.White, fontSize = 17.sp)
                            Text(
                                "${TimeFormat.day(item.startUtcMillis)} ${TimeFormat.time(item.startUtcMillis)} · ${TimeFormat.duration(item.endUtcMillis - item.startUtcMillis)}",
                                color = Color(0xFF9AA4B2),
                                fontSize = 13.sp,
                            )
                        }
                        TextButton(onClick = { onPlay(item) }) { Text(stringResource(R.string.epg_watch)) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onDelete(item.id) }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
}
