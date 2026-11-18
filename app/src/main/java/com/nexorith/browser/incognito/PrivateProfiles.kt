package com.nexorith.browser.incognito

/**
 * Storage backend for private browsing. Each profile is a separate WebView data container
 * (cookies, cache, local storage, permissions) that can be deleted as a whole.
 *
 * Kept free of Android types so the session logic can be unit-tested on the JVM.
 */
interface PrivateProfiles {

    /** False when this device's WebView cannot keep private data apart from the normal profile. */
    val isSupported: Boolean

    /** Creates an empty profile. Throws if private profiles are not supported. */
    fun create(name: String)

    /**
     * Deletes the profile and everything stored in it.
     * Returns false when it could not be deleted yet (for example, it is still in use).
     */
    fun delete(name: String): Boolean

    fun listNames(): List<String>
}
