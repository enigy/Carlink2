package com.carlink.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Adapter identity the host learns from the USB projection protocol, published for the
 * Settings UI and the `[FW_CHECK]` log line. Process-global (like
 * [com.carlink.cluster.ClusterBindingState]) so whichever CarlinkManager instance reads a
 * SoftwareVersion (0xCC) message feeds the single Settings screen.
 *
 * SCOPE — this reports the firmware VERSION the adapter announces; it is NOT a
 * stock-vs-custom test. The CPC200-CCPA custom firmware is built from the same base
 * version (2025.10.15.1127) and announces the same string over projection, so the version
 * alone cannot prove which image is flashed (the custom image's real tells — Dropbear/SFTP
 * on the adapter's own IP — are not reachable over the USB projection link). What this DOES
 * verify is that the adapter runs the version this app's protocol handling and bundled
 * binary patches were built against; a different version is the thing that would silently
 * invalidate those assumptions.
 */
object AdapterIdentityState {
    /**
     * Firmware version this app is built and verified against. The adapter appends a
     * hardware/region suffix to it (e.g. "CAY" → "2025.10.15.1127CAY"), so a match is a
     * prefix test on this base rather than string equality.
     */
    const val EXPECTED_FIRMWARE_BASE = "2025.10.15.1127"

    private val _firmwareVersion = MutableStateFlow<String?>(null)

    /** Raw SoftwareVersion the adapter last reported (e.g. "2025.10.15.1127CAY"), or null. */
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    /** Record the adapter-reported SoftwareVersion. Blank payloads are ignored. */
    fun recordFirmwareVersion(version: String) {
        val trimmed = version.trim()
        if (trimmed.isNotEmpty()) _firmwareVersion.value = trimmed
    }

    /**
     * Whether [version] is the expected base. `null` (nothing reported yet) is not a
     * mismatch — callers show a neutral "unknown" state until the adapter answers.
     */
    fun matches(version: String?): Boolean =
        version != null && version.startsWith(EXPECTED_FIRMWARE_BASE)
}
