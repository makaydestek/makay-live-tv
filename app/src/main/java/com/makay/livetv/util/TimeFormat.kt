package com.makay.livetv.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * EPG ve bilgi barlarinda kullanilan tarih/sure bicimlendirme.
 *
 * Neden sabit bicim? EPG zamanlari **UTC** gelir (TvContract standardi);
 * kullaniciya **yerel saat** gosterilmelidir. `SimpleDateFormat` her cagrida
 * yeni nesne uretmemesi icin thread-local tutulur.
 */
object TimeFormat {

    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()) }
    private val weekdayFmt = ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.getDefault()) }
    private val fullFmt = ThreadLocal.withInitial { SimpleDateFormat("d MMMM, EEEE", Locale.getDefault()) }

    /** "20:30" */
    fun time(utcMillis: Long): String = timeFmt.get()!!.format(Date(utcMillis))

    /** "12 Kas" */
    fun day(utcMillis: Long): String = dayFmt.get()!!.format(Date(utcMillis))

    /** "Çar" */
    fun weekday(utcMillis: Long): String = weekdayFmt.get()!!.format(Date(utcMillis))

    /** "12 Kasım, Çarşamba" */
    fun fullDate(utcMillis: Long): String = fullFmt.get()!!.format(Date(utcMillis))

    /** "20:30 – 21:15" */
    fun range(startUtc: Long, endUtc: Long): String = "${time(startUtc)} – ${time(endUtc)}"

    /**
     * Kisa sure: "45 dk", "1 sa 20 dk", "2 sa".
     * 1 dakikadan kisa sureler "< 1 dk" doner.
     */
    fun duration(millis: Long): String {
        if (millis <= 0L) return ""
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        if (totalMinutes < 1L) return "< 1 dk"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "$minutes dk"
            minutes == 0L -> "$hours sa"
            else -> "$hours sa $minutes dk"
        }
    }

    /** Programin kalan suresi: "12 dk kaldı". Bittiyse bos doner. */
    fun remaining(endUtc: Long, nowUtc: Long): String {
        val left = endUtc - nowUtc
        if (left <= 0L) return ""
        return "${duration(left)} kaldı"
    }

    /**
     * EPG izgarasi icin gun baslangici (yerel 00:00) — **UTC** milisaniye.
     * TvContract programlari UTC sakladigi icin bu cevirim kritiktir.
     */
    fun startOfDayUtc(dayOffset: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    /** Bugun / Yarin / "Çar 12 Kas" etiketi. */
    fun relativeDayLabel(dayStartLocalMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val today = startOfDayUtc(0)
        val dayLength = TimeUnit.DAYS.toMillis(1)
        val diff = ((dayStartLocalMillis - today) / dayLength).toInt()
        return when (diff) {
            0 -> "Bugün"
            1 -> "Yarın"
            -1 -> "Dün"
            else -> "${weekday(dayStartLocalMillis)} ${day(dayStartLocalMillis)}"
        }
    }

    /** "5 dk önce" tarzi goreli zaman (son izlenenler listesi). */
    fun relativePast(pastMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = (nowMillis - pastMillis).coerceAtLeast(0L)
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "az önce"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} dk önce"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} sa önce"
            else -> "${TimeUnit.MILLISECONDS.toDays(diff)} gün önce"
        }
    }
}
