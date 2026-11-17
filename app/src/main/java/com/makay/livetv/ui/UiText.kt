package com.makay.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.makay.livetv.R
import com.makay.livetv.domain.model.ChannelFilter
import com.makay.livetv.domain.model.ChannelSort

/**
 * UI metinleri icin tek erisim noktasi.
 *
 * Neden ayri katman? Ekranlarda `stringResource` cagrilarini serpiştirmek
 * yerine anlamli fonksiyonlar sunar; boylece ceviri eksikligi derleme
 * zamaninda yakalanir ve testlerde sabit metne bagimlilik kalmaz.
 */
object UiText {

    @Composable
    fun filterLabel(filter: ChannelFilter): String = when (filter) {
        ChannelFilter.ALL -> stringResource(R.string.filter_all)
        ChannelFilter.FAVORITES -> stringResource(R.string.filter_favorites)
        ChannelFilter.RADIO -> stringResource(R.string.filter_radio)
        ChannelFilter.HD -> stringResource(R.string.filter_hd)
        ChannelFilter.HIDDEN -> stringResource(R.string.filter_hidden)
        ChannelFilter.RECENT -> stringResource(R.string.filter_recent)
    }

    @Composable
    fun sortLabel(sort: ChannelSort): String = when (sort) {
        ChannelSort.NUMBER -> stringResource(R.string.sort_number)
        ChannelSort.NAME -> stringResource(R.string.sort_name)
        ChannelSort.CUSTOM -> stringResource(R.string.sort_custom)
    }

    @Composable
    fun themeLabel(mode: com.makay.livetv.ui.theme.ThemeMode): String = when (mode) {
        com.makay.livetv.ui.theme.ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
        com.makay.livetv.ui.theme.ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
        com.makay.livetv.ui.theme.ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    }
}
