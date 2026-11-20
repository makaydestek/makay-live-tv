package com.makay.livetv.ui

import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.ChannelFilter
import com.makay.livetv.domain.model.ChannelSort
import com.makay.livetv.domain.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvViewModelFilterTest {

    private fun channel(
        id: Long,
        number: String,
        name: String,
        type: ChannelType = ChannelType.TERRESTRIAL,
        favorite: Boolean = false,
        videoFormat: String? = "1920x1080",
        serviceType: String = "A/V",
        browsable: Boolean = true,
    ) = Channel(
        id = id,
        inputId = "org.dtvkit.inputsource/.Service",
        inputLabel = "DroidLogic DVB",
        displayNumber = number,
        displayName = name,
        type = type,
        isFavorite = favorite,
        videoFormat = videoFormat,
        serviceType = serviceType,
        isBrowsable = browsable,
    )

    @Test
    fun `acilista silinen gizli ve kilitli kanallar secilmez`() {
        val deleted = channel(1, "1", "Silinen").copy(isLocallyDeleted = true)
        val hidden = channel(2, "2", "Gizli").copy(isLocallyHidden = true)
        val locked = channel(3, "3", "Kilitli").copy(isLocallyLocked = true)
        val playable = channel(4, "4", "Acik")
        assertEquals(
            playable,
            LiveTvViewModel.startupChannel(listOf(deleted, hidden, locked, playable), ChannelSort.NUMBER, deleted.id),
        )
        assertNull(LiveTvViewModel.startupChannel(listOf(deleted, hidden, locked), ChannelSort.NUMBER))
    }

    @Test
    fun `kanal gecisi kilitli ve atlanacak kanallari iki yonde gecer`() {
        val first = channel(1, "1", "Bir")
        val locked = channel(2, "2", "Kilitli").copy(isLocallyLocked = true)
        val skipped = channel(3, "3", "Atlanan").copy(isLocallySkipped = true)
        val last = channel(4, "4", "Dort")
        val channels = listOf(first, locked, skipped, last)
        assertEquals(last, LiveTvViewModel.findZapTarget(channels, first.id, 1))
        assertEquals(first, LiveTvViewModel.findZapTarget(channels, last.id, -1))
        assertNull(LiveTvViewModel.findZapTarget(listOf(locked), null, 1))
    }

    @Test
    fun `numara siralamasi dogal sayisal sirada olmali`() {
        val list = listOf(
            channel(1, "10", "On"),
            channel(2, "7.2", "Yedi-Iki"),
            channel(3, "2", "Iki"),
            channel(4, "101", "Yuz Bir"),
        )
        val sorted = LiveTvViewModel.filterChannels(
            list, ChannelFilter.ALL, ChannelSort.NUMBER, "",
        )
        assertEquals(listOf("2", "7.2", "10", "101"), sorted.map { it.displayNumber })
    }

    @Test
    fun `sayisal olmayan numaralar sona atilmali`() {
        val list = listOf(
            channel(1, "HDMI1", "Harici"),
            channel(2, "3", "Uc"),
        )
        val sorted = LiveTvViewModel.filterChannels(
            list, ChannelFilter.ALL, ChannelSort.NUMBER, "",
        )
        assertEquals("3", sorted.first().displayNumber)
    }

    @Test
    fun `favori filtresi yalnizca favorileri getirmeli`() {
        val list = listOf(
            channel(1, "1", "A", favorite = true),
            channel(2, "2", "B", favorite = false),
        )
        val result = LiveTvViewModel.filterChannels(
            list, ChannelFilter.FAVORITES, ChannelSort.NUMBER, "",
        )
        assertEquals(1, result.size)
        assertEquals("A", result.first().displayName)
    }

    @Test
    fun `arama isim ve numarada eslesmeli ve kucuk-buyuk harf duyarsiz olmali`() {
        val list = listOf(
            channel(1, "1", "TRT 1"),
            channel(2, "2", "ATV"),
            channel(3, "45", "Kanal D"),
        )
        assertEquals(1, LiveTvViewModel.filterChannels(list, ChannelFilter.ALL, ChannelSort.NUMBER, "trt").size)
        assertEquals(1, LiveTvViewModel.filterChannels(list, ChannelFilter.ALL, ChannelSort.NUMBER, "45").size)
        assertEquals(0, LiveTvViewModel.filterChannels(list, ChannelFilter.ALL, ChannelSort.NUMBER, "yok").size)
    }

    @Test
    fun `browsable olmayan kanallar hicbir filtrede gorunmemeli`() {
        val list = listOf(channel(1, "1", "Gizli", browsable = false))
        val result = LiveTvViewModel.filterChannels(list, ChannelFilter.ALL, ChannelSort.NUMBER, "")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `HD filtresi kanal adindaki HD etiketini yakalamali`() {
        val list = listOf(
            channel(1, "1", "TRT 1 HD"),
            channel(2, "2", "ATV", videoFormat = "720x576"),
        )
        val result = LiveTvViewModel.filterChannels(list, ChannelFilter.HD, ChannelSort.NUMBER, "")
        assertEquals(listOf("TRT 1 HD"), result.map { it.displayName })
    }

    @Test
    fun `radyo filtresi yalnizca ses kanallarini getirmeli`() {
        val list = listOf(
            channel(1, "1", "TRT 3", serviceType = "DIGITAL RADIO", videoFormat = null),
            channel(2, "2", "ATV"),
        )
        val result = LiveTvViewModel.filterChannels(list, ChannelFilter.RADIO, ChannelSort.NUMBER, "")
        assertEquals(listOf("TRT 3"), result.map { it.displayName })
    }

    @Test
    fun `isim siralamasi harf sirasina gore olmali`() {
        val list = listOf(
            channel(1, "3", "Cem TV"),
            channel(2, "1", "Atv"),
            channel(3, "2", "Beyaz TV"),
        )
        val sorted = LiveTvViewModel.filterChannels(list, ChannelFilter.ALL, ChannelSort.NAME, "")
        assertEquals(listOf("Atv", "Beyaz TV", "Cem TV"), sorted.map { it.displayName })
    }
}
