package com.makay.livetv.update

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File

data class AppUpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val available: AvailableUpdate? = null,
    val dialogVisible: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val needsInstallPermission: Boolean = false,
    val downloadedApk: File? = null
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppUpdateRepository(application)

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var postponedThisSession = false
    private var wasBackgrounded = false
    private var lastForegroundCheckAt = 0L
    private var launchCheckJob: Job? = null

    fun checkOnLaunch() {
        checkForUpdateOnForeground()
    }

    /** Recheck after returning to the app, but debounce duplicate lifecycle events. */
    fun checkForUpdateOnForeground() {
        val now = SystemClock.elapsedRealtime()
        if (launchCheckJob?.isActive == true) return
        if (lastForegroundCheckAt > 0L && now - lastForegroundCheckAt < FOREGROUND_CHECK_DEBOUNCE_MS) return
        if (wasBackgrounded) postponedThisSession = false
        wasBackgrounded = false
        lastForegroundCheckAt = now
        launchCheckJob = viewModelScope.launch {
            _state.update { it.copy(checking = true, error = null) }
            val result = runCatching {
                // Her uygulama açılışında/öne gelişinde resmi son sürümü denetle.
                withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
            }
            val update = result.getOrNull()
            if (update != null && !postponedThisSession) {
                _state.update {
                    it.copy(
                        checking = false,
                        available = update,
                        dialogVisible = true,
                        statusMessage = "Yeni sürüm: ${update.versionName}",
                        error = null
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        checking = false,
                        available = update,
                        dialogVisible = false,
                        statusMessage = if (result.isSuccess && update == null) "Uygulama güncel" else null,
                        error = result.exceptionOrNull()?.localizedMessage
                    )
                }
            }
        }
    }

    /** Postpone is session-scoped; leaving the app allows the next entry to prompt again. */
    fun onAppBackgrounded() {
        wasBackgrounded = true
    }

    fun handleNotificationOpen(autoDownload: Boolean, activity: Activity?) {
        viewModelScope.launch {
            postponedThisSession = false
            _state.update { it.copy(checking = true, error = null) }
            val update = _state.value.available
                ?: runCatching {
                    withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
                }.getOrNull()
            if (update == null) {
                _state.update {
                    it.copy(
                        checking = false,
                        dialogVisible = false,
                        statusMessage = "Uygulama güncel",
                        error = null
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    checking = false,
                    available = update,
                    dialogVisible = true,
                    statusMessage = "Yeni sürüm: ${update.versionName}",
                    error = null
                )
            }
            if (autoDownload && activity != null) {
                startDownloadAndInstall(activity)
            }
        }
    }

    fun checkManual() {
        viewModelScope.launch {
            postponedThisSession = false
            _state.update {
                it.copy(checking = true, error = null, statusMessage = "Güncelleme kontrol ediliyor…")
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.checkForUpdate(force = true) }
            }
            val update = result.getOrNull()
            val err = result.exceptionOrNull()?.localizedMessage ?: result.exceptionOrNull()?.message
            when {
                update != null -> {
                    _state.update {
                        it.copy(
                            checking = false,
                            available = update,
                            dialogVisible = true,
                            statusMessage = "Yeni sürüm: ${update.versionName}",
                            error = null
                        )
                    }
                }
                err != null -> {
                    _state.update {
                        it.copy(checking = false, statusMessage = null, error = "Kontrol başarısız: $err")
                    }
                }
                else -> {
                    _state.update {
                        it.copy(
                            checking = false,
                            available = null,
                            dialogVisible = false,
                            statusMessage = "Uygulama güncel",
                            error = null
                        )
                    }
                }
            }
        }
    }

    fun dismissDialog(postpone: Boolean) {
        if (postpone) postponedThisSession = true
        _state.update { it.copy(dialogVisible = false) }
    }

    fun reportNoActivity() {
        _state.update {
            it.copy(error = "Kurulum başlatılamadı. Uygulamayı yeniden açıp tekrar deneyin.")
        }
    }

    fun clearStatus() {
        _state.update { it.copy(statusMessage = null, error = null) }
    }

    fun startDownloadAndInstall(activity: Activity) {
        val update = _state.value.available ?: return
        if (_state.value.downloading) return
        viewModelScope.launch {
            if (!repository.canRequestInstall()) {
                _state.update {
                    it.copy(
                        needsInstallPermission = true,
                        dialogVisible = true,
                        error = "Ayarlardan “Bilinmeyen uygulamalar” iznini açın, sonra tekrar deneyin."
                    )
                }
                runCatching { activity.startActivity(repository.unknownSourcesSettingsIntent()) }
                return@launch
            }
            _state.update {
                it.copy(
                    downloading = true,
                    progress = 0,
                    error = null,
                    dialogVisible = true,
                    statusMessage = "APK indiriliyor…"
                )
            }
            val download = runCatching {
                withContext(Dispatchers.IO) {
                    repository.downloadApk(update) { pct ->
                        _state.update { s ->
                            s.copy(
                                progress = pct,
                                statusMessage = if (pct >= 100) {
                                    "İmza ve bütünlük doğrulanıyor…"
                                } else {
                                    "APK indiriliyor… %$pct"
                                }
                            )
                        }
                    }
                }
            }
            val file = download.getOrNull()
            if (file == null || !file.isFile) {
                val raw = friendlyError(
                    download.exceptionOrNull(),
                    fallback = "İndirme başarısız. Bağlantınızı kontrol edip tekrar deneyin."
                )
                _state.update {
                    it.copy(
                        downloading = false,
                        dialogVisible = true,
                        error = raw,
                        statusMessage = null,
                        downloadedApk = null
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    downloading = false,
                    progress = 100,
                    downloadedApk = file,
                    statusMessage = "Doğrulandı — kurulum başlatılıyor…",
                    dialogVisible = true,
                    error = null
                )
            }
            runCatching {
                activity.startActivity(repository.installIntent(file))
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        dialogVisible = true,
                        error = friendlyError(e, "Kurulum ekranı açılamadı")
                    )
                }
            }
        }
    }

    fun retryInstallAfterPermission(activity: Activity) {
        if (!repository.canRequestInstall()) return
        val file = _state.value.downloadedApk
        if (file != null && file.exists()) {
            runCatching { activity.startActivity(repository.installIntent(file)) }
            _state.update { it.copy(needsInstallPermission = false, error = null) }
            return
        }
        _state.update { it.copy(needsInstallPermission = false, error = null) }
        if (_state.value.available != null) startDownloadAndInstall(activity)
    }

    private fun friendlyError(t: Throwable?, fallback: String): String {
        if (t == null) return fallback
        val msg = sequenceOf(t.message, t.localizedMessage)
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        return when {
            msg == null -> fallback
            msg.contains("ENOENT", ignoreCase = true) ||
                msg.contains("No such file", ignoreCase = true) ->
                "APK dosyası kaydedilemedi. Depolama alanını kontrol edip Güncelle’ye tekrar basın."
            msg.contains("SHA-256", ignoreCase = true) ||
                msg.contains("imza", ignoreCase = true) -> msg
            msg.length > 280 -> msg.take(280) + "…"
            else -> msg
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppUpdateViewModel::class.java)) {
                return AppUpdateViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel")
        }
    }

    private companion object {
        const val FOREGROUND_CHECK_DEBOUNCE_MS = 10_000L
    }
}
