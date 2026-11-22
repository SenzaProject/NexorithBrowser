package com.nexorith.browser.downloads

/**
 * Decides how many connections a download should use. It starts low and adds one connection at
 * a time as long as the measured throughput keeps growing; it stops at the first level that
 * does not help, goes back one level if throughput dropped, and halves the count when the
 * server throttles (HTTP 429/503). Throughput numbers come from real bytes received.
 */
class ConcurrencyController(
    private val max: Int,
    initial: Int,
    startMs: Long,
    private val holdMs: Long = 3_000,
) {
    var target: Int = initial.coerceIn(1, max.coerceAtLeast(1))
        private set

    private var settled = false
    private var baseline = 0L
    private var holdUntilMs = startMs + holdMs
    private var lastThrottleMs = Long.MIN_VALUE

    /** Call about every 250 ms with the throughput of the last few seconds. */
    fun evaluate(nowMs: Long, bytesPerSecond: Long) {
        if (settled || nowMs < holdUntilMs || bytesPerSecond <= 0) return
        if (target >= max) {
            settled = true
            return
        }
        if (baseline == 0L || bytesPerSecond * 100 >= baseline * GAIN_PERCENT) {
            baseline = bytesPerSecond
            target++
            holdUntilMs = nowMs + holdMs
        } else {
            settled = true
            if (bytesPerSecond * 100 < baseline * DROP_PERCENT && target > 1) target--
        }
    }

    /**
     * The server answered 429/503: use fewer connections and do not ramp up again. Several
     * connections usually get the answer together, so calls close to each other count once.
     */
    fun onThrottled(nowMs: Long) {
        settled = true
        if (lastThrottleMs != Long.MIN_VALUE && nowMs - lastThrottleMs < THROTTLE_BURST_MS) return
        lastThrottleMs = nowMs
        target = (target / 2).coerceAtLeast(1)
    }

    private companion object {
        const val GAIN_PERCENT = 115L
        const val DROP_PERCENT = 90L
        const val THROTTLE_BURST_MS = 2_000L
    }
}
