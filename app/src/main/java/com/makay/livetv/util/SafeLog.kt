package com.makay.livetv.util

import android.util.Log
import com.makay.livetv.BuildConfig

/**
 * Logcat gurultusunu azaltan ince sarmalayici.
 * Release derlemede debug/info seviyeleri tamamen susturulur; hata
 * seviyesi yalnizca yigin izi olmadan tek satir olarak yazilir.
 */
object SafeLog {

    private const val MAX_TAG = 23

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(safeTag(tag), message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(safeTag(tag), message)
    }

    fun w(tag: String, message: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.w(safeTag(tag), message, tr)
        } else if (tr != null) {
            Log.w(safeTag(tag), "$message: ${tr.javaClass.simpleName}: ${tr.message}")
        }
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        if (tr == null) {
            Log.e(safeTag(tag), message)
        } else {
            Log.e(safeTag(tag), "$message: ${tr.javaClass.simpleName}: ${tr.message}")
        }
    }

    private fun safeTag(tag: String): String =
        if (tag.length <= MAX_TAG) tag else tag.substring(0, MAX_TAG)
}
