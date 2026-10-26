package com.sameerasw.airsync.utils

/** Keeps relay policy updates from cancelling the LAN proof currently establishing trust. */
internal class LanVerificationWindow(private val now: () -> Long) {
    @Volatile private var deadline = 0L
    val isActive: Boolean get() = now() < deadline
    fun begin(durationMillis: Long = 25_000) { deadline = now() + durationMillis }
    fun finish() { deadline = 0 }
}
