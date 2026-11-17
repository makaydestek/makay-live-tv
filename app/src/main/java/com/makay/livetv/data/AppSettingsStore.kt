package com.makay.livetv.data

import android.content.Context
import android.content.SharedPreferences
import com.makay.livetv.player.AspectMode
import com.makay.livetv.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tum kullanici tercihleri tek yerde.
 *
 * `AppSettingsStore` deseniyle uyumlu: `SharedPreferences` uzerinde
 * `StateFlow` yayinlari tutulur, boylece Compose dogrudan toplayabilir.
 * Kanal/EPG verisi burada **tutulmaz** (onun kaynagi TvProvider'dir).
 */
class AppSettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("livetv_settings", Context.MODE_PRIVATE)

    // ---- Tema ----
    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // ---- Oynatma ----
    private val _startWithLastChannel = MutableStateFlow(prefs.getBoolean(KEY_START_LAST, true))
    val startWithLastChannel: StateFlow<Boolean> = _startWithLastChannel.asStateFlow()

    private val _showInfoBanner = MutableStateFlow(prefs.getBoolean(KEY_INFO_BANNER, true))
    val showInfoBanner: StateFlow<Boolean> = _showInfoBanner.asStateFlow()

    private val _bannerTimeoutMs = MutableStateFlow(prefs.getLong(KEY_BANNER_TIMEOUT, 3_000L))
    val bannerTimeoutMs: StateFlow<Long> = _bannerTimeoutMs.asStateFlow()

    private val _showChannelLogos = MutableStateFlow(prefs.getBoolean(KEY_LOGOS, true))
    val showChannelLogos: StateFlow<Boolean> = _showChannelLogos.asStateFlow()

    private val _aspectModeId = MutableStateFlow(
        prefs.getString(KEY_ASPECT, AspectMode.FULL_16_9.id) ?: AspectMode.FULL_16_9.id,
    )
    val aspectModeId: StateFlow<String> = _aspectModeId.asStateFlow()

    private val _fontScale = MutableStateFlow(prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.9f, 1.25f))
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    // ---- Tuner ----
    private val _preferredInputId = MutableStateFlow(prefs.getString(KEY_INPUT_ID, null))
    val preferredInputId: StateFlow<String?> = _preferredInputId.asStateFlow()

    // ---- Ilk kurulum ----
    private val _firstLaunchDone = MutableStateFlow(prefs.getBoolean(KEY_FIRST_LAUNCH, false))
    val firstLaunchDone: StateFlow<Boolean> = _firstLaunchDone.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setStartWithLastChannel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_LAST, enabled).apply()
        _startWithLastChannel.value = enabled
    }

    fun setShowInfoBanner(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INFO_BANNER, enabled).apply()
        _showInfoBanner.value = enabled
    }

    fun setBannerTimeout(ms: Long) {
        val clamped = ms.coerceIn(2_000L, 15_000L)
        prefs.edit().putLong(KEY_BANNER_TIMEOUT, clamped).apply()
        _bannerTimeoutMs.value = clamped
    }

    fun setShowChannelLogos(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOGOS, enabled).apply()
        _showChannelLogos.value = enabled
    }

    fun setAspectMode(mode: AspectMode) {
        prefs.edit().putString(KEY_ASPECT, mode.id).apply()
        _aspectModeId.value = mode.id
    }

    fun setFontScale(scale: Float) {
        val value = scale.coerceIn(0.9f, 1.25f)
        prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()
        _fontScale.value = value
    }

    fun setPreferredInputId(inputId: String?) {
        prefs.edit().apply {
            if (inputId.isNullOrBlank()) remove(KEY_INPUT_ID) else putString(KEY_INPUT_ID, inputId)
        }.apply()
        _preferredInputId.value = inputId
    }

    fun setFirstLaunchDone(done: Boolean = true) {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, done).apply()
        _firstLaunchDone.value = done
    }

    private fun readThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_START_LAST = "start_with_last_channel"
        private const val KEY_INFO_BANNER = "show_info_banner"
        private const val KEY_BANNER_TIMEOUT = "banner_timeout_ms"
        private const val KEY_LOGOS = "show_channel_logos"
        private const val KEY_ASPECT = "aspect_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_INPUT_ID = "preferred_input_id"
        private const val KEY_FIRST_LAUNCH = "first_launch_done"

        @Volatile
        private var instance: AppSettingsStore? = null

        fun getInstance(context: Context): AppSettingsStore =
            instance ?: synchronized(this) {
                instance ?: AppSettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
