package com.nexorith.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormattersTest {
    private val us = Locale.US

    @Test
    fun bytes() {
        assertEquals("0 B", Formatters.bytes(0, us))
        assertEquals("1023 B", Formatters.bytes(1023, us))
        assertEquals("1.0 KB", Formatters.bytes(1024, us))
        assertEquals("1.5 KB", Formatters.bytes(1536, us))
        assertEquals("5.0 MB", Formatters.bytes(5L * 1024 * 1024, us))
        assertEquals("150 MB", Formatters.bytes(150L * 1024 * 1024, us))
        assertEquals("1.0 GB", Formatters.bytes(1024L * 1024 * 1024, us))
    }

    @Test
    fun speedUsesKbBelowOneMbAndMbAbove() {
        assertEquals("0 KB/s", Formatters.speed(0, us))
        assertEquals("<1 KB/s", Formatters.speed(500, us))
        assertEquals("2 KB/s", Formatters.speed(2048, us))
        assertEquals("1.5 MB/s", Formatters.speed(1536L * 1024, us))
    }

    @Test
    fun duration() {
        assertEquals("0s", Formatters.duration(0))
        assertEquals("45s", Formatters.duration(45))
        assertEquals("1m 00s", Formatters.duration(60))
        assertEquals("4m 12s", Formatters.duration(252))
        assertEquals("1h 05m", Formatters.duration(3900))
    }
}
