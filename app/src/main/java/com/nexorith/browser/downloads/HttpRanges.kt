package com.nexorith.browser.downloads

internal data class ContentRange(val start: Long, val total: Long)

private val CONTENT_RANGE = Regex("bytes\\s+(?:(\\d+)-\\d+|\\*)/(\\d+|\\*)", RegexOption.IGNORE_CASE)

/** Reads "bytes 100-999/1000", "bytes 100-999/*" and "bytes */1000". Unknown numbers become -1. */
internal fun parseContentRange(value: String?): ContentRange? {
    if (value == null) return null
    val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: -1L
    val total = match.groupValues[2].toLongOrNull() ?: -1L
    return ContentRange(start, total)
}

/** 1 s, 2 s, 4 s ... up to 30 s, for the n-th consecutive failure. */
internal fun backoffMillis(failures: Int): Long =
    minOf(1_000L shl (failures - 1).coerceIn(0, 10), 30_000L)

internal const val MAX_RETRY_AFTER_MS = 120_000L
internal const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
