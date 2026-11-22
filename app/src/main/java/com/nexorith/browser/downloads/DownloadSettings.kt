package com.nexorith.browser.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionLimits {
    const val MIN = 1
    const val MAX = 8
    const val DEFAULT = 4

    /** Choices offered to the user. */
    val CHOICES = listOf(1, 2, 4, 6, 8)
}

interface DownloadSettings {
    /** Most parallel connections one download may use. */
    val maxConnections: StateFlow<Int>
    fun setMaxConnections(value: Int)
}

class SharedPreferencesDownloadSettings(context: Context) : DownloadSettings {

    private val prefs = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)
    private val connections = MutableStateFlow(
        prefs.getInt(KEY_CONNECTIONS, ConnectionLimits.DEFAULT).coerceIn(ConnectionLimits.MIN, ConnectionLimits.MAX),
    )

    override val maxConnections: StateFlow<Int> = connections.asStateFlow()

    override fun setMaxConnections(value: Int) {
        val clamped = value.coerceIn(ConnectionLimits.MIN, ConnectionLimits.MAX)
        prefs.edit().putInt(KEY_CONNECTIONS, clamped).apply()
        connections.value = clamped
    }

    private companion object {
        const val KEY_CONNECTIONS = "max_connections"
    }
}
