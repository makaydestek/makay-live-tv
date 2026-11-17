package com.makay.livetv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.makay.livetv.ui.theme.MakayPrimary

/**
 * Uzaktan kumanda odak sistemi.
 *
 * TV'de "hover" yoktur; kullanici yalnizca DPAD ile gezer. Bu yuzden her
 * etkilesimli ogenin odak halkasi **çok belirgin** olmali ve olcek
 * animasyonu 2-3 metre mesafeden fark edilmelidir.
 */
object TvFocus {
    val scaleFocused = 1.06f
    val scaleNormal = 1f
    val borderWidth = 3.dp
    val focusShape = RoundedCornerShape(12.dp)
    val glowColor: Color = MakayPrimary
    val padding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
}

/** Odak durumunu izleyip olcek doner (animasyonlu). */
@Composable
fun rememberFocusScale(
    interactionSource: MutableInteractionSource,
    focusedScale: Float = TvFocus.scaleFocused,
): Float {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else TvFocus.scaleNormal,
        animationSpec = tween(durationMillis = 140),
        label = "tvFocusScale",
    )
    return scale
}

/** Odak olcegi + kirpma uygulayan hazir modifier. */
@Composable
fun Modifier.tvFocusableItem(
    interactionSource: MutableInteractionSource,
    shape: androidx.compose.ui.graphics.Shape = TvFocus.focusShape,
): Modifier {
    val scale = rememberFocusScale(interactionSource)
    return this
        .scale(scale)
        .clip(shape)
}

/** LazyRow/LazyColumn icinde odaklanan ogenin role cikarilmasi icin animasyon. */
@Composable
fun focusedBorderColor(interactionSource: MutableInteractionSource): Color {
    val focused by interactionSource.collectIsFocusedAsState()
    return if (focused) TvFocus.glowColor else Color.Transparent
}

/** Tekrar eden odak semantigi icin kisa yardimci. */
@Composable
fun rememberTvInteractionSource(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/** Odakliyken cevrelenmis metin rengi (koyu zeminde okunurluk). */
fun onFocusedTextColor(): Color = Color.White

/** Malzeme temasindan odak rengini dogrulayan kucuk yardimci (test edilebilir). */
internal fun resolvedFocusColor(isFocused: Boolean): Color =
    if (isFocused) MakayPrimary else Color.Transparent
