package com.nexorith.browser.downloads

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** A digest the server announced for the whole file. [algorithm] is a MessageDigest name. */
class Checksum(val algorithm: String, val expected: ByteArray)

/**
 * Checksums are only checked when the server sends one for the complete file:
 * `Digest` (RFC 3230) or `Repr-Digest` (RFC 9530). Many servers send neither; then the file is
 * not verified and nothing is claimed about its integrity.
 */
object Checksums {

    // header name -> MessageDigest name, strongest first
    private val ALGORITHMS = listOf("sha-512" to "SHA-512", "sha-256" to "SHA-256", "sha-1" to "SHA-1", "md5" to "MD5")

    fun fromHeaders(digest: String?, reprDigest: String?): Checksum? {
        val found = HashMap<String, ByteArray>()
        digest?.split(',')?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) decode(part.substring(eq + 1))?.let { found[part.substring(0, eq).trim().lowercase()] = it }
        }
        reprDigest?.split(',')?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val value = part.substring(eq + 1).trim().removePrefix(":").removeSuffix(":")
                decode(value)?.let { found.putIfAbsent(part.substring(0, eq).trim().lowercase(), it) }
            }
        }
        for ((headerName, javaName) in ALGORITHMS) {
            val bytes = found[headerName] ?: continue
            if (bytes.size == MessageDigest.getInstance(javaName).digestLength) return Checksum(javaName, bytes)
        }
        return null
    }

    /**
     * Hashes [file] and compares it with [checksum]. [check] is called regularly so that the
     * caller can stop a long hash when the download is cancelled.
     */
    @Throws(IOException::class)
    fun matches(file: File, checksum: Checksum, check: () -> Unit = {}): Boolean {
        val digest = MessageDigest.getInstance(checksum.algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                check()
            }
        }
        return MessageDigest.isEqual(digest.digest(), checksum.expected)
    }

    private fun decode(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
}
