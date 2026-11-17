package com.makay.livetv.epg

import com.makay.livetv.domain.model.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramProgressTest {

    private fun program(start: Long, end: Long) = Program(
        id = 1L,
        channelId = 10L,
        title = "Test",
        startTimeUtcMillis = start,
        endTimeUtcMillis = end,
    )

    @Test
    fun `sure negatifse sifir donmeli`() {
        assertEquals(0L, program(2_000L, 1_000L).durationMillis)
    }

    @Test
    fun `progress baslangicta sifir sonda bir olmali`() {
        val p = program(1_000L, 3_000L)
        assertEquals(0f, p.progressAt(1_000L), 0.001f)
        assertEquals(1f, p.progressAt(3_000L), 0.001f)
        assertEquals(0.5f, p.progressAt(2_000L), 0.001f)
    }

    @Test
    fun `progress aralik disinda kirpilmali`() {
        val p = program(1_000L, 3_000L)
        assertEquals(0f, p.progressAt(0L), 0.001f)
        assertEquals(1f, p.progressAt(99_000L), 0.001f)
    }

    @Test
    fun `canli program yalnizca aralik icinde true olmali`() {
        val p = program(1_000L, 3_000L)
        assertTrue(p.isLiveAt(1_000L))
        assertTrue(p.isLiveAt(2_500L))
        assertFalse(p.isLiveAt(3_000L))
        assertFalse(p.isLiveAt(999L))
    }

    @Test
    fun `gecmis ve gelecek program ayrimi dogru olmali`() {
        val p = program(10_000L, 20_000L)
        assertTrue(p.isFinishedBy(20_001L))
        assertTrue(p.isUpcomingAt(9_999L))
    }
}
