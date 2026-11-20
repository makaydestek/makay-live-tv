package com.makay.livetv.tuner

import android.content.ContentResolver
import android.net.Uri
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DtvKitSignal(val strengthPercent: Int?, val qualityPercent: Int?)

internal object DtvKitSignalReader {
    private const val TAG = "DtvKitSignal"

    // Null means the provider could not be queried. An empty reading can be retried
    // while the tuner is still making measurements available.
    suspend fun read(resolver: ContentResolver): DtvKitSignal? = withContext(Dispatchers.IO) {
        try {
            resolver.query(
                Uri.parse("content://com.nes.dtvkit.tvsetting/dynamic_table"),
                arrayOf("signal_information"),
                null,
                null,
                null,
            )?.use { cursor ->
                val valueIndex = cursor.getColumnIndex("value")
                if (valueIndex < 0) return@use null
                val payload = if (cursor.moveToFirst()) cursor.getString(valueIndex) else null
                parse(payload)
            }
        } catch (_: SecurityException) {
            SafeLog.d(TAG, "Provider denied signal read")
            null
        } catch (_: RuntimeException) {
            SafeLog.d(TAG, "Provider unavailable for signal read")
            null
        }
    }

    internal fun parse(payload: String?): DtvKitSignal {
        val root = try {
            payload?.let(JsonParser::parseString)
        } catch (_: JsonParseException) {
            null
        }
        var strength: Int? = null
        var quality: Int? = null
        if (root?.isJsonArray == true) {
            val entries = root.asJsonArray
            for (entry in entries) {
                if (!entry.isJsonObject) continue
                val fields = entry.asJsonObject
                val value = fields.get("value")?.takeIf { it.isJsonPrimitive }
                    ?.asString?.toIntOrNull()?.takeIf { it in 0..100 } ?: continue
                when (fields.get("name")?.takeIf { it.isJsonPrimitive }?.asString) {
                    "signal_strength" -> strength = value
                    "signal_quality" -> quality = value
                }
            }
            // This firmware endpoint also has a four-row form ordered as
            // strength, quality, dB and dBm. Use it only for unnamed rows.
            if (entries.size() == 4 && entries.all {
                    it.isJsonObject && !it.asJsonObject.has("name") &&
                        it.asJsonObject.get("value")?.isJsonPrimitive == true
                }) {
                strength = entries[0].asJsonObject.get("value").asString.toIntOrNull()?.takeIf { it in 0..100 }
                quality = entries[1].asJsonObject.get("value").asString.toIntOrNull()?.takeIf { it in 0..100 }
            }
        }
        return DtvKitSignal(strength, quality)
    }
}
