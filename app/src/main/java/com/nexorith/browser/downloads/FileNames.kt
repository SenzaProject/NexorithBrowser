package com.nexorith.browser.downloads

import java.net.URLDecoder
import java.nio.charset.Charset

object FileNames {

    private const val MAX_LENGTH = 120
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val EXT_STAR = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE)
    private val EXT_PLAIN = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)

    private val EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "video/mp4" to "mp4",
        "audio/mpeg" to "mp3",
        "text/plain" to "txt",
    )

    /** Name for a download that a page started: header name, else the URL's last segment, else "download". */
    fun resolve(url: String, contentDisposition: String?, mimeType: String?): String {
        val raw = fromContentDisposition(contentDisposition) ?: fromUrl(url) ?: "download"
        return withExtension(sanitize(raw), mimeType)
    }

    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        EXT_STAR.find(header)?.let { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1].ifBlank { "UTF-8" }) }
                .getOrDefault(Charsets.UTF_8)
            decode(m.groupValues[2].trim(), charset)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        EXT_PLAIN.find(header)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    fun fromUrl(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty() || !afterScheme.contains('/')) return null
        val path = afterScheme.substringAfter('/').substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isEmpty()) return null
        return decode(last, Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    }

    fun sanitize(name: String): String {
        var result = name.replace(ILLEGAL, "_").trim().trim('.').trim()
        if (result.length > MAX_LENGTH) {
            val ext = result.substringAfterLast('.', "")
            result = if (ext.isNotEmpty() && ext.length <= 10) {
                result.substringBeforeLast('.').take(MAX_LENGTH - ext.length - 1) + "." + ext
            } else {
                result.take(MAX_LENGTH)
            }
        }
        return result.ifEmpty { "download" }
    }

    /** Name from a server header, cleaned and completed with an extension from the MIME type. */
    fun fromServerName(name: String, mimeType: String?): String = withExtension(sanitize(name), mimeType)

    private fun withExtension(name: String, mimeType: String?): String {
        if (name.contains('.')) return name
        val ext = mimeType?.substringBefore(';')?.trim()?.lowercase()?.let { EXTENSIONS[it] } ?: return name
        return "$name.$ext"
    }

    private fun decode(value: String, charset: Charset): String? =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), charset.name()) }.getOrNull()
}
