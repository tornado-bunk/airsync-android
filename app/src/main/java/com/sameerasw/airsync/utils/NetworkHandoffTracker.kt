package com.sameerasw.airsync.utils

/** Ignores capability/DNS churn, but detects rejoining a network even with the same IP. */
internal class NetworkHandoffTracker {
    private var route: Pair<String?, String?> = null to null
    val hasNetwork: Boolean get() = route.first != null

    fun update(networkId: String?, localAddress: String?): Boolean {
        val next = networkId to localAddress
        if (next == route) return false
        route = next
        return true
    }
}
