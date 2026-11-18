package com.nexorith.browser.browser

import java.net.URI
import java.net.URLEncoder

sealed interface ResolvedInput {
    data class Navigate(val url: String) : ResolvedInput
    data class Blocked(val scheme: String) : ResolvedInput
    data object Empty : ResolvedInput
}

/**
 * Turns address-bar text into a safe navigation target.
 * HTTPS is the default; script/file/content/data and other non-web schemes are blocked.
 */
object UrlResolver {

    /** Default search engine: Google. */
    const val SEARCH_URL = "https://www.google.com/search?q="

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")
    private val WEB_SCHEMES = setOf("http", "https")
    private val BLOCKED_SCHEMES = setOf(
        "javascript", "file", "content", "data", "intent", "about", "blob", "vbscript",
    )
    private val DOMAIN = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z][a-zA-Z0-9-]{1,}$")
    private val IPV4 = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    fun resolve(raw: String): ResolvedInput {
        val input = raw.trim()
        if (input.isEmpty()) return ResolvedInput.Empty

        val match = SCHEME.find(input)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase()
            val hasSlashes = input.startsWith("//", match.range.last + 1)
            when {
                scheme in BLOCKED_SCHEMES -> return ResolvedInput.Blocked(scheme)
                scheme in WEB_SCHEMES && hasSlashes -> {
                    return if (input.any(Char::isWhitespace) || hostOf(input) == null) {
                        search(input)
                    } else {
                        ResolvedInput.Navigate(input)
                    }
                }
                hasSlashes -> return ResolvedInput.Blocked(scheme) // ftp://, ws://, ...
                // otherwise e.g. "example.com:8080" or "mailto:" style text: fall through
            }
        }

        if (input.any(Char::isWhitespace)) return search(input)
        if (looksLikeHost(input)) {
            val url = "https://$input"
            if (hostOf(url) != null) return ResolvedInput.Navigate(url)
        }
        return search(input)
    }

    fun isWebUrl(url: String?): Boolean {
        if (url == null) return false
        return url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }

    fun isHttps(url: String?): Boolean = url?.startsWith("https://", ignoreCase = true) == true

    /** Address shown when the field is not being edited, e.g. "example.com/path". */
    fun displayUrl(url: String?): String {
        if (url == null) return ""
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
    }

    fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun search(query: String): ResolvedInput.Navigate =
        ResolvedInput.Navigate(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))

    private fun looksLikeHost(input: String): Boolean {
        var host = input.substringBefore('/').substringBefore('?').substringBefore('#')
        val port = host.substringAfterLast(':', "")
        if (host.contains(':') && port.isNotEmpty() && port.all(Char::isDigit)) {
            host = host.substringBeforeLast(':')
        }
        return DOMAIN.matches(host) || IPV4.matches(host)
    }
}
