package com.sameerasw.airsync.utils

import java.util.UUID

data class TrustedNetwork(
    val id: String,
    val name: String,
    val macFingerprint: String,
    val androidFingerprint: String,
    val subnet: String,
    val gateway: String,
    val revision: Long,
    val editor: String,
    val deleted: Boolean
) {
    fun isValid(): Boolean = runCatching {
        UUID.fromString(id).toString().equals(id, ignoreCase = true) && name.isNotBlank() && name.length <= 80 &&
            listOf(macFingerprint, androidFingerprint).all { it.matches(Regex("[0-9a-fA-F]{64}")) } &&
            ipv4(gateway) != null && validSubnet(subnet) && revision in 1 until Long.MAX_VALUE / 2 && editor in setOf("mac", "android")
    }.getOrDefault(false)

    companion object {
        fun merge(existing: TrustedNetwork?, incoming: TrustedNetwork): TrustedNetwork {
            if (existing == null) return incoming
            if (existing.id != incoming.id || existing.macFingerprint != incoming.macFingerprint ||
                existing.androidFingerprint != incoming.androidFingerprint || existing.subnet != incoming.subnet || existing.gateway != incoming.gateway) return existing
            if (existing.deleted != incoming.deleted) return if (existing.deleted) existing else incoming
            if (existing.revision != incoming.revision) return if (existing.revision > incoming.revision) existing else incoming
            return if (existing.editor >= incoming.editor) existing else incoming
        }

        fun ipv4(address: String): Long? {
            val parts = address.split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
                val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or value.toLong()
            }
            return result
        }

        fun subnet(address: String, prefix: Int): String? {
            if (prefix !in 1..32) return null
            val ip = ipv4(address) ?: return null
            val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val network = ip and mask
            return "${(network shr 24) and 255}.${(network shr 16) and 255}.${(network shr 8) and 255}.${network and 255}/$prefix"
        }

        fun validSubnet(value: String): Boolean {
            val parts = value.split('/')
            if (parts.size != 2) return false
            val prefix = parts[1].toIntOrNull() ?: return false
            return subnet(parts[0], prefix) == value
        }
    }
}
