package com.makay.livetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role

/**
 * Uzaktan kumanda icin "tiklama" davranisi.
 *
 * TV'de dokunma yoktur; DPAD merkez tusu (`Key.Enter` / `Key.DirectionCenter`)
 * `clickable` tarafindan **otomatik** olarak tetiklenmez — bu yuzden
 * `onKeyEvent` ile acikca ele alinir. Uzun basma (OK tusunu basili tutma)
 * favori degistirme gibi ikincil eylemlerde kullanilir.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvClickable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this
    .focusable(interactionSource = interactionSource)
    .combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        role = Role.Button,
        onClick = onClick,
        onLongClick = onLongClick,
    )
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                onClick()
                true
            }
            else -> false
        }
    }

/**
 * Odak istegini yalnizca bilesen ilk kez gorunur oldugunda yapar.
 * LazyList icinde her satirin odak istemesi odak savasina yol acar;
 * bu yuzden yalnizca [shouldRequest] `true` olan satir icin kullanilir.
 */
@Composable
fun AutoFocus(
    focusRequester: FocusRequester,
    shouldRequest: Boolean,
    key: Any? = Unit,
) {
    LaunchedEffect(key, shouldRequest) {
        if (shouldRequest) {
            runCatching { focusRequester.requestFocus() }
        }
    }
}

/** Odak istegini guvenli sekilde dener (henuz compose tree'de yoksa yut). */
fun FocusRequester.requestFocusSafely() {
    runCatching { requestFocus() }
}

/**
 * Bir ogenin odakli olmasini izleyen kisa yardimci; odak degisiminde
 * yeniden kompozisyon tetikler.
 */
@Composable
fun rememberFocusState(): Pair<androidx.compose.runtime.MutableState<Boolean>, MutableInteractionSource> {
    val state = remember { androidx.compose.runtime.mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    return state to source
}
