package com.nexorith.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class ConcurrencyControllerTest {

    private fun controller(max: Int = 8, initial: Int = 2) =
        ConcurrencyController(max = max, initial = initial, startMs = 0, holdMs = 3_000)

    @Test
    fun startsAtTheInitialCountClampedToTheMaximum() {
        assertEquals(2, controller().target)
        assertEquals(1, controller(max = 1, initial = 2).target)
        assertEquals(1, controller(max = 8, initial = 0).target)
    }

    @Test
    fun waitsForTheWarmUpBeforeChangingAnything() {
        val c = controller()
        c.evaluate(nowMs = 1_000, bytesPerSecond = 1_000_000)
        assertEquals(2, c.target)
    }

    @Test
    fun addsConnectionsWhileThroughputKeepsGrowing() {
        val c = controller()
        c.evaluate(3_000, 1_000_000)  // first measurement at 2 connections -> try 3
        assertEquals(3, c.target)
        c.evaluate(4_000, 1_400_000)  // still in the hold period of the last change
        assertEquals(3, c.target)
        c.evaluate(6_000, 1_400_000)  // +40 % -> try 4
        assertEquals(4, c.target)
        c.evaluate(9_000, 1_900_000)  // +36 % -> try 5
        assertEquals(5, c.target)
    }

    @Test
    fun staysAtTheLevelWhereMoreConnectionsStopHelping() {
        val c = controller()
        c.evaluate(3_000, 1_000_000)
        assertEquals(3, c.target)
        c.evaluate(6_000, 1_050_000)  // only +5 %: stop here
        assertEquals(3, c.target)
        c.evaluate(9_000, 5_000_000)  // settled: later measurements change nothing
        c.evaluate(12_000, 9_000_000)
        assertEquals(3, c.target)
    }

    @Test
    fun goesBackOneLevelWhenThroughputDropped() {
        val c = controller()
        c.evaluate(3_000, 1_000_000)  // 2 -> 3
        c.evaluate(6_000, 800_000)    // 20 % worse
        assertEquals(2, c.target)
    }

    @Test
    fun neverExceedsTheMaximum() {
        val c = controller(max = 3, initial = 2)
        c.evaluate(3_000, 1_000_000)
        assertEquals(3, c.target)
        c.evaluate(6_000, 2_000_000)
        assertEquals(3, c.target)
    }

    @Test
    fun ignoresZeroThroughput() {
        val c = controller()
        c.evaluate(3_000, 0)
        assertEquals(2, c.target)
    }

    @Test
    fun throttlingHalvesTheCountOnceAndStopsRamping() {
        val c = controller(max = 8, initial = 8)
        c.onThrottled(10_000)
        assertEquals(4, c.target)
        c.onThrottled(10_500) // same burst of 429 answers: counts once
        assertEquals(4, c.target)
        c.onThrottled(13_000) // a new burst
        assertEquals(2, c.target)
        c.evaluate(20_000, 5_000_000) // settled: no ramp-up after throttling
        assertEquals(2, c.target)
    }

    @Test
    fun throttlingNeverGoesBelowOne() {
        val c = controller(max = 2, initial = 1)
        c.onThrottled(10_000)
        c.onThrottled(20_000)
        assertEquals(1, c.target)
    }
}
