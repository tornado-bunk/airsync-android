package com.sameerasw.airsync.utils

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransportGenerationTest {
    @Before fun start() { WebSocketUtil.resetTransportNegotiationState() }
    @After fun finish() { WebSocketUtil.resetTransportNegotiationState() }

    @Test fun freshOfferReplacesAnInFlightRoundWithoutWaitingForExpiry() {
        assertEquals(1L, WebSocketUtil.nextTransportGeneration())
        assertTrue(WebSocketUtil.acceptIncomingTransportGeneration(2, "offer_rx"))
        assertTrue(WebSocketUtil.isTransportGenerationActive(2))
        assertFalse(WebSocketUtil.acceptIncomingTransportGeneration(1, "delayed_answer"))
        assertEquals(3L, WebSocketUtil.nextTransportGeneration())
    }

    @Test fun wifiReturnStartsInTheSameGenerationAsTheNewRelayPeer() {
        WebSocketUtil.acceptIncomingTransportGeneration(50, "old_session")
        WebSocketUtil.resetTransportNegotiationState()
        assertEquals(1L, WebSocketUtil.nextTransportGeneration())
        assertTrue(WebSocketUtil.acceptIncomingTransportGeneration(1, "peer_restarted"))
    }

    @Test fun validatedOldRoundCannotNominateTheNewConnection() {
        val old = WebSocketUtil.nextTransportGeneration()
        WebSocketUtil.markTransportGenerationValidated(old, "check_ack")
        assertTrue(WebSocketUtil.isTransportGenerationValidated(old))
        assertTrue(WebSocketUtil.acceptIncomingTransportGeneration(old + 1, "new_offer"))
        assertFalse(WebSocketUtil.isTransportGenerationValidated(old))
        assertFalse(WebSocketUtil.isTransportGenerationValidated(old + 1))
        assertFalse(WebSocketUtil.acceptIncomingTransportGeneration(0, "invalid"))
    }
}
