package com.sameerasw.airsync.utils

import org.junit.Assert.*
import org.junit.Test

class TrustedNetworkTest {
    private val home = TrustedNetwork("12345678-1234-4234-8234-123456789abc", "Home", "a".repeat(64), "b".repeat(64), "192.168.1.0/24", "192.168.1.1", 1, "mac", false)

    @Test fun validatesCatalogTrustBoundaries() {
        assertTrue(home.isValid())
        assertFalse(home.copy(id = "not-a-uuid").isValid())
        assertFalse(home.copy(name = " ").isValid())
        assertFalse(home.copy(name = "a".repeat(81)).isValid())
        assertFalse(home.copy(androidFingerprint = "missing").isValid())
        assertFalse(home.copy(revision = Long.MAX_VALUE).isValid())
        assertFalse(home.copy(gateway = "192.168.1.999").isValid())
        assertFalse(home.copy(subnet = "192.168.1.4/24").isValid())
    }

    @Test fun deletionBeatsAnOfflineRenameInEitherArrivalOrder() {
        val removed = home.copy(deleted = true, revision = 2)
        val offlineRename = home.copy(name = "New name", revision = 3, editor = "android")
        assertEquals(removed, TrustedNetwork.merge(offlineRename, removed))
        assertEquals(removed, TrustedNetwork.merge(removed, offlineRename))
    }

    @Test fun concurrentRenamesConvergeWithoutClockSynchronization() {
        val mac = home.copy(name = "Mac name", revision = 2)
        val android = home.copy(name = "Phone name", revision = 2, editor = "android")
        assertEquals(mac, TrustedNetwork.merge(mac, android))
        assertEquals(mac, TrustedNetwork.merge(android, mac))
        assertEquals(android.copy(revision = 3), TrustedNetwork.merge(mac, android.copy(revision = 3)))
    }

    @Test fun aRenameCannotChangeNetworkIdentity() {
        val impostor = home.copy(macFingerprint = "c".repeat(64), revision = 100)
        assertEquals(home, TrustedNetwork.merge(home, impostor))
        assertEquals(home, TrustedNetwork.merge(home, home.copy(subnet = "10.0.0.0/8", revision = 100)))
    }

    @Test fun subnetDoesNotDependOnDhcpHostAddress() {
        assertEquals("192.168.1.0/24", TrustedNetwork.subnet("192.168.1.5", 24))
        assertEquals("192.168.1.0/24", TrustedNetwork.subnet("192.168.1.200", 24))
        assertEquals("10.16.0.0/12", TrustedNetwork.subnet("10.21.3.4", 12))
        assertEquals("192.168.1.5/32", TrustedNetwork.subnet("192.168.1.5", 32))
        assertNull(TrustedNetwork.subnet("192.168.1.5", 0))
        assertNull(TrustedNetwork.subnet("192.168.1.5", 33))
        assertNull(TrustedNetwork.ipv4("192.168.1.-1"))
        assertNull(TrustedNetwork.ipv4("192.168.1.256"))
        assertNull(TrustedNetwork.ipv4("192.168.1.1.1"))
        assertFalse(TrustedNetwork.validSubnet("192.168.1.5/24"))
    }

    @Test fun sameSubnetDoesNotMakeTwoNetworksIdentical() {
        val university = home.copy(id = "87654321-4321-4321-8321-cba987654321", macFingerprint = "c".repeat(64), androidFingerprint = "d".repeat(64))
        assertTrue(university.isValid())
        assertNotEquals(home.macFingerprint, university.macFingerprint)
        assertNotEquals(home.androidFingerprint, university.androidFingerprint)
    }
}
