package com.makay.livetv

import android.app.Application
import com.makay.livetv.util.SafeLog

/**
 * Uygulama baslangic noktasi.
 *
 * Burada yalnizca ucuz islemler yapilir (kanal/EPG yuklemesi ViewModel'e
 * aittir). Amac: TV acilisinda siyah ekran suresini minimumda tutmak.
 */
class MakayLiveTvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SafeLog.i(TAG, "Makay Live TV ${BuildConfig.VERSION_NAME} başlatıldı")
    }

    companion object {
        private const val TAG = "MakayLiveTvApp"
    }
}
