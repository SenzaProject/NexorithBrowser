package com.nexorith.browser.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64

class ChecksumsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun digest(algorithm: String, data: ByteArray) = MessageDigest.getInstance(algorithm).digest(data)
    private val data = "hello nexorith".toByteArray()

    @Test
    fun readsTheDigestHeader() {
        val checksum = Checksums.fromHeaders("SHA-256=${b64(digest("SHA-256", data))}", null)
        assertNotNull(checksum)
        assertEquals("SHA-256", checksum!!.algorithm)
        assertArrayEquals(digest("SHA-256", data), checksum.expected)
    }

    @Test
    fun readsTheReprDigestHeader() {
        val checksum = Checksums.fromHeaders(null, "sha-256=:${b64(digest("SHA-256", data))}:")
        assertEquals("SHA-256", checksum!!.algorithm)
        assertArrayEquals(digest("SHA-256", data), checksum.expected)
    }

    @Test
    fun prefersTheStrongestAlgorithm() {
        val header = "md5=${b64(digest("MD5", data))}, sha-512=${b64(digest("SHA-512", data))}, sha-1=${b64(digest("SHA-1", data))}"
        assertEquals("SHA-512", Checksums.fromHeaders(header, null)!!.algorithm)
    }

    @Test
    fun ignoresUnknownAlgorithmsBrokenValuesAndWrongLengths() {
        assertNull(Checksums.fromHeaders(null, null))
        assertNull(Checksums.fromHeaders("unixsum=30637", null))
        assertNull(Checksums.fromHeaders("sha-256=not base64!!", null))
        assertNull(Checksums.fromHeaders("sha-256=${b64(ByteArray(5))}", null)) // not 32 bytes
        assertNull(Checksums.fromHeaders("garbage", null))
    }

    @Test
    fun matchesComparesTheFileWithTheExpectedDigest() {
        val file = tmp.newFile().apply { writeBytes(data) }
        assertTrue(Checksums.matches(file, Checksum("SHA-256", digest("SHA-256", data))))
        assertFalse(Checksums.matches(file, Checksum("SHA-256", digest("SHA-256", "other".toByteArray()))))
    }

    @Test
    fun matchesCallsTheCheckCallbackWhileHashing() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(600 * 1024)) }
        var calls = 0
        Checksums.matches(file, Checksum("MD5", ByteArray(16))) { calls++ }
        assertTrue(calls >= 2)
    }
}
