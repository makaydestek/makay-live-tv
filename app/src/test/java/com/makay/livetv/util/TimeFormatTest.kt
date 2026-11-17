package com.makay.livetv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `sure bicimlendirme`() {
        assertEquals("< 1 dk", TimeFormat.duration(30_000L))
        assertEquals("1 dk", TimeFormat.duration(60_000L))
        assertEquals("45 dk", TimeFormat.duration(45 * 60_000L))
        assertEquals("1 sa", TimeFormat.duration(60 * 60_000L))
        assertEquals("1 sa 20 dk", TimeFormat.duration(80 * 60_000L))
    }

    @Test
    fun `sifir veya negatif sure bos donmeli`() {
        assertEquals("", TimeFormat.duration(0L))
        assertEquals("", TimeFormat.duration(-5L))
    }

    @Test
    fun `kalan sure bitmis programda bos donmeli`() {
        assertEquals("", TimeFormat.remaining(1_000L, 2_000L))
    }

    @Test
    fun `kalan sure gelecekteki programda dolu olmali`() {
        val text = TimeFormat.remaining(endUtc = 10 * 60_000L, nowUtc = 0L)
        assertTrue(text.contains("kaldı"))
        assertTrue(text.contains("10"))
    }

    @Test
    fun `gun baslangici gece yarisi olmali`() {
        val start = TimeFormat.startOfDayUtc(0)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(java.util.Calendar.MINUTE))
        assertEquals(0, cal.get(java.util.Calendar.SECOND))
    }

    @Test
    fun `dun bugun yarin etiketleri`() {
        assertEquals("Bugün", TimeFormat.relativeDayLabel(TimeFormat.startOfDayUtc(0)))
        assertEquals("Yarın", TimeFormat.relativeDayLabel(TimeFormat.startOfDayUtc(1)))
        assertEquals("Dün", TimeFormat.relativeDayLabel(TimeFormat.startOfDayUtc(-1)))
    }
}
