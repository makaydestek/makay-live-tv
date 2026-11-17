package com.makay.livetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigatorTest {

    @Test
    fun `baslangic kok ekranda olmali`() {
        val nav = AppNavigator()
        assertEquals(AppScreen.LiveTv, nav.current)
        assertFalse(nav.canPop)
    }

    @Test
    fun `navigate stack derinligini arttirmali`() {
        val nav = AppNavigator()
        nav.navigate(AppScreen.ChannelList)
        assertEquals(AppScreen.ChannelList, nav.current)
        assertTrue(nav.canPop)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `ayni ekrani ust uste eklememeli`() {
        val nav = AppNavigator()
        nav.navigate(AppScreen.Settings)
        nav.navigate(AppScreen.Settings)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `kok ekrana pop yalnizca usttekini kaldirmali`() {
        val nav = AppNavigator()
        nav.navigate(AppScreen.Epg)
        nav.navigate(AppScreen.Settings)
        assertTrue(nav.pop())
        assertEquals(AppScreen.Epg, nav.current)
        assertTrue(nav.pop())
        assertEquals(AppScreen.LiveTv, nav.current)
    }

    @Test
    fun `popTo hedefin ustunu temizlemeli`() {
        val nav = AppNavigator()
        nav.navigate(AppScreen.ChannelList)
        nav.navigate(AppScreen.Epg)
        nav.navigate(AppScreen.Settings)
        nav.popTo(AppScreen.ChannelList)
        assertEquals(AppScreen.ChannelList, nav.current)
        assertEquals(2, nav.depth)
    }

    @Test
    fun `kok ekranda pop islemsiz kalmali`() {
        val nav = AppNavigator()
        assertFalse(nav.pop())
        assertEquals(AppScreen.LiveTv, nav.current)
    }
}
