package com.nexorith.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMapTest {

    @Test
    fun chunkBounds() {
        val map = SegmentMap(total = 25, chunkSize = 10)
        assertEquals(3, map.count)
        assertEquals(listOf(10L, 10L, 5L), (0 until 3).map { map.length(it) })
        assertEquals(listOf(0L, 10L, 20L), (0 until 3).map { map.start(it) })
        assertEquals(listOf(9L, 19L, 24L), (0 until 3).map { map.end(it) })
    }

    @Test
    fun emptyFileHasNoChunks() {
        val map = SegmentMap(0, 10)
        assertEquals(0, map.count)
        assertTrue(map.isComplete())
        assertFalse(map.hasUnclaimed())
        assertNull(map.claim())
    }

    @Test
    fun claimGivesEachChunkOnce() {
        val map = SegmentMap(25, 10)
        assertEquals(0, map.claim())
        assertEquals(1, map.claim())
        assertEquals(2, map.claim())
        assertNull(map.claim())
        assertFalse(map.hasUnclaimed())
    }

    @Test
    fun finishedChunksAreNotClaimed() {
        val map = SegmentMap(25, 10)
        map.add(0, 10)
        map.add(1, 4)
        assertTrue(map.isDone(0))
        assertFalse(map.isDone(1))
        assertEquals(1, map.claim()) // the half-done chunk is claimed, chunk 0 is skipped
        assertEquals(4L, map.receivedIn(1))
        assertEquals(14L, map.totalReceived())
    }

    @Test
    fun receivedNeverExceedsTheChunkLength() {
        val map = SegmentMap(25, 10)
        map.add(2, 99)
        assertEquals(5L, map.receivedIn(2))
    }

    @Test
    fun completeWhenEveryChunkIsFull() {
        val map = SegmentMap(25, 10)
        map.add(0, 10); map.add(1, 10)
        assertFalse(map.isComplete())
        map.add(2, 5)
        assertTrue(map.isComplete())
        assertEquals(25L, map.totalReceived())
    }

    @Test
    fun encodeAndDecodeKeepEveryByte() {
        val map = SegmentMap(total = 100, chunkSize = 10)
        for (i in 0..3) map.add(i, 10) // chunks 0-3 done
        map.add(5, 7)                  // chunk 5 partial
        map.add(7, 10)                 // chunk 7 done alone
        val text = map.encode()
        assertEquals("1|100|10|0-3,5:7,7", text)

        val copy = SegmentMap.decode(text)
        assertNotNull(copy)
        copy!!
        assertEquals(100L, copy.total)
        assertEquals(10L, copy.chunkSize)
        for (i in 0 until map.count) assertEquals("chunk $i", map.receivedIn(i), copy.receivedIn(i))
        assertEquals(map.totalReceived(), copy.totalReceived())
        assertEquals(text, copy.encode())
    }

    @Test
    fun anEmptyMapEncodesWithoutEntries() {
        val map = SegmentMap(30, 10)
        assertEquals("1|30|10|", map.encode())
        assertEquals(0L, SegmentMap.decode(map.encode())!!.totalReceived())
    }

    @Test
    fun corruptStateIsRejected() {
        assertNull(SegmentMap.decode(""))
        assertNull(SegmentMap.decode("2|100|10|"))
        assertNull(SegmentMap.decode("1|abc|10|"))
        assertNull(SegmentMap.decode("1|100|0|"))
        assertNull(SegmentMap.decode("1|100|10|99"))     // chunk does not exist
        assertNull(SegmentMap.decode("1|100|10|0:11"))   // more bytes than the chunk holds
        assertNull(SegmentMap.decode("1|100|10|x-y"))
    }

    @Test
    fun prefixMapCountsTheBytesAlreadyOnDisk() {
        val map = SegmentMap.withPrefix(total = 100, chunkSize = 10, bytes = 25)
        assertTrue(map.isDone(0))
        assertTrue(map.isDone(1))
        assertEquals(5L, map.receivedIn(2))
        assertEquals(0L, map.receivedIn(3))
        assertEquals(25L, map.totalReceived())
    }

    @Test
    fun defaultChunkSizeStaysBetweenOneAndEightMegabytes() {
        val mb = 1024L * 1024
        assertEquals(1 * mb, defaultChunkSize(5 * mb))
        assertEquals(3 * mb, defaultChunkSize(96 * mb))
        assertEquals(8 * mb, defaultChunkSize(10L * 1024 * mb))
    }
}
