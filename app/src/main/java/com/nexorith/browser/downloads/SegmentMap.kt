package com.nexorith.browser.downloads

/**
 * Which parts of a file have been received, as fixed-size chunks. A chunk is claimed by one
 * connection at a time and records how many of its bytes arrived, so a download can be paused
 * and resumed in the middle of a chunk. Thread-safe.
 */
class SegmentMap(val total: Long, val chunkSize: Long) {

    init {
        require(total >= 0 && chunkSize > 0) { "invalid segment map" }
    }

    val count: Int = if (total == 0L) 0 else ((total + chunkSize - 1) / chunkSize).toInt()

    private val received = LongArray(count)
    private val claimed = BooleanArray(count)
    private val lock = Any()

    fun start(index: Int): Long = index.toLong() * chunkSize

    fun length(index: Int): Long = minOf(chunkSize, total - start(index))

    /** Last byte of the chunk (inclusive), as used in a Range header. */
    fun end(index: Int): Long = start(index) + length(index) - 1

    fun receivedIn(index: Int): Long = synchronized(lock) { received[index] }

    fun isDone(index: Int): Boolean = synchronized(lock) { received[index] >= length(index) }

    /** True while some chunk is neither finished nor being downloaded. */
    fun hasUnclaimed(): Boolean = synchronized(lock) {
        (0 until count).any { !claimed[it] && received[it] < length(it) }
    }

    /** Takes the next chunk that is neither finished nor claimed, or null when there is none. */
    fun claim(): Int? {
        synchronized(lock) {
            for (i in 0 until count) {
                if (!claimed[i] && received[i] < length(i)) {
                    claimed[i] = true
                    return i
                }
            }
        }
        return null
    }

    fun add(index: Int, bytes: Long) {
        synchronized(lock) { received[index] = minOf(length(index), received[index] + bytes) }
    }

    fun totalReceived(): Long = synchronized(lock) { received.sum() }

    fun isComplete(): Boolean = synchronized(lock) { (0 until count).all { received[it] >= length(it) } }

    /** Compact text form, saved with the download so that it can resume after a restart. */
    fun encode(): String = synchronized(lock) {
        val parts = ArrayList<String>()
        var i = 0
        while (i < count) {
            if (received[i] >= length(i)) {
                var j = i
                while (j + 1 < count && received[j + 1] >= length(j + 1)) j++
                parts += if (j == i) "$i" else "$i-$j"
                i = j + 1
            } else {
                if (received[i] > 0) parts += "$i:${received[i]}"
                i++
            }
        }
        "1|$total|$chunkSize|" + parts.joinToString(",")
    }

    private fun setReceived(index: Int, bytes: Long) {
        require(index in 0 until count && bytes in 0..length(index)) { "bad entry" }
        synchronized(lock) { received[index] = bytes }
    }

    companion object {
        fun decode(text: String): SegmentMap? {
            val fields = text.split('|', limit = 4)
            if (fields.size != 4 || fields[0] != "1") return null
            val total = fields[1].toLongOrNull() ?: return null
            val chunk = fields[2].toLongOrNull() ?: return null
            if (total < 0 || chunk <= 0) return null
            val map = SegmentMap(total, chunk)
            try {
                for (entry in fields[3].split(',')) {
                    if (entry.isEmpty()) continue
                    when {
                        ':' in entry -> {
                            val pieces = entry.split(':')
                            map.setReceived(pieces[0].toInt(), pieces[1].toLong())
                        }
                        '-' in entry -> {
                            val pieces = entry.split('-')
                            for (i in pieces[0].toInt()..pieces[1].toInt()) map.setReceived(i, map.length(i))
                        }
                        else -> {
                            val i = entry.toInt()
                            map.setReceived(i, map.length(i))
                        }
                    }
                }
            } catch (e: RuntimeException) {
                return null // corrupt state: the caller starts over
            }
            return map
        }

        /** A map for a file whose first [bytes] bytes are already there (a single-connection partial file). */
        fun withPrefix(total: Long, chunkSize: Long, bytes: Long): SegmentMap {
            val map = SegmentMap(total, chunkSize)
            var left = bytes.coerceIn(0, total)
            var i = 0
            while (left > 0 && i < map.count) {
                val take = minOf(left, map.length(i))
                map.setReceived(i, take)
                left -= take
                i++
            }
            return map
        }
    }
}

private const val MIN_CHUNK = 1L * 1024 * 1024
private const val MAX_CHUNK = 8L * 1024 * 1024

/** About 32 chunks, between 1 MB and 8 MB. */
fun defaultChunkSize(total: Long): Long = (total / 32).coerceIn(MIN_CHUNK, MAX_CHUNK)
