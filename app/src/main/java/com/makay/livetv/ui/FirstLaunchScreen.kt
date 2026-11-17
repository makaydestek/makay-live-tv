package com.makay.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makay.livetv.R
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.MakaySecondary

/**
 * Ilk kurulum: tuner durumunu gosterip kullaniciyi canli TV'ye alir.
 * Uzun sihirbaz yerine tek ekran; TV'de hiz onemlidir.
 */
@Composable
fun FirstLaunchScreen(
    availability: TunerAvailability,
    onContinue: () -> Unit,
    onScan: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF000000)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 26.dp).widthIn(max = 980.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.first_launch_title),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.first_launch_setup_steps),
                color = Color(0xFF8FA6C8),
                fontSize = 15.sp,
                textAlign = TextAlign.Start,
            )

            Spacer(Modifier.height(36.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusItem(
                    label = stringResource(R.string.first_launch_status_tuner),
                    value = when (availability) {
                        is TunerAvailability.Ready -> stringResource(R.string.first_launch_ready)
                        is TunerAvailability.NeedsScan -> stringResource(R.string.first_launch_need_scan)
                        TunerAvailability.NotSupported -> stringResource(R.string.first_launch_none)
                    },
                    ok = availability is TunerAvailability.Ready,
                )
                StatusItem(
                    label = stringResource(R.string.first_launch_status_channel),
                    value = when (availability) {
                        is TunerAvailability.Ready -> availability.channelCount.toString()
                        else -> "0"
                    },
                    ok = availability is TunerAvailability.Ready,
                )
                StatusItem(
                    label = stringResource(R.string.first_launch_status_input),
                    value = when (availability) {
                        is TunerAvailability.Ready -> availability.inputs.firstOrNull()?.shortLabel ?: "—"
                        is TunerAvailability.NeedsScan -> availability.inputs.firstOrNull()?.shortLabel ?: "—"
                        TunerAvailability.NotSupported -> "—"
                    },
                    ok = availability !is TunerAvailability.NotSupported,
                )
            }

            if (availability is TunerAvailability.NeedsScan) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.first_launch_scan_body),
                    color = Color(0xFFFFC107),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                BigButton(
                    label = stringResource(R.string.action_scan_channels),
                    onClick = onScan,
                )
            }

            if (availability !is TunerAvailability.NeedsScan && availability !is TunerAvailability.NotSupported) {
                Spacer(Modifier.height(16.dp))
                BigButton(
                    label = stringResource(R.string.first_launch_open_setup),
                    onClick = onScan,
                )
            }

            Spacer(Modifier.height(20.dp))

            BigButton(
                label = stringResource(R.string.first_launch_continue),
                onClick = onContinue,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String, ok: Boolean) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141922))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, color = Color(0xFF8FA6C8), fontSize = 12.sp)
        Text(
            text = value,
            color = if (ok) MakaySecondary else Color(0xFFFFC107),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BigButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) MakayPrimary else Color(0xFF1F2633))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = 56.dp, vertical = 16.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
