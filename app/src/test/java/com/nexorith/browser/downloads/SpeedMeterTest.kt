package com.nexorith.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun noSpeedUntilTwoSamples() {
        val meter = SpeedMeter()
        assertEquals(0, meter.bytesPerSecond())
        meter.record(0, 0)
        assertEquals(0, meter.bytesPerSecond())
    }

    @Test
    fun speedIsBytesReceivedDividedByRealTime() {
        val meter = SpeedMeter()
        meter.record(0, 0)
        meter.record(1_000, 500_000)
        assertEquals(500_000, meter.bytesPerSecond())
        meter.record(2_000, 1_500_000)
        assertEquals(750_000, meter.bytesPerSecond())
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val meter = SpeedMeter(windowMs = 3_000)
        meter.record(0, 0)
        meter.record(1_000, 10_000_000) // a burst early on
        meter.record(10_000, 10_000_000)
        meter.record(11_000, 10_001_000)
        // Only the last seconds count: 1 000 bytes in 1 s.
        assertEquals(1_000, meter.bytesPerSecond())
    }

    @Test
    fun stalledTransferReportsZero() {
        val meter = SpeedMeter()
        meter.record(0, 1_000)
        meter.record(2_000, 1_000)
        assertEquals(0, meter.bytesPerSecond())
    }

    @Test
    fun resetForgetsSamples() {
        val meter = SpeedMeter()
        meter.record(0, 0)
        meter.record(1_000, 1_000)
        meter.reset()
        assertEquals(0, meter.bytesPerSecond())
    }

    @Test
    fun etaRoundsUpAndNeedsASpeed() {
        assertEquals(10L, SpeedMeter.etaSeconds(1_000, 100))
        assertEquals(11L, SpeedMeter.etaSeconds(1_001, 100))
        assertEquals(0L, SpeedMeter.etaSeconds(0, 100))
        assertNull(SpeedMeter.etaSeconds(1_000, 0))
        assertNull(SpeedMeter.etaSeconds(-1, 100))
    }
}
