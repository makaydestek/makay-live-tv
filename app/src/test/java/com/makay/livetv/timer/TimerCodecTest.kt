package com.makay.livetv.timer

import com.makay.livetv.domain.model.TimerEntry
import com.makay.livetv.domain.model.TimerStatus
import com.makay.livetv.domain.model.TimerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerCodecTest {

    private fun entry(
        id: Long = 100L,
        type: TimerType = TimerType.REMIND,
        status: TimerStatus = TimerStatus.SCHEDULED,
        repeatWeekly: Boolean = false,
        programId: Long? = 4242L,
        channelName: String = "TRT 1",
        programTitle: String = "Ana Haber",
    ) = TimerEntry(
        id = id,
        channelId = 7L,
        channelNumber = "1",
        channelName = channelName,
        inputId = "com.droidlogic.tvinput/.DroidLogicTvInputService",
        programId = programId,
        programTitle = programTitle,
        startUtcMillis = 1_700_000_000_000L,
        endUtcMillis = 1_700_000_300_000L,
        type = type,
        repeatWeekly = repeatWeekly,
        status = status,
    )

    @Test
    fun `encode decode tur dondurmesi`() {
        val timers = listOf(
            entry(),
            entry(id = 101L, type = TimerType.RECORD, status = TimerStatus.FIRED, repeatWeekly = true),
            entry(id = 102L, programId = null, programTitle = "Reklamsız"),
        )

        val decoded = TimerCodec.decode(TimerCodec.encode(timers))

        assertEquals(timers, decoded)
    }

    @Test
    fun `ayrac iceren degerler alan sayisini bozmaz`() {
        val tricky = entry(
            channelName = "Kanal | Adı",
            programTitle = "Program\nBaşlığı\rSonu",
        )

        val decoded = TimerCodec.decode(TimerCodec.encode(listOf(tricky)))

        assertEquals(1, decoded.size)
        assertEquals("Kanal / Adı", decoded[0].channelName)
        assertEquals("Program Başlığı Sonu", decoded[0].programTitle)
        assertEquals(tricky.id, decoded[0].id)
    }

    @Test
    fun `eski 11 alanli satir tekrar haftalik alani olmadan okunur`() {
        // Eski surumden kalan satir: repeatWeekly alani yok.
        val legacy = "5|9|input|3|Kanal|77|Eski Program|1000|2000|REMIND|SCHEDULED"

        val decoded = TimerCodec.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals(5L, decoded[0].id)
        assertEquals(77L, decoded[0].programId)
        assertEquals(TimerType.REMIND, decoded[0].type)
        assertEquals(TimerStatus.SCHEDULED, decoded[0].status)
        assertEquals(false, decoded[0].repeatWeekly)
    }

    @Test
    fun `gecersiz satirlar atlanir gecerli satirlar korunur`() {
        val raw = listOf(
            "",
            "bozuk-satir",
            "abc|1|i|1|k|1|p|1|2|REMIND|SCHEDULED|false", // id sayi degil
            "9|1|i|1|k|1|p|xx|2|REMIND|SCHEDULED|false",   // baslangic sayi degil
            entry(id = 200L).let { TimerCodec.encode(listOf(it)) },
        ).joinToString("\n")

        val decoded = TimerCodec.decode(raw)

        assertEquals(1, decoded.size)
        assertEquals(200L, decoded[0].id)
    }

    @Test
    fun `bos girdi bos liste dondurur`() {
        assertTrue(TimerCodec.decode("").isEmpty())
        assertTrue(TimerCodec.decode("\n\n").isEmpty())
    }

    @Test
    fun `programId yoksa alan bos kalir ve null okunur`() {
        val decoded = TimerCodec.decode(TimerCodec.encode(listOf(entry(programId = null))))

        assertEquals(1, decoded.size)
        assertNull(decoded[0].programId)
    }
}
