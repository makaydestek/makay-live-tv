package com.makay.livetv

import android.annotation.SuppressLint
import android.content.Intent
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.makay.livetv.data.AppSettingsStore
import com.makay.livetv.receiver.TvAlarmReceiver
import com.makay.livetv.timer.TimerEvent
import com.makay.livetv.timer.TimerEventBus
import com.makay.livetv.ui.AppNavigator
import com.makay.livetv.ui.AppRoot
import com.makay.livetv.ui.LiveTvViewModel
import com.makay.livetv.ui.theme.MakayLiveTvTheme

/**
 * Tek aktivite / tek giris noktasi.
 *
 * TV'de ekran donmez; `screenOrientation=landscape` manifesttir. Activity
 * `configChanges` ile yeniden olusturulmaz → `TvView` oturumu kanal
 * degisiminde kesintiye ugramaz.
 *
 * Zamanlayici: `TvAlarmReceiver` bildirime basinca (veya surec zaten
 * ayaktaysa) ayni intent action'u ile buraya ulasir; [onNewIntent] olayi
 * [TimerEventBus]'a gecirir, `AppRoot` hatirlatma penceresini acar.
 */
class MainActivity : ComponentActivity() {

    private var liveTvRemoteHandler: ((KeyEvent) -> Boolean)? = null

    fun setLiveTvRemoteHandler(handler: ((KeyEvent) -> Boolean)?) {
        liveTvRemoteHandler = handler
    }

    /** Enters the system PiP window when this TV device supports it. */
    fun enterLivePictureInPicture(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) return false
        return runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }.getOrDefault(false)
    }

    // RestrictedApi: super.dispatchKeyEvent androidx.activity'te tanimlidir;
    // kumanda tuslarini Compose'e ulastirmak icin override zorunlu ve guvenli.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (liveTvRemoteHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dispatchTimerIntent(intent)

        val settings = AppSettingsStore.getInstance(this)

        setContent {
            MakayLiveTvTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        val navigator = remember { AppNavigator() }
                        val viewModel: LiveTvViewModel = viewModel()
                        AppRoot(viewModel = viewModel, navigator = navigator)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchTimerIntent(intent)
    }

    /** Bildirim/intent ile gelen zamanlayici olayini yayina yazar. */
    private fun dispatchTimerIntent(intent: Intent?) {
        if (intent?.action != ACTION_TIMER_EVENT) return
        val channelId = intent.getLongExtra(TvAlarmReceiver.EXTRA_CHANNEL_ID, -1L)
        if (channelId < 0) return
        // emit hem canli yayini doldurur hem pending'i tutar; UI ister akistadan
        // ister baslangictaki pending'den okur (iki kez islenmez: consumePending).
        TimerEventBus.emit(
            TimerEvent(
                timerId = intent.getLongExtra(TvAlarmReceiver.EXTRA_TIMER_ID, -1L),
                channelId = channelId,
                channelNumber = "",
                channelName = "",
                programTitle = intent.getStringExtra(TvAlarmReceiver.EXTRA_PROGRAM_TITLE).orEmpty(),
                isRecord = intent.getBooleanExtra(TvAlarmReceiver.EXTRA_IS_RECORD, false),
            ),
        )
    }

    companion object {
        const val ACTION_TIMER_EVENT = "com.makay.livetv.ACTION_TIMER_EVENT"
    }
}
