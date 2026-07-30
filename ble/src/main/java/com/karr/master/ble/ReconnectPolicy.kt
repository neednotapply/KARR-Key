package com.karr.master.ble

import java.util.Locale

internal data class ReconnectTarget(
    val serial: String,
)

internal object ReconnectPolicy {
    val retryDelaysMillis = listOf(
        1_000L,
        2_000L,
        5_000L,
        10_000L,
        20_000L,
    )

    val maxAttempts: Int
        get() = retryDelaysMillis.size

    fun targetFor(device: DiscoveredDevice?): ReconnectTarget? =
        stableSerial(device)?.let(::ReconnectTarget)

    fun matches(
        target: ReconnectTarget,
        device: DiscoveredDevice,
    ): Boolean = stableSerial(device) == target.serial

    private fun stableSerial(device: DiscoveredDevice?): String? {
        if (device?.connectionEligible != true) {
            return null
        }
        val normalized = (
            device.name
                ?: device.classification.recoveredName
            )
            ?.trim()
            ?.uppercase(Locale.US)
            ?: return null
        return normalized.takeIf(EXACT_DR_QT_SERIAL::matches)
    }

    private val EXACT_DR_QT_SERIAL = Regex("^(DR|QT) [A-F0-9]{7,8}$")
}
