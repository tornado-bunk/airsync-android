package com.sameerasw.airsync.utils

import org.junit.Assert.*
import org.junit.Test

class LanVerificationWindowTest {
    @Test fun relayPolicyCannotInterruptTheFirstSocketAttempt() {
        var time = 100L
        val verification = LanVerificationWindow { time }
        assertFalse(verification.isActive)
        verification.begin()
        time += 15_000 // Relay's empty catalog arrives before the LAN socket opens.
        assertTrue(verification.isActive)
        verification.begin(10_000) // Local socket now sends its proof.
        time += 9_999
        assertTrue(verification.isActive)
        time++
        assertFalse(verification.isActive)
    }

    @Test fun completedOrResetPairingDoesNotKeepBypassingPolicy() {
        var time = 10L
        val verification = LanVerificationWindow { time }
        verification.begin()
        verification.finish()
        assertFalse(verification.isActive)
        verification.begin()
        time += 25_000
        assertFalse(verification.isActive)
    }
}
