package com.nexorith.browser.downloads

/**
 * Speed over a sliding window, computed only from bytes that were really received
 * divided by the real time between the first and last sample of the window.
 */
class SpeedMeter(private val windowMs: Long = 3_000) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    fun reset() {
        times.clear()
        bytes.clear()
    }

    /** @param totalBytes bytes received so far in this transfer (monotonic) */
    fun record(nowMs: Long, totalBytes: Long) {
        times.addLast(nowMs)
        bytes.addLast(totalBytes)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            bytes.removeFirst()
        }
    }

    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0
        val elapsed = times.last() - times.first()
        if (elapsed <= 0) return 0
        return (bytes.last() - bytes.first()) * 1000 / elapsed
    }

    companion object {
        /** Seconds left at the current speed, or null when it cannot be estimated. */
        fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long? {
            if (remainingBytes < 0 || bytesPerSecond <= 0) return null
            return (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
        }
    }
}
