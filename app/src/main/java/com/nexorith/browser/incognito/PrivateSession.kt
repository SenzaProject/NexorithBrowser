package com.nexorith.browser.incognito

import java.util.UUID

/**
 * Lifetime of the private browsing data.
 *
 * A private session starts when the first private WebView is created and ends when the last
 * one is destroyed. Ending it deletes the whole profile, so cookies, cache and site data of
 * private tabs never mix with the normal profile and never outlive the private tabs.
 *
 * Profiles whose deletion fails (or that a crash left behind) are removed on the next
 * [retryPending] / [purgeLeftovers] call.
 *
 * Main thread only.
 */
class PrivateSession(
    private val profiles: PrivateProfiles,
    private val newName: () -> String = ::randomProfileName,
) {
    private var current: String? = null
    private var users = 0
    private val pending = LinkedHashSet<String>()

    val isSupported: Boolean get() = profiles.isSupported

    /** Name of the profile in use, or null when no private WebView exists. */
    val activeProfile: String? get() = current

    /** Registers one more private WebView and returns the profile it must be bound to. */
    fun acquire(): String {
        check(profiles.isSupported) { "Private browsing is not supported on this device" }
        retryPending()
        val name = current ?: newName().also { created ->
            profiles.create(created)
            current = created
        }
        users++
        return name
    }

    /** Call after a private WebView has been destroyed. Deletes the profile with the last one. */
    fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        val name = current ?: return
        current = null
        if (!profiles.delete(name)) pending += name
    }

    /** Retries deletions that failed earlier. */
    fun retryPending() {
        pending.toList().forEach { name ->
            if (profiles.delete(name)) pending -= name
        }
    }

    /** Deletes private profiles left behind by a crash or a force stop. Call at app start. */
    fun purgeLeftovers() {
        retryPending()
        if (!profiles.isSupported) return
        profiles.listNames()
            .filter { it.startsWith(PROFILE_PREFIX) && it != current }
            .forEach { name -> if (!profiles.delete(name)) pending += name }
    }

    companion object {
        const val PROFILE_PREFIX = "incognito_"
    }
}

internal fun randomProfileName(): String =
    PrivateSession.PROFILE_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
