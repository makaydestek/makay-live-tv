package com.makay.livetv.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.makay.livetv.R
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.ChannelFilter
import com.makay.livetv.epg.ChannelEpg
import com.makay.livetv.player.AspectMode
import com.makay.livetv.player.PlaybackState
import com.makay.livetv.player.TrackState
import com.makay.livetv.player.TimeShiftState
import com.makay.livetv.player.TunerPlayerController
import com.makay.livetv.timer.TimerEvent
import com.makay.livetv.timer.TimerEventBus
import com.makay.livetv.update.AppUpdateViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tum ekranlari yoneten kok bilesen.
 *
 * Canli TV her zaman altta durur: kanal listesi, EPG ve ayarlar tam ekran
 * bindirme olarak acilir. Boylece bindirme kapanirken `TvView` yeniden
 * olusturulmaz ve yayin kesilmez.
 */
@Composable
fun AppRoot(
    viewModel: LiveTvViewModel,
    navigator: AppNavigator,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updateVm: AppUpdateViewModel = viewModel(
        factory = AppUpdateViewModel.Factory(context.applicationContext as android.app.Application),
    )
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(updateVm) { updateVm.checkOnLaunch() }
    DisposableEffect(lifecycleOwner, updateVm, updateState.needsInstallPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updateVm.checkForUpdateOnForeground()
                Lifecycle.Event.ON_RESUME -> if (updateState.needsInstallPermission) {
                    (context as? Activity)?.let(updateVm::retryInstallAfterPermission)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var backupNotice by remember { mutableStateOf<String?>(null) }
    val tunerSetupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshChannelsFromProvider()
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            backupNotice = context.getString(
                if (viewModel.exportChannelPreferences(uri)) R.string.channel_backup_exported else R.string.channel_backup_failed,
            )
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            backupNotice = context.getString(
                if (viewModel.importChannelPreferences(uri)) R.string.channel_backup_imported else R.string.channel_backup_invalid,
            )
        }
    }
    val launchTunerSetup: () -> Boolean = {
        val intent = viewModel.tunerSetupIntent()
        if (intent != null) {
            tunerSetupLauncher.launch(intent)
            true
        } else {
            viewModel.openTunerSetup()
        }
    }

    // Menu kilidi (referanstaki Security Settings ile ayni): acikken Ayarlar,
    // kanal listesi, zamanlayicilar ve kayitlar PIN ister; EPG ve canli TV
    // her zaman aciktir.
    var pendingLockedScreen by remember { mutableStateOf<AppScreen?>(null) }
    var menuLockPin by remember { mutableStateOf("") }
    var menuLockFailed by remember { mutableStateOf(false) }
    val menuLockEnabled by viewModel.menuLockEnabled.collectAsStateWithLifecycle()
    val openGuarded: (AppScreen) -> Unit = { screen ->
        if (menuLockEnabled && screen in MENU_LOCKED_SCREENS) {
            menuLockPin = ""
            menuLockFailed = false
            pendingLockedScreen = screen
        } else {
            navigator.navigate(screen)
        }
    }
    val openMenuDestination: (AppScreen) -> Unit = { screen ->
        navigator.popTo(AppScreen.LiveTv)
        openGuarded(screen)
    }
    val activeChannel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val channelsLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val channels by viewModel.visibleChannels.collectAsStateWithLifecycle()
    val allChannels = channels
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val recordableInputIds = when (val value = availability) {
        is com.makay.livetv.domain.model.TunerAvailability.Ready -> value.inputs.filter { it.supportsRecord }.map { it.id }.toSet()
        is com.makay.livetv.domain.model.TunerAvailability.NeedsScan -> value.inputs.filter { it.supportsRecord }.map { it.id }.toSet()
        com.makay.livetv.domain.model.TunerAvailability.NotSupported -> emptySet()
    }
    val epgMap by viewModel.epg.collectAsStateWithLifecycle()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingError by viewModel.recordingError.collectAsStateWithLifecycle()
    val maxAllowedAge by viewModel.maxAllowedAge.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val firstLaunchDone by viewModel.settings.firstLaunchDone.collectAsStateWithLifecycle()
    val showBanner by viewModel.settings.showInfoBanner.collectAsStateWithLifecycle()
    val bannerTimeout by viewModel.settings.bannerTimeoutMs.collectAsStateWithLifecycle()
    val showLogos by viewModel.settings.showChannelLogos.collectAsStateWithLifecycle()
    val numberBuffer by viewModel.numberBuffer.collectAsStateWithLifecycle()
    val aspectModeId by viewModel.settings.aspectModeId.collectAsStateWithLifecycle()
    val aspectMode = AspectMode.fromId(aspectModeId)
    val activeProgram = activeChannel?.let { epgMap[it.id]?.current }
    var ratingUnlockedProgramId by remember { mutableLongStateOf(-1L) }
    var agePromptPin by remember { mutableStateOf("") }
    var agePromptFailed by remember { mutableStateOf(false) }
    LaunchedEffect(activeProgram?.id) {
        if (ratingUnlockedProgramId != activeProgram?.id) {
            ratingUnlockedProgramId = -1L
            agePromptPin = ""
            agePromptFailed = false
        }
    }
    val ageBlockedProgram = activeProgram?.takeIf {
        it.id != ratingUnlockedProgramId && viewModel.isRatingBlocked(it)
    }

    var playbackState by remember { mutableStateOf<PlaybackState>(PlaybackState.Idle) }
    var controller by remember { mutableStateOf<TunerPlayerController?>(null) }
    var trackState by remember { mutableStateOf(TrackState()) }
    var timeShiftState by remember { mutableStateOf(TimeShiftState()) }
    var bannerVisible by remember { mutableStateOf(false) }
    var bannerToken by remember { mutableLongStateOf(0L) }
    var pendingTimerRequest by remember { mutableStateOf<TimerRequest?>(null) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var timerEvent by remember { mutableStateOf<TimerEvent?>(null) }
    var timerNotice by remember { mutableStateOf<com.makay.livetv.domain.model.TimerResult?>(null) }

    // Zamanlayici olayi: uygulama ayaktaysa canli akistan gelir; bildirimden
    // acildiginda intent pending'te bekler. Ikisi de bir kez islenir ve
    // consumePending ile temizlenir (iki kez pencere acilmaz).
    LaunchedEffect(Unit) {
        TimerEventBus.pending.value?.let { event ->
            timerEvent = event
            TimerEventBus.consumePending()
        }
        TimerEventBus.events.collect { event ->
            timerEvent = event
            TimerEventBus.consumePending()
        }
    }

    fun revealChannelInfo() {
        bannerVisible = true
        bannerToken++
    }

    // Handle DPAD and numeric keys before they reach the secure TV surface.
    // Several Homatics remotes stop delivering those keys to Compose after
    // playback has been running for a while.
    SideEffect {
        val screen = navigator.current
        val enabled = screen == AppScreen.LiveTv || screen == AppScreen.ChannelList
        (context as? com.makay.livetv.MainActivity)?.setLiveTvRemoteHandler(
            if (!enabled) null else { event ->
                val isDown = event.action == android.view.KeyEvent.ACTION_DOWN
                if (screen == AppScreen.ChannelList && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (isDown && event.repeatCount == 0) navigator.navigate(AppScreen.MainMenu)
                    true
                } else {
                    val channelDelta = when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_CHANNEL_UP -> +1
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                        else -> null
                    }
                    if (channelDelta != null && screen == AppScreen.LiveTv) {
                        if (isDown && event.repeatCount == 0) {
                            viewModel.cancelNumberEntry()
                            viewModel.zap(channelDelta)
                            revealChannelInfo()
                        }
                        true
                    } else {
                        val digit = event.keyCode.toChannelDigit()
                        if (digit != null && screen == AppScreen.LiveTv) {
                            if (isDown && event.repeatCount == 0) viewModel.inputDigit(digit)
                            true
                        } else false
                    }
                }
            },
        )
    }

    fun createTimer(request: TimerRequest) {
        scope.launch {
            val result = if (request.record) {
                viewModel.scheduleRecording(request.channel, request.program, request.repeatWeekly)
            } else {
                viewModel.scheduleReminder(request.channel, request.program, request.repeatWeekly)
            }
            if (result != com.makay.livetv.domain.model.TimerResult.ADDED) timerNotice = result
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingTimerRequest
        pendingTimerRequest = null
        if (granted && request != null) createTimer(request)
        else if (!granted) notificationPermissionDenied = true
    }

    fun scheduleWithNotificationPermission(
        channel: Channel,
        program: com.makay.livetv.domain.model.Program,
        record: Boolean,
        repeatWeekly: Boolean,
    ) {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val request = TimerRequest(channel, program, record, repeatWeekly)
        if (permitted) createTimer(request)
        else {
            pendingTimerRequest = request
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Oynatma secenekleri paneli izleri controller hazir olunca toplar.
    LaunchedEffect(controller) {
        val ready = controller ?: return@LaunchedEffect
        ready.trackState.collect(object : kotlinx.coroutines.flow.FlowCollector<TrackState> {
            override suspend fun emit(value: TrackState) { trackState = value }
        })
    }
    LaunchedEffect(controller) {
        val ready = controller ?: return@LaunchedEffect
        ready.timeShiftState.collect(object : kotlinx.coroutines.flow.FlowCollector<TimeShiftState> {
            override suspend fun emit(value: TimeShiftState) { timeShiftState = value }
        })
    }

    // Bindirme acilip kapaninca (ve ilk acilista) canli TV odagi geri alinir;
    // yarim kalmis numara girisi de iptal edilir. Kanal paneli aramasi ise
    // panel ozelidir: panel kapandiginda temizlenir ki sonraki acilista
    // filtrelenmis/kaybolmus liste surprizi olmasin.
    var focusEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(navigator.depth) {
        if (navigator.current == AppScreen.LiveTv) {
            focusEpoch++
        } else {
            viewModel.cancelNumberEntry()
        }
        if (navigator.current != AppScreen.ChannelList) viewModel.setQuery("")
    }

    // Kanal degisince ust ve alt bilgi birlikte gosterilir; sabit zamanlayici
    // her kanal ve manuel acilis icin mutlaka gizlenmelerini saglar.
    var previousBannerChannelId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(activeChannel?.id, showBanner) {
        val channelId = activeChannel?.id
        if (channelId == null) {
            bannerVisible = false
            previousBannerChannelId = null
            return@LaunchedEffect
        }
        val isFirstChannel = previousBannerChannelId == null
        previousBannerChannelId = channelId
        if (!isFirstChannel || showBanner) revealChannelInfo()
    }

    LaunchedEffect(bannerToken, bannerVisible, bannerTimeout) {
        if (!bannerVisible) return@LaunchedEffect
        val token = bannerToken
        delay(bannerTimeout.coerceAtMost(2_800L))
        if (bannerToken == token) bannerVisible = false
    }

    var previousNumberBuffer by remember { mutableStateOf("") }
    LaunchedEffect(numberBuffer) {
        if (previousNumberBuffer.isNotEmpty() && numberBuffer.isEmpty()) revealChannelInfo()
        previousNumberBuffer = numberBuffer
    }

    // Ilk kurulum kontrolu: tum kanal verisi yuklendikten sonra bir kez.
    LaunchedEffect(Unit) {
        if (!firstLaunchDone) navigator.navigate(AppScreen.FirstLaunch)
    }

    // Geri tusu bindirmeleri tek basista kapatir; kok ekranda uygulamadan cikar.
    BackHandler(enabled = true) {
        if (navigator.canPop) navigator.pop()
        else (context as? Activity)?.finish()
    }

    when (navigator.current) {
        AppScreen.FirstLaunch -> FirstLaunchScreen(
            availability = availability,
            onScan = { launchTunerSetup() },
            onContinue = {
                viewModel.completeFirstLaunch()
                navigator.pop()
            },
        )

        else -> LiveTvScreen(
            activeChannel = if (ageBlockedProgram == null) activeChannel else null,
            channelsLoading = channelsLoading,
            epg = if (ageBlockedProgram == null) activeChannel?.let { epgMap[it.id] } else null,
            playbackState = playbackState,
            bannerVisible = bannerVisible,
            numberBuffer = numberBuffer,
            isRecording = isRecording,
            focusToken = focusEpoch,
            aspectMode = aspectMode,
            onControllerReady = { controller = it },
            onState = { playbackState = it },
            onOpenChannelList = {
                revealChannelInfo()
                openGuarded(AppScreen.ChannelList)
            },
            onOpenMainMenu = { navigator.navigate(AppScreen.MainMenu) },
            onOpenEpg = { navigator.navigate(AppScreen.Epg) },
            onOpenSettings = { openGuarded(AppScreen.Settings) },
            onOpenTunerSetup = { launchTunerSetup() },
            onOpenOptions = { navigator.navigate(AppScreen.Options) },
            onToggleBanner = {
                bannerToken++
                bannerVisible = !bannerVisible
            },
            onZap = { delta ->
                viewModel.cancelNumberEntry()
                viewModel.zap(delta)
                revealChannelInfo()
            },
            onKeyDigit = { digit -> viewModel.inputDigit(digit) },
            onCommitNumber = {
                val committed = viewModel.commitNumberEntry()
                if (committed) revealChannelInfo()
                committed
            },
            onForwardKey = { event ->
                // TIF oturumuna dogrudan ilet (teletext / renkli / medya tuslari).
                controller?.dispatchKey(event.nativeKeyEvent) ?: false
            },
        )
    }

    // ---- Tam ekran bindirmeler ----
    when (navigator.current) {
        AppScreen.MainMenu -> MainMenuScreen(
            recordings = recordings,
            onOpenSetup = { launchTunerSetup() },
            onEnterPictureInPicture = {
                navigator.popTo(AppScreen.LiveTv)
                scope.launch {
                    delay(250)
                    val entered = (context as? com.makay.livetv.MainActivity)?.enterLivePictureInPicture() == true
                    if (!entered) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.picture_in_picture_unavailable),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onSelect = { destination ->
                if (destination == AppScreen.Recordings || destination == AppScreen.Timers) {
                    scope.launch { viewModel.refreshRecordings() }
                }
                openMenuDestination(destination)
            },
            onPlayRecording = { item ->
                controller?.playRecording(item.inputId, item.uri)
                navigator.popTo(AppScreen.LiveTv)
            },
        )

        AppScreen.ChannelList -> ChannelListScreen(
            channels = allChannels,
            activeChannelId = activeChannel?.id,
            epg = epgMap,
            showLogos = showLogos,
            availability = availability,
            onToggleFavorite = viewModel::toggleFavorite,
            onRename = viewModel::renameChannel,
            onSetHidden = viewModel::setChannelHidden,
            onSetDeleted = viewModel::setChannelDeleted,
            onSetSkipped = viewModel::setChannelSkipped,
            onMoveChannel = viewModel::moveChannel,
            onSetLocked = viewModel::setChannelLocked,
            onVerifyPin = viewModel::verifyParentalPin,
            onSelect = { channel ->
                viewModel.selectChannel(channel)
                revealChannelInfo()
                navigator.popTo(AppScreen.LiveTv)
            },
            // Odak sadece listede gezinir; kanal tune işlemi OK/click ile seçilince yapılır.
            onFocused = {},
            onScanChannels = { launchTunerSetup() },
            filter = filter,
            sort = sort,
            query = query,
            onCycleFilter = viewModel::cycleFilter,
            onCycleSort = viewModel::cycleSort,
            onQueryChange = viewModel::setQuery,
            onOpenMainMenu = { navigator.navigate(AppScreen.MainMenu) },
            onDismiss = { navigator.popTo(AppScreen.LiveTv) },
            autoHideTimeoutMs = bannerTimeout.coerceAtMost(2_800L),
            onPanelActivity = ::revealChannelInfo,
        )

        AppScreen.Epg -> EpgScreen(
            channels = allChannels,
            epg = epgMap,
            activeChannelId = activeChannel?.id,
            onSelectChannel = { channel ->
                viewModel.selectChannel(channel)
                navigator.popTo(AppScreen.LiveTv)
            },
            onLoadRange = { list, from, to -> viewModel.programsInRange(list, from, to) },
            scheduledProgramTimers = timers.asSequence()
                .filter { it.status == com.makay.livetv.domain.model.TimerStatus.SCHEDULED }
                .mapNotNull { timer -> timer.programId?.let { it to timer } }
                .toMap(),
            recordableInputIds = recordableInputIds,
            onScheduleReminder = { channel, program, weekly ->
                scheduleWithNotificationPermission(channel, program, record = false, repeatWeekly = weekly)
            },
            onScheduleRecording = { channel, program, weekly ->
                scheduleWithNotificationPermission(channel, program, record = true, repeatWeekly = weekly)
            },
            onCancelReminder = viewModel::cancelReminder,
        )

        AppScreen.Settings -> SettingsScreen(
            viewModel = viewModel,
            availability = availability,
            onOpenTimers = { openGuarded(AppScreen.Timers) },
            hasParentalPin = viewModel.hasParentalPin(),
            onUpdateParentalPin = viewModel::updateParentalPin,
            onClearParentalPin = viewModel::clearParentalPin,
            onOpenTunerSettings = { viewModel.openTunerSettings() },
            onExportChannelBackup = { exportBackupLauncher.launch("MakayLiveTV_channels.json") },
            onImportChannelBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/*")) },
            updateStatus = when {
                updateState.checking -> context.getString(R.string.update_checking)
                updateState.downloading -> updateState.statusMessage
                else -> updateState.error ?: updateState.statusMessage ?: context.getString(R.string.update_status_current)
            },
            onCheckUpdates = updateVm::checkManual,
            maxAllowedAge = maxAllowedAge,
            onSetMaxAllowedAge = viewModel::setMaxAllowedAge,
            isRecording = isRecording,
            recordingError = recordingError,
            canRecord = viewModel.canRecord(activeChannel),
            onStartRecording = {
                val currentProgram = activeChannel?.let { epgMap[it.id]?.current }
                viewModel.startRecording(activeChannel, currentProgram?.id)
            },
            onStopRecording = viewModel::stopRecording,
            onOpenRecordings = {
                scope.launch { viewModel.refreshRecordings() }
                openGuarded(AppScreen.Recordings)
            },
        )

        AppScreen.Timers -> TimersScreen(
            timers = timers,
            onCancel = viewModel::cancelReminder,
        )

        AppScreen.Recordings -> RecordingsScreen(
            recordings = recordings,
            onRefresh = { scope.launch { viewModel.refreshRecordings() } },
            onDelete = viewModel::deleteRecording,
            onPlay = { item ->
                controller?.playRecording(item.inputId, item.uri)
                navigator.popTo(AppScreen.LiveTv)
            },
        )

        AppScreen.Options -> PlaybackOptionsScreen(
            trackState = trackState,
            timeShiftState = timeShiftState,
            aspectMode = aspectMode,
            onAspectChange = { viewModel.settings.setAspectMode(it) },
            onSelectAudio = { controller?.selectAudioTrack(it) },
            onSelectSubtitle = { controller?.selectSubtitleTrack(it) },
            onToggleTeletext = {
                val handled = controller?.toggleTeletext() == true
                navigator.pop()
                if (!handled) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.teletext_unavailable),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onToggleTimeShift = {
                if (timeShiftState.paused) controller?.resumeTimeShift() else controller?.pauseTimeShift()
            },
            onSeekTimeShift = { controller?.seekTimeShiftBy(it) },
            onClose = { navigator.pop() },
        )

        else -> Unit
    }

    if (notificationPermissionDenied) {
        AlertDialog(
            onDismissRequest = { notificationPermissionDenied = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.notif_permission_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.notif_permission_body)) },
            confirmButton = {
                TextButton(onClick = { notificationPermissionDenied = false }) {
                    Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.epg_close))
                }
            },
        )
    }

    // Menu kilidi PIN penceresi: onaylaninca hedef ekran acilir.
    pendingLockedScreen?.let { lockedScreen ->
        AlertDialog(
            onDismissRequest = { pendingLockedScreen = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_pin_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = menuLockPin,
                        onValueChange = { menuLockPin = it.filter(Char::isDigit).take(8); menuLockFailed = false },
                        singleLine = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_pin_hint)) },
                    )
                    if (menuLockFailed) Text(
                        androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_pin_wrong),
                        color = androidx.compose.ui.graphics.Color(0xFFFF8A80),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.verifyParentalPin(menuLockPin)) {
                        navigator.navigate(lockedScreen)
                        pendingLockedScreen = null
                    } else menuLockFailed = true
                }) { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_unlock_watch)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLockedScreen = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.timer_remind_close))
                }
            },
        )
    }

    // Zamanlayici ekleme sonucu: kullanici neden eklenemedigini gorur
    // (sinir / cakisma / program bitmis / girdi desteklemiyor).
    timerNotice?.let { result ->
        val messageRes = when (result) {
            com.makay.livetv.domain.model.TimerResult.DUPLICATE -> com.makay.livetv.R.string.timer_exists
            com.makay.livetv.domain.model.TimerResult.LIMIT -> com.makay.livetv.R.string.timer_limit_reached
            com.makay.livetv.domain.model.TimerResult.CONFLICT -> com.makay.livetv.R.string.timer_conflict
            com.makay.livetv.domain.model.TimerResult.NOT_SUPPORTED -> com.makay.livetv.R.string.timer_record_unsupported
            com.makay.livetv.domain.model.TimerResult.ENDED -> com.makay.livetv.R.string.timer_program_ended
            com.makay.livetv.domain.model.TimerResult.ADDED -> com.makay.livetv.R.string.timer_added
        }
        AlertDialog(
            onDismissRequest = { timerNotice = null },
            title = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.timer_remind_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(messageRes)) },
            confirmButton = {
                TextButton(onClick = { timerNotice = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.timer_remind_close))
                }
            },
        )
    }

    // Zamanlayici hatirlatmasi / kayit bilgisi: kanala gecis veya kayit baslatma.
    timerEvent?.let { event ->
        val eventChannel = allChannels.firstOrNull { it.id == event.channelId }
            ?: viewModel.channelById(event.channelId)
        val channelLabel = (
            eventChannel?.let { "${it.displayNumber} ${it.displayName}" }
                ?: "${event.channelNumber} ${event.channelName}"
            ).trim()
        val eventProgram = eventChannel?.let { epgMap[it.id]?.current }
        val programLabel = event.programTitle.ifEmpty { eventProgram?.title.orEmpty() }
        AlertDialog(
            onDismissRequest = { timerEvent = null },
            title = {
                Text(
                    androidx.compose.ui.res.stringResource(
                        if (event.isRecord) com.makay.livetv.R.string.timer_record_started
                        else com.makay.livetv.R.string.timer_remind_title,
                    ),
                )
            },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(
                        com.makay.livetv.R.string.timer_remind_body,
                        channelLabel,
                        programLabel,
                    ),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        if (eventChannel != null) {
                            viewModel.selectChannel(eventChannel)
                            revealChannelInfo()
                            navigator.popTo(AppScreen.LiveTv)
                        }
                        timerEvent = null
                    }) {
                        Text(
                            androidx.compose.ui.res.stringResource(
                                com.makay.livetv.R.string.timer_remind_watch,
                            ),
                        )
                    }
                    if (!event.isRecord && eventChannel != null && viewModel.canRecord(eventChannel)) {
                        TextButton(onClick = {
                            // Basariliysa kapat; degilse pencere kalir (hata kaydi gorulur).
                            if (viewModel.startRecording(eventChannel, eventProgram?.id)) {
                                timerEvent = null
                            }
                        }) {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    com.makay.livetv.R.string.timer_remind_record,
                                ),
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { timerEvent = null }) {
                    Text(
                        androidx.compose.ui.res.stringResource(
                            com.makay.livetv.R.string.timer_remind_close,
                        ),
                    )
                }
            },
        )
    }

    ageBlockedProgram?.let { blockedProgram ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_rating_blocked)) },
            text = {
                Column {
                    Text(blockedProgram.title)
                    OutlinedTextField(
                        value = agePromptPin,
                        onValueChange = { agePromptPin = it.filter(Char::isDigit).take(8); agePromptFailed = false },
                        singleLine = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_pin_hint)) },
                    )
                    if (agePromptFailed) Text(
                        androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_pin_wrong),
                        color = androidx.compose.ui.graphics.Color(0xFFFF8A80),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.verifyParentalPin(agePromptPin)) {
                        ratingUnlockedProgramId = blockedProgram.id
                        agePromptPin = ""
                        agePromptFailed = false
                    } else agePromptFailed = true
                }) { Text(androidx.compose.ui.res.stringResource(com.makay.livetv.R.string.parental_unlock_watch)) }
            },
        )
    }

    backupNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { backupNotice = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { backupNotice = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.action_ok))
                }
            },
        )
    }

    val availableUpdate = updateState.available
    if (updateState.dialogVisible && availableUpdate != null) {
        AlertDialog(
            onDismissRequest = { if (!updateState.downloading) updateVm.dismissDialog(postpone = true) },
            title = { Text(context.getString(R.string.update_available_title, availableUpdate.versionName)) },
            text = {
                Column {
                    Text(context.getString(R.string.update_available_body))
                    if (updateState.downloading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { updateState.progress / 100f },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        )
                        Text(updateState.statusMessage ?: context.getString(R.string.update_downloading))
                    }
                    updateState.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val activity = context as? Activity
                        if (activity != null) updateVm.startDownloadAndInstall(activity) else updateVm.reportNoActivity()
                    },
                    enabled = !updateState.downloading,
                ) { Text(if (updateState.downloading) context.getString(R.string.update_downloading) else context.getString(R.string.update_install)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { updateVm.dismissDialog(postpone = true) },
                    enabled = !updateState.downloading,
                ) { Text(context.getString(R.string.update_later)) }
            },
        )
    }
}

private data class TimerRequest(
    val channel: Channel,
    val program: com.makay.livetv.domain.model.Program,
    val record: Boolean,
    val repeatWeekly: Boolean,
)

/** Menu kilidi acikken PIN istenen ekranlar (EPG ve canli TV disarida). */
private val MENU_LOCKED_SCREENS = setOf(
    AppScreen.ChannelList,
    AppScreen.Settings,
    AppScreen.Timers,
    AppScreen.Recordings,
)

private fun Int.toChannelDigit(): String? = when {
    this in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
        (this - android.view.KeyEvent.KEYCODE_0).toString()
    this in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
        (this - android.view.KeyEvent.KEYCODE_NUMPAD_0).toString()
    else -> null
}
