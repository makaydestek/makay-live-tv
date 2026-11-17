package com.makay.livetv.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.makay.livetv.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kanal logosu.
 *
 * Coil/Glide gibi ek bir gorsel yukleme kutuphanesi olmadan calisir:
 * `TvProvider`'in verdigi `content://` / `resource://` / `file://` URI'leri
 * arka planda (`Dispatchers.IO`) acilir, olceklendirilir ve proses boyunca
 * saklanan kucuk bir bellek onbelleGINE alinir. Boylece liste kaydirirken
 * her satir diskten yeniden okunmaz.
 *
 * Hata veya bos URI durumunda [fallbackLabel]'in ilk harfi gosterilir.
 */
@Composable
fun ChannelLogo(
    uri: Uri?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(uri) { mutableStateOf(uri?.let { LogoCache.get(it.toString()) }) }
    val context = LocalContext.current

    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    Drawable.createFromStream(input, uri.toString())?.toScaledBitmap()
                }
            }.onFailure {
                SafeLog.d(TAG, "Logo okunamadi: $uri (${it.message})")
            }.getOrNull()
        }
        if (loaded != null) {
            LogoCache.put(uri.toString(), loaded)
            bitmap = loaded
        }
    }

    val current = bitmap
    Box(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A1F29)),
        contentAlignment = Alignment.Center,
    ) {
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                text = fallbackLabel.take(1).uppercase(),
                color = Color(0xFF8FA6C8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Logoyu en fazla [MAX_LOGO_WIDTH] piksele indirger (bellek + cizim icin). */
private fun Drawable.toScaledBitmap(): Bitmap? {
    val rawW = intrinsicWidth.takeIf { it > 0 } ?: return null
    val rawH = intrinsicHeight.takeIf { it > 0 } ?: return null
    val scale = if (rawW > MAX_LOGO_WIDTH) MAX_LOGO_WIDTH.toFloat() / rawW else 1f
    val w = (rawW * scale).toInt().coerceAtLeast(1)
    val h = (rawH * scale).toInt().coerceAtLeast(1)
    return toBitmap(w, h)
}

/**
 * Prosse ozel logo onbellei: anahtari URI, degeri olceklenmis bitmap.
 * `sizeOf` kilobayt cinsinden; sinir asilirsa en eski kayit silinir.
 */
private object LogoCache {
    private const val MAX_KB = 8 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    fun put(key: String, value: Bitmap) {
        synchronized(cache) { cache.put(key, value) }
    }
}

private const val MAX_LOGO_WIDTH = 256
private const val TAG = "ChannelLogo"
