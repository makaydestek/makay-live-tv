package com.makay.livetv.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Uygulama ekranlari.
 *
 * Tek back stack ile gezinme: TV'de geri tusu her zaman stack'ten pop eder.
 * Canli TV ekrani ([LiveTv]) her zaman koktadir; kanal listesi, EPG ve
 * ayarlar onun uzerine acilan tam ekran bindirmelerdir.
 */
sealed class AppScreen {
    /** Canli yayin + bilgi bari. Kok ekran. */
    data object LiveTv : AppScreen()

    /** Tam ekran kanal listesi (Menu / OK tusu). */
    data object ChannelList : AppScreen()

    /** TV menu and recording drawer with top tabs. */
    data object MainMenu : AppScreen()

    /** TV Rehberi (EPG izgarasi). */
    data object Epg : AppScreen()

    /** Ayarlar. */
    data object Settings : AppScreen()

    /** Oynatma secenekleri (goruntu modu, ses/altyazi izi). */
    data object Options : AppScreen()

    /** Zamanlayici hatirlatmalarinin yonetimi. */
    data object Timers : AppScreen()

    /** TvProvider'dan okunan kayitlar. */
    data object Recordings : AppScreen()

    /** Ilk kurulum rehberi. */
    data object FirstLaunch : AppScreen()
}

/**
 * Tek back stack ile ekran gezinmesi (MakayCleaner `AppNavigator` deseni).
 */
class AppNavigator(start: AppScreen = AppScreen.LiveTv) {
    private val stack: SnapshotStateList<AppScreen> = mutableStateListOf(start)

    val current: AppScreen
        get() = stack.lastOrNull() ?: AppScreen.LiveTv

    val canPop: Boolean
        get() = stack.size > 1

    /** Compose recomposition tetiklemek icin stack boyutu. */
    val depth: Int
        get() = stack.size

    fun navigate(screen: AppScreen) {
        if (stack.lastOrNull() == screen) return
        stack.add(screen)
    }

    /** Ayni ekrani yeniden basmadan degistirir. */
    fun replace(screen: AppScreen) {
        if (stack.isEmpty()) {
            stack.add(screen)
            return
        }
        stack[stack.lastIndex] = screen
    }

    fun resetTo(screen: AppScreen) {
        stack.clear()
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popTo(screen: AppScreen): Boolean {
        val idx = stack.indexOfLast { it == screen }
        if (idx < 0) return false
        while (stack.lastIndex > idx) stack.removeAt(stack.lastIndex)
        return true
    }
}
