package com.makay.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makay.livetv.BuildConfig
import com.makay.livetv.R
import com.makay.livetv.domain.model.TunerAvailability
import com.makay.livetv.ui.theme.MakayPrimary
import com.makay.livetv.ui.theme.OverlaySurface
import com.makay.livetv.ui.theme.ThemeMode

/** Ayarlar ekrani — bolumler ve degerler tek sutunda, DPAD ile gezinilir. */
@Composable
fun SettingsScreen(
    viewModel: LiveTvViewModel,
    availability: TunerAvailability,
    onOpenTimers: () -> Unit,
    isRecording: Boolean,
    recordingError: String?,
    canRecord: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenRecordings: () -> Unit,
    hasParentalPin: Boolean,
    onUpdateParentalPin: (String, String) -> Boolean,
    onClearParentalPin: (String) -> Boolean,
    onOpenTunerSettings: () -> Unit,
    onStartTkgsScan: () -> Unit,
    onExportChannelBackup: () -> Unit,
    onImportChannelBackup: () -> Unit,
    updateStatus: String?,
    onCheckUpdates: () -> Unit,
    maxAllowedAge: Int?,
    onSetMaxAllowedAge: (Int?) -> Boolean,
) {
    val settings = viewModel.settings
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val startLast by settings.startWithLastChannel.collectAsStateWithLifecycle()
    val showBanner by settings.showInfoBanner.collectAsStateWithLifecycle()
    val showLogos by settings.showChannelLogos.collectAsStateWithLifecycle()
    val fontScale by settings.fontScale.collectAsStateWithLifecycle()
    val bannerTimeout by settings.bannerTimeoutMs.collectAsStateWithLifecycle()
    var parentalPinIsSet by remember { mutableStateOf(hasParentalPin) }
    var showPinDialog by remember { mutableStateOf(false) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // TV'de dokunma yok: ekran acilirken ilk ayar ogesine odaklanir.
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF000000))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.settings),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))

        Section(stringResource(R.string.section_appearance))
        SettingRow(
            title = stringResource(R.string.settings_theme),
            subtitle = stringResource(R.string.settings_theme_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption(
                    label = UiText.themeLabel(ThemeMode.SYSTEM),
                    selected = themeMode == ThemeMode.SYSTEM,
                    focusRequester = firstFocus,
                ) {
                    settings.setThemeMode(ThemeMode.SYSTEM)
                }
                SegmentedOption(UiText.themeLabel(ThemeMode.DARK), themeMode == ThemeMode.DARK) {
                    settings.setThemeMode(ThemeMode.DARK)
                }
                SegmentedOption(UiText.themeLabel(ThemeMode.LIGHT), themeMode == ThemeMode.LIGHT) {
                    settings.setThemeMode(ThemeMode.LIGHT)
                }
            }
        }
        SettingRow(
            title = stringResource(R.string.settings_text_size),
            subtitle = stringResource(R.string.settings_text_size_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.9f, 1f, 1.2f).forEach { scale ->
                    SegmentedOption(
                        label = stringResource(when (scale) {
                            0.9f -> R.string.settings_text_small
                            1f -> R.string.settings_text_normal
                            else -> R.string.settings_text_large
                        }),
                        selected = fontScale == scale,
                    ) { settings.setFontScale(scale) }
                }
            }
        }

        Section(stringResource(R.string.section_playback))
        SettingRow(
            title = stringResource(R.string.settings_start_last_channel),
            subtitle = stringResource(R.string.settings_start_last_channel_desc),
        ) {
            TvSwitch(checked = startLast) { settings.setStartWithLastChannel(it) }
        }
        SettingRow(
            title = stringResource(R.string.settings_channel_logo),
            subtitle = stringResource(R.string.settings_channel_logo_desc),
        ) {
            TvSwitch(checked = showLogos) { settings.setShowChannelLogos(it) }
        }
        SettingRow(
            title = stringResource(R.string.settings_info_banner),
            subtitle = stringResource(R.string.settings_info_banner_desc),
        ) {
            TvSwitch(checked = showBanner) { settings.setShowInfoBanner(it) }
        }
        SettingRow(
            title = stringResource(R.string.settings_banner_timeout),
            subtitle = stringResource(
                R.string.settings_banner_timeout_value,
                pluralStringResource(
                    R.plurals.banner_timeout_seconds,
                    (bannerTimeout / 1000).toInt(),
                    (bannerTimeout / 1000).toInt(),
                ),
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3_000L, 5_000L, 8_000L, 12_000L).forEach { ms ->
                    SegmentedOption("${ms / 1000}s", bannerTimeout == ms) {
                        settings.setBannerTimeout(ms)
                    }
                }
            }
        }

        Section(stringResource(R.string.section_tuner))
        SettingRow(
            title = stringResource(R.string.settings_tuner_input),
            subtitle = tunerSubtitle(availability),
        ) {
            Text(
                text = activeInputLabel(availability),
                color = MakayPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        SettingRow(
            title = stringResource(R.string.settings_tuner_setup),
            subtitle = stringResource(R.string.settings_tuner_setup_desc),
        ) {
            ActionButton(stringResource(R.string.action_open)) { onOpenTunerSettings() }
        }
        SettingRow(
            title = stringResource(R.string.settings_tkgs_setup),
            subtitle = stringResource(R.string.settings_tkgs_setup_desc),
        ) {
            ActionButton(stringResource(R.string.action_open)) { onStartTkgsScan() }
        }
        SettingRow(
            title = stringResource(R.string.settings_channel_count),
            subtitle = stringResource(R.string.settings_channel_count_desc),
        ) {
            Text(
                text = when (availability) {
                    is TunerAvailability.Ready -> pluralStringResource(
                        R.plurals.channel_count_value,
                        availability.channelCount,
                        availability.channelCount,
                    )
                    else -> "—"
                },
                color = Color.White,
                fontSize = 15.sp,
            )
        }

        Section(stringResource(R.string.section_maintenance))
        SettingRow(
            title = stringResource(R.string.update_settings_title),
            subtitle = updateStatus ?: stringResource(R.string.update_settings_desc),
        ) {
            ActionButton(stringResource(R.string.update_check_button), onCheckUpdates)
        }
        SettingRow(
            title = stringResource(R.string.channel_backup_title),
            subtitle = stringResource(R.string.channel_backup_desc),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.channel_backup_export)) { onExportChannelBackup() }
                ActionButton(stringResource(R.string.channel_backup_import)) { onImportChannelBackup() }
            }
        }
        SettingRow(
            title = stringResource(R.string.diagnostics_title),
            subtitle = stringResource(R.string.diagnostics_desc),
        ) {
            ActionButton(stringResource(R.string.action_open)) { showDiagnostics = true }
        }
        Section(stringResource(R.string.section_recording))
        SettingRow(
            title = stringResource(R.string.timer_record_title),
            subtitle = recordingError ?: stringResource(
                if (isRecording) R.string.timer_recording_active else if (canRecord) R.string.timer_record_ready else R.string.timer_record_unsupported,
            ),
        ) {
            if (isRecording) {
                ActionButton(stringResource(R.string.timer_record_stop), onStopRecording)
            } else if (canRecord) {
                ActionButton(stringResource(R.string.timer_record_start), onStartRecording)
            }
        }
        SettingRow(
            title = stringResource(R.string.recordings_title),
            subtitle = stringResource(R.string.recordings_open_desc),
        ) {
            ActionButton(stringResource(R.string.action_open)) { onOpenRecordings() }
        }
        Section(stringResource(R.string.section_parental))
        SettingRow(
            title = stringResource(R.string.parental_pin_title),
            subtitle = stringResource(if (parentalPinIsSet) R.string.parental_pin_enabled else R.string.parental_pin_disabled),
        ) {
            ActionButton(stringResource(if (parentalPinIsSet) R.string.parental_pin_change else R.string.parental_pin_set)) {
                showPinDialog = true
                pinError = false
            }
        }
        SettingRow(
            title = stringResource(R.string.parental_age_limit),
            subtitle = stringResource(if (parentalPinIsSet) R.string.parental_age_limit_desc else R.string.parental_age_requires_pin),
        ) {
            if (parentalPinIsSet) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 7, 12, 16, 18).forEach { age ->
                        SegmentedOption(
                            label = age?.let { "$it+" } ?: stringResource(R.string.parental_age_off),
                            selected = maxAllowedAge == age,
                        ) { onSetMaxAllowedAge(age) }
                    }
                }
            }
        }
        SettingRow(
            title = stringResource(R.string.timers_title),
            subtitle = stringResource(R.string.timers_manage_desc),
        ) {
            ActionButton(stringResource(R.string.action_open)) { onOpenTimers() }
        }
        SettingRow(
            title = stringResource(R.string.settings_clear_recent),
            subtitle = stringResource(R.string.settings_clear_recent_desc),
        ) {
            ActionButton(stringResource(R.string.action_clear)) {
                viewModel.clearRecentlyWatched()
            }
        }
        SettingRow(
            title = stringResource(R.string.channel_restore),
            subtitle = stringResource(R.string.channel_restore_desc),
        ) {
            ActionButton(stringResource(R.string.action_restore)) {
                viewModel.restoreHiddenAndDeleted()
            }
        }

        Section(stringResource(R.string.settings_about))
        SettingRow(
            title = stringResource(R.string.settings_about_app),
            subtitle = stringResource(R.string.settings_installed_version_desc),
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Color(0xFF8FA6C8),
                fontSize = 15.sp,
            )
        }
        SettingRow(
            title = stringResource(R.string.update_settings_title),
            subtitle = updateStatus ?: stringResource(R.string.update_status_current),
        ) {
            ActionButton(stringResource(R.string.update_check_button), onCheckUpdates)
        }
        SettingRow(
            title = stringResource(R.string.settings_platform),
            subtitle = stringResource(R.string.settings_platform_desc),
        ) {
            Text(
                text = stringResource(R.string.platform_tif),
                color = Color(0xFF8FA6C8),
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.back_to_close),
            color = Color(0xFF6B7684),
            fontSize = 13.sp,
        )
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false; currentPin = ""; newPin = ""; pinError = false },
            title = { Text(stringResource(R.string.parental_pin_title)) },
            text = {
                Column {
                    if (parentalPinIsSet) OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter(Char::isDigit).take(8); pinError = false },
                        singleLine = true,
                        label = { Text(stringResource(R.string.parental_pin_current)) },
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter(Char::isDigit).take(8); pinError = false },
                        singleLine = true,
                        label = { Text(stringResource(R.string.parental_pin_new)) },
                    )
                    if (pinError) Text(stringResource(R.string.parental_pin_wrong), color = Color(0xFFFF8A80))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onUpdateParentalPin(currentPin, newPin)) {
                        showPinDialog = false
                        parentalPinIsSet = true
                        currentPin = ""
                        newPin = ""
                    } else pinError = true
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    if (parentalPinIsSet) TextButton(onClick = {
                        if (onClearParentalPin(currentPin)) {
                            showPinDialog = false
                            parentalPinIsSet = false
                            currentPin = ""
                        } else pinError = true
                    }) { Text(stringResource(R.string.parental_pin_remove)) }
                    TextButton(onClick = { showPinDialog = false }) { Text(stringResource(R.string.epg_close)) }
                }
            },
        )
    }

    if (showDiagnostics) {
        val inputs = when (val state = availability) {
            is TunerAvailability.Ready -> state.inputs
            is TunerAvailability.NeedsScan -> state.inputs
            TunerAvailability.NotSupported -> emptyList()
        }
        val channelCount = (availability as? TunerAvailability.Ready)?.channelCount ?: 0
        val report = buildString {
            appendLine("Makay Live TV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android API: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Availability: ${availability::class.simpleName}")
            appendLine("Channels: $channelCount")
            appendLine("Recording active: $isRecording")
            inputs.forEach { input ->
                appendLine("TV input: ${input.label} (${input.packageName})")
                appendLine("  tuner=${input.supportsTuner}, record=${input.supportsRecord}, count=${input.tunerCount}")
            }
        }.trim()
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = { Text(report) },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                    Text(stringResource(R.string.diagnostics_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnostics = false }) {
                    Text(stringResource(R.string.epg_close))
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title.uppercase(),
        color = MakayPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF232936)),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 17.sp)
            subtitle?.let {
                Text(text = it, color = Color(0xFF8FA6C8), fontSize = 13.sp)
            }
        }
        value()
    }
}

@Composable
private fun SegmentedOption(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MakayPrimary else Color(0xFF1A1F29))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
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
private fun ActionButton(label: String, onClick: () -> Unit) {
    val interaction = rememberTvInteractionSource()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) OverlaySurface else Color(0xFF1A1F29))
            .border(2.dp, if (focused) MakayPrimary else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun TvSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = rememberTvInteractionSource()
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        interactionSource = interaction,
    )
}

@Composable
private fun tunerSubtitle(availability: TunerAvailability): String = when (availability) {
    is TunerAvailability.Ready -> stringResource(
        R.string.settings_tuner_input_desc_ready,
        pluralStringResource(
            R.plurals.input_count,
            availability.inputs.size,
            availability.inputs.size,
        ),
    )
    is TunerAvailability.NeedsScan -> stringResource(R.string.settings_tuner_input_desc_scan)
    TunerAvailability.NotSupported -> stringResource(R.string.settings_tuner_input_desc_none)
}

@Composable
private fun activeInputLabel(availability: TunerAvailability): String = when (availability) {
    is TunerAvailability.Ready -> availability.inputs.firstOrNull()?.shortLabel ?: "—"
    is TunerAvailability.NeedsScan -> availability.inputs.firstOrNull()?.shortLabel ?: "—"
    TunerAvailability.NotSupported -> stringResource(R.string.first_launch_none)
}
