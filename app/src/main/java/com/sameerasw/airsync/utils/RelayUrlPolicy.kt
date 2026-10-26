package com.sameerasw.airsync.utils

import java.net.URI

internal object RelayUrlPolicy {
    fun normalize(raw: String): String {
        val input = raw.trim()
        val uri = URI(if ("://" in input) input else "wss://$input")
        require(uri.scheme in setOf("ws", "wss") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) {
            "Invalid relay URL"
        }
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val scheme = if (uri.scheme == "ws" && isPrivateHost(host)) "ws" else "wss"
        val path = uri.path.orEmpty().trimEnd('/').let { if (it.endsWith("/ws")) it else "$it/ws" }
        return URI(scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
    }

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return false
        return octets[0] == 127 || octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }
}
