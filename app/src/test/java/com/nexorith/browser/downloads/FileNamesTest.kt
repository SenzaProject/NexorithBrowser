package com.nexorith.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun contentDispositionQuotedAndPlain() {
        assertEquals("a b.zip", FileNames.fromContentDisposition("attachment; filename=\"a b.zip\""))
        assertEquals("report.pdf", FileNames.fromContentDisposition("attachment; filename=report.pdf"))
    }

    @Test
    fun contentDispositionExtendedNameWins() {
        assertEquals(
            "\u20AC rates.txt",
            FileNames.fromContentDisposition("attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E2%82%AC%20rates.txt"),
        )
        // A plus sign in an extended name is a plus, not a space.
        assertEquals("a+b.txt", FileNames.fromContentDisposition("attachment; filename*=UTF-8''a+b.txt"))
    }

    @Test
    fun contentDispositionMissing() {
        assertNull(FileNames.fromContentDisposition(null))
        assertNull(FileNames.fromContentDisposition(""))
        assertNull(FileNames.fromContentDisposition("inline"))
    }

    @Test
    fun nameFromUrl() {
        assertEquals("My File.zip", FileNames.fromUrl("https://host/path/My%20File.zip?x=1#frag"))
        assertNull(FileNames.fromUrl("https://host/"))
        assertNull(FileNames.fromUrl("https://host"))
    }

    @Test
    fun sanitizeRemovesPathAndIllegalCharacters() {
        val name = FileNames.sanitize("../../etc/passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.startsWith("."))
        assertEquals("a_b_c_.txt", FileNames.sanitize("a:b*c?.txt"))
        assertEquals("download", FileNames.sanitize(""))
        assertEquals("download", FileNames.sanitize("..."))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val name = FileNames.sanitize("x".repeat(300) + ".zip")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".zip"))
    }

    @Test
    fun resolvePrefersHeaderThenUrlThenFallback() {
        assertEquals("real.zip", FileNames.resolve("https://h/api/file/XYZ?download", "attachment; filename=\"real.zip\"", null))
        assertEquals("XYZ.pdf", FileNames.resolve("https://h/api/file/XYZ?download", null, "application/pdf"))
        assertEquals("XYZ", FileNames.resolve("https://h/api/file/XYZ", null, "application/octet-stream"))
        assertEquals("download", FileNames.resolve("https://h/", null, null))
    }
}
