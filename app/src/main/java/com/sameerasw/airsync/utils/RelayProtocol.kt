package com.sameerasw.airsync.utils

import java.util.UUID

/** Only transmit these frames inside authenticated encryption. */
internal class RelayProtocol(
    private val role: String,
    private val nonce: String = UUID.randomUUID().toString().replace("-", "")
) {
    private val peerRole = if (role == "mac") "android" else "mac"
    private var peerNonce: String? = null
    private var sent = 0L
    private var received = 0L

    val hello: String get() = "AB1|hello|$role|$nonce"

    @Synchronized
    fun wrap(payload: String): String? {
        val receiver = peerNonce ?: return null
        if (sent == Long.MAX_VALUE) return null
        sent++
        return "AB1|data|$role|$nonce|$receiver|$sent|$payload"
    }

    data class Received(val payload: String? = null, val reply: String? = null)

    @Synchronized
    fun receive(plaintext: String): Received {
        val parts = plaintext.split('|', limit = 7)
        if (parts.size < 4 || parts[0] != "AB1" || parts[2] != peerRole) return Received()
        if (parts.size == 4 && parts[1] == "hello" && parts[3].matches(Regex("[0-9a-f]{32}"))) {
            val changed = peerNonce != parts[3]
            peerNonce = parts[3]
            // Never reset counters on hello: replaying it must not re-enable old data.
            return Received(reply = if (changed) hello else null)
        }
        if (parts.size != 7 || parts[1] != "data" || parts[3] != peerNonce || parts[4] != nonce) return Received()
        val sequence = parts[5].toLongOrNull() ?: return Received()
        if (sequence <= received) return Received()
        received = sequence
        return Received(payload = parts[6])
    }
}
