package com.nexorith.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlResolverTest {

    private fun navigate(input: String) =
        (UrlResolver.resolve(input) as ResolvedInput.Navigate).url

    @Test
    fun emptyInputIsEmpty() {
        assertEquals(ResolvedInput.Empty, UrlResolver.resolve("   "))
    }

    @Test
    fun bareDomainDefaultsToHttps() {
        assertEquals("https://example.com", navigate("example.com"))
        assertEquals("https://example.com/path?q=1", navigate("example.com/path?q=1"))
        assertEquals("https://example.com:8443", navigate("example.com:8443"))
        assertEquals("https://192.168.1.1", navigate("192.168.1.1"))
    }

    @Test
    fun fullWebUrlsAreKept() {
        assertEquals("https://example.com/a?b=1", navigate("https://example.com/a?b=1"))
        assertEquals("http://example.com", navigate("http://example.com"))
    }

    @Test
    fun googleIsTheDefaultSearchEngine() {
        assertEquals("https://www.google.com/search?q=", UrlResolver.SEARCH_URL)
        assertEquals("https://www.google.com/search?q=kopi+susu", navigate("kopi susu"))
        assertEquals("https://www.google.com/search?q=a%26b%3Dc", navigate("a&b=c"))
    }

    @Test
    fun plainTextBecomesSearch() {
        assertEquals(UrlResolver.SEARCH_URL + "hello+world", navigate("hello world"))
        assertEquals(UrlResolver.SEARCH_URL + "nexorith", navigate("nexorith"))
    }

    @Test
    fun dangerousSchemesAreBlocked() {
        assertEquals(ResolvedInput.Blocked("javascript"), UrlResolver.resolve("javascript:alert(1)"))
        assertEquals(ResolvedInput.Blocked("file"), UrlResolver.resolve("file:///etc/passwd"))
        assertEquals(ResolvedInput.Blocked("content"), UrlResolver.resolve("content://x/y"))
        assertEquals(ResolvedInput.Blocked("data"), UrlResolver.resolve("data:text/html,hi"))
        assertEquals(ResolvedInput.Blocked("javascript"), UrlResolver.resolve("JavaScript:alert(1)"))
        assertEquals(ResolvedInput.Blocked("ftp"), UrlResolver.resolve("ftp://example.com"))
    }

    @Test
    fun urlWithSpacesBecomesSearch() {
        assertTrue(navigate("https://exa mple.com").startsWith(UrlResolver.SEARCH_URL))
    }

    @Test
    fun displayUrlStripsSchemeAndTrailingSlash() {
        assertEquals("example.com", UrlResolver.displayUrl("https://example.com/"))
        assertEquals("example.com/a", UrlResolver.displayUrl("http://example.com/a"))
        assertEquals("", UrlResolver.displayUrl(null))
    }

    @Test
    fun webUrlChecks() {
        assertTrue(UrlResolver.isWebUrl("https://a.com"))
        assertFalse(UrlResolver.isWebUrl("about:blank"))
        assertFalse(UrlResolver.isWebUrl(null))
        assertTrue(UrlResolver.isHttps("HTTPS://a.com"))
        assertFalse(UrlResolver.isHttps("http://a.com"))
        assertNull(UrlResolver.hostOf("not a url"))
    }
}
