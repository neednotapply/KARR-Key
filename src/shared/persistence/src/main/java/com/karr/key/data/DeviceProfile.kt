package com.karr.key.data

import com.karr.key.protocol.KarrVulnerabilityStatus
import java.time.Instant

/**
 * What a unit's most recent self-reported GPS (extended READ_STATE) plus its authentication
 * challenge revealed when we connected to it: its authoritative unit-type model (from READ_MODE)
 * and whether it is vulnerable or patched (from the challenge width). Null model means we never got
 * a mode read (e.g. a patched unit rejects auth before READ_MODE).
 */
data class DeviceProfile(
    val deviceAddress: String,
    val unitModelCode: Int?,
    val vulnerability: KarrVulnerabilityStatus,
    val updatedUtc: Instant,
)
