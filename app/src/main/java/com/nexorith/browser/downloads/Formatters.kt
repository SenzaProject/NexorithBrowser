package com.nexorith.browser.downloads

import java.util.Locale

object Formatters {

    fun bytes(value: Long, locale: Locale = Locale.getDefault()): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = -1
        do {
            v /= 1024
            i++
        } while (v >= 1024 && i < units.lastIndex)
        val pattern = if (v >= 100) "%.0f %s" else "%.1f %s"
        return String.format(locale, pattern, v, units[i])
    }

    /** KB/s below 1 MB/s, MB/s above. */
    fun speed(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String = when {
        bytesPerSecond <= 0 -> "0 KB/s"
        bytesPerSecond < 1024 -> "<1 KB/s"
        bytesPerSecond < 1024 * 1024 -> String.format(locale, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(locale, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    /** "45s", "4m 12s", "1h 05m". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }
}
