package com.nexorith.browser.incognito

import com.nexorith.browser.fakes.FakePrivateProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PrivateSessionTest {

    private lateinit var profiles: FakePrivateProfiles
    private lateinit var session: PrivateSession
    private var counter = 0

    @Before
    fun setUp() {
        profiles = FakePrivateProfiles()
        counter = 0
        session = PrivateSession(profiles, newName = { PrivateSession.PROFILE_PREFIX + "t${counter++}" })
    }

    @Test
    fun firstAcquireCreatesProfileAndLaterOnesShareIt() {
        val first = session.acquire()
        val second = session.acquire()
        assertEquals(first, second)
        assertEquals(setOf(first), profiles.existing)
        assertEquals(first, session.activeProfile)
    }

    @Test
    fun profileIsDeletedOnlyWhenLastWebViewIsReleased() {
        val name = session.acquire()
        session.acquire()

        session.release()
        assertTrue(name in profiles.existing)

        session.release()
        assertTrue(profiles.existing.isEmpty())
        assertEquals(listOf(name), profiles.deleted)
        assertNull(session.activeProfile)
    }

    @Test
    fun newSessionAfterEndingUsesAFreshProfile() {
        val first = session.acquire()
        session.release()
        val second = session.acquire()
        assertNotEquals(first, second)
        assertEquals(setOf(second), profiles.existing)
    }

    @Test
    fun extraReleaseIsIgnored() {
        session.release()
        val name = session.acquire()
        session.release()
        session.release()
        assertEquals(listOf(name), profiles.deleted)
    }

    @Test
    fun failedDeletionIsRetriedLater() {
        val name = session.acquire()
        profiles.failDeletes = true
        session.release()
        assertTrue(name in profiles.existing)

        profiles.failDeletes = false
        session.retryPending()
        assertFalse(name in profiles.existing)
    }

    @Test
    fun failedDeletionIsRetriedBeforeTheNextSessionStarts() {
        val old = session.acquire()
        profiles.failDeletes = true
        session.release()
        profiles.failDeletes = false

        val fresh = session.acquire()
        assertFalse(old in profiles.existing)
        assertEquals(setOf(fresh), profiles.existing)
    }

    @Test
    fun purgeRemovesLeftoversButNotTheActiveProfileOrOtherProfiles() {
        profiles.existing += "incognito_crashed1"
        profiles.existing += "incognito_crashed2"
        profiles.existing += "work_profile"
        val active = session.acquire()

        session.purgeLeftovers()

        assertEquals(setOf("work_profile", active), profiles.existing)
    }

    @Test
    fun unsupportedDeviceNeverCreatesAProfile() {
        profiles.isSupported = false
        assertFalse(session.isSupported)
        try {
            session.acquire()
            fail("acquire must refuse when private profiles are unsupported")
        } catch (expected: IllegalStateException) {
            // fail closed: no fallback to the normal profile
        }
        assertTrue(profiles.existing.isEmpty())
        assertNull(session.activeProfile)
    }

    @Test
    fun purgeDoesNothingWhenUnsupported() {
        profiles.existing += "incognito_old"
        profiles.isSupported = false
        session.purgeLeftovers()
        assertEquals(setOf("incognito_old"), profiles.existing)
    }
}
