package com.sameerasw.airsync.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoffTrackerTest {
    @Test fun wifiToCellularAndBackWithSameAddress() {
        val tracker = NetworkHandoffTracker()
        assertTrue(tracker.update("wifi1", "192.168.1.5"))
        assertTrue(tracker.update("cellular", null))
        assertFalse(tracker.update("cellular", null))
        assertTrue(tracker.update("wifi2", "192.168.1.5"))
    }

    @Test fun repeatedCallbacksDoNotRestartHealthyConnections() {
        val tracker = NetworkHandoffTracker()
        assertTrue(tracker.update("wifi", "192.168.1.5"))
        assertFalse(tracker.update("wifi", "192.168.1.5"))
        assertTrue(tracker.update("wifi", "192.168.1.6"))
    }

    @Test fun lossAndReturnOfSameNetwork() {
        val tracker = NetworkHandoffTracker()
        assertFalse(tracker.update(null, null))
        assertTrue(tracker.update("wifi", "192.168.1.5"))
        assertTrue(tracker.update(null, null))
        assertFalse(tracker.update(null, null))
        assertTrue(tracker.update("wifi", "192.168.1.5"))
    }
}
