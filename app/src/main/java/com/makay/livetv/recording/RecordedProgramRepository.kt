package com.makay.livetv.recording

import android.content.Context
import android.media.tv.TvContract
import android.net.Uri
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordedProgram(
    val id: Long,
    val title: String,
    val inputId: String,
    val channelId: Long,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val dataUri: Uri?,
) {
    val uri: Uri get() = TvContract.buildRecordedProgramUri(id)
}

/** TvProvider'daki kayitli programlar tablosunu okur. */
class RecordedProgramRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun load(): List<RecordedProgram> = withContext(Dispatchers.IO) {
        val columns = arrayOf(
            TvContract.RecordedPrograms._ID,
            TvContract.RecordedPrograms.COLUMN_TITLE,
            TvContract.RecordedPrograms.COLUMN_INPUT_ID,
            TvContract.RecordedPrograms.COLUMN_CHANNEL_ID,
            TvContract.RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI,
        )
        runCatching {
            resolver.query(
                TvContract.RecordedPrograms.CONTENT_URI,
                columns,
                null,
                null,
                "${TvContract.RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS} DESC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms._ID)
                    val title = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_TITLE)
                    val input = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_INPUT_ID)
                    val channel = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_CHANNEL_ID)
                    val start = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS)
                    val end = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS)
                    val data = cursor.getColumnIndexOrThrow(TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI)
                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(id)
                        add(
                            RecordedProgram(
                                id = rowId,
                                title = cursor.getString(title).orEmpty(),
                                inputId = cursor.getString(input).orEmpty(),
                                channelId = cursor.getLong(channel),
                                startUtcMillis = cursor.getLong(start),
                                endUtcMillis = cursor.getLong(end),
                                dataUri = cursor.getString(data)?.let(Uri::parse),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { SafeLog.w(TAG, "Kayit listesi okunamadi", it) }.getOrDefault(emptyList())
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(TvContract.buildRecordedProgramUri(id), null, null) > 0 }
            .onFailure { SafeLog.w(TAG, "Kayit silinemedi: $id", it) }
            .getOrDefault(false)
    }

    companion object { private const val TAG = "RecordedPrograms" }
}
