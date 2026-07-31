package com.karr.key.protocol

import java.util.Locale

data class QtDecodedResponse(
    val heading: String,
    val lines: List<String>,
    val securityState: QtSecurityState? = null,
    val unitModel: KarrUnitModel? = null,
)

enum class QtSecurityState(val displayName: String) {
    ARMED("Armed"),
    DISARMED("Disarmed"),
}

object QtResponseDecoder {
    fun decode(packet: QtPacket): QtDecodedResponse? = when (packet.command) {
        QtCommand.READ_VEHICLE_BATTERY_VOLTAGE -> decodeBattery(packet.payload)
        QtCommand.READ_STATE -> decodeState(packet.payload)
        QtCommand.READ_FIRMWARE_VERSION -> decodeFirmware(packet.payload)
        QtCommand.READ_VIOLATIONS -> decodeViolations(packet.payload)
        QtCommand.READ_MODE -> decodeMode(packet.payload)
        else -> null
    }

    private fun decodeBattery(payload: ByteArray): QtDecodedResponse {
        val millivolts = payload.u16LittleEndian()
            ?: return malformed(
                heading = "Vehicle battery",
                expectation = "two voltage bytes",
                actualBytes = payload.size,
            )
        return QtDecodedResponse(
            heading = "Vehicle battery",
            lines = listOf(
                "Voltage: ${formatMillivolts(millivolts)}",
                "Raw measurement: $millivolts mV",
            ),
        )
    }

    private fun decodeState(payload: ByteArray): QtDecodedResponse {
        if (payload.isEmpty()) {
            return malformed(
                heading = "Vehicle state",
                expectation = "at least one state byte",
                actualBytes = payload.size,
            )
        }

        val primary = payload[0].unsigned()
        val secondary = payload.getOrNull(1)?.unsigned()
        val securityState = secondary?.toSecurityState(ARMED_MASK)
        val lines = buildList {
            add("Ignition: ${openClosed(primary, IGNITION_MASK, "On", "Off")}")
            add("Security: ${securityState?.displayName ?: "Not returned"}")
            add(
                "Front driver / wired doors: " +
                    openClosed(primary, FRONT_DRIVER_MASK),
            )
            add("Front passenger door: ${openClosed(primary, FRONT_PASSENGER_MASK)}")
            add("Rear driver door: ${openClosed(primary, REAR_DRIVER_MASK)}")
            add("Rear passenger door: ${openClosed(primary, REAR_PASSENGER_MASK)}")
            add("Hood: ${openClosed(primary, HOOD_MASK)}")
            add("Trunk: ${openClosed(primary, TRUNK_MASK)}")
            add("Trunk shunt input: ${openClosed(primary, TRUNK_SHUNT_MASK)}")
            if (secondary != null) {
                add("Arm inhibit: ${yesNo(secondary and ARM_INHIBIT_MASK != 0)}")
                add("Disarm inhibit: ${yesNo(secondary and DISARM_INHIBIT_MASK != 0)}")
            }

            payload.u16LittleEndian(STATE_BATTERY_OFFSET)?.let { millivolts ->
                add("Battery: ${formatMillivolts(millivolts)}")
            }

            if (payload.size == EXTENDED_STATE_BYTES) {
                val latitudeRaw = payload.u32LittleEndian(STATE_LATITUDE_OFFSET)
                val longitudeRaw = payload.u32LittleEndian(STATE_LONGITUDE_OFFSET)
                if (latitudeRaw != null && longitudeRaw != null) {
                    val latitude = latitudeRaw / COORDINATE_SCALE - LATITUDE_OFFSET
                    val longitude = longitudeRaw / COORDINATE_SCALE - LONGITUDE_OFFSET
                    if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                        add(
                            "Position: ${
                                String.format(
                                    Locale.US,
                                    "%.6f, %.6f",
                                    latitude,
                                    longitude,
                                )
                            }",
                        )
                    }
                }
            }
        }
        return QtDecodedResponse(
            heading = "Vehicle state",
            lines = lines,
            securityState = securityState,
        )
    }

    private fun decodeFirmware(payload: ByteArray): QtDecodedResponse {
        if (payload.size < FIRMWARE_RESPONSE_BYTES) {
            return malformed(
                heading = "Firmware information",
                expectation = "a firmware selector and version byte",
                actualBytes = payload.size,
            )
        }
        return QtDecodedResponse(
            heading = "Firmware information",
            lines = listOf(
                "Firmware version: ${payload[1].unsigned()}",
                "Firmware selector: 0x${payload[0].hex()}",
            ),
        )
    }

    private fun decodeViolations(payload: ByteArray): QtDecodedResponse {
        if (payload.size < VIOLATION_HEADER_BYTES) {
            return malformed(
                heading = "Violation history",
                expectation = "a four-byte unit timestamp",
                actualBytes = payload.size,
            )
        }

        val nowStamp = payload.u32LittleEndian(0)!!
        val availableSlots = (payload.size - VIOLATION_HEADER_BYTES) / VIOLATION_SLOT_BYTES
        val slotCount = minOf(availableSlots, MAX_VIOLATION_SLOTS)
        val violations = buildList {
            repeat(slotCount) { slot ->
                val offset = VIOLATION_HEADER_BYTES + slot * VIOLATION_SLOT_BYTES
                val eventStamp = payload.u32LittleEndian(offset)!!
                val sourceCode = payload[offset + 4].unsigned()
                if (sourceCode == NO_VIOLATION_SOURCE) {
                    return@repeat
                }
                val elapsed = if (nowStamp >= eventStamp) {
                    nowStamp - eventStamp
                } else {
                    null
                }
                add(
                    buildString {
                        append("Slot ${slot + 1}: ")
                        append(triggerSourceName(sourceCode))
                        if (elapsed != null) {
                            append(" — ")
                            append(formatElapsed(elapsed))
                            append(" ago")
                        } else {
                            append(" — timestamp is ahead of the unit clock")
                        }
                    },
                )
            }
        }
        return QtDecodedResponse(
            heading = "Violation history",
            lines = buildList {
                add("Stored triggers: ${violations.size}")
                if (violations.isEmpty()) {
                    add("No triggers are stored in the returned history.")
                } else {
                    addAll(violations)
                }
                if (availableSlots < MAX_VIOLATION_SLOTS) {
                    add(
                        "Partial history: $availableSlots of $MAX_VIOLATION_SLOTS slots returned.",
                    )
                }
            },
        )
    }

    private fun decodeMode(payload: ByteArray): QtDecodedResponse {
        if (payload.size < MODE_RESPONSE_BYTES) {
            return malformed(
                heading = "Unit identity",
                expectation = "mode, unit type, and version bytes",
                actualBytes = payload.size,
            )
        }
        val status = payload[0].unsigned()
        val unitModel = KarrUnitModel.fromTypeByte(payload[1])
        val securityState = status.toSecurityState(MODE_ARMED_MASK)
        return QtDecodedResponse(
            heading = "Unit identity",
            lines = listOf(
                "Mode: ${
                    KarrDeviceMode
                        .fromStatusByte(payload[0])
                        .name
                        .lowercase()
                        .replaceFirstChar(Char::uppercase)
                        .replace('_', ' ')
                }",
                "Security: ${securityState.displayName}",
                "Unit type: ${unitModel.displayName}",
                "Unit version: ${payload[2].unsigned()}",
            ),
            securityState = securityState,
            unitModel = unitModel,
        )
    }

    private fun malformed(
        heading: String,
        expectation: String,
        actualBytes: Int,
    ) = QtDecodedResponse(
        heading = heading,
        lines = listOf(
            "Unable to decode: expected $expectation; received $actualBytes payload bytes.",
        ),
    )

    private fun ByteArray.u16LittleEndian(offset: Int = 0): Int? {
        if (size < offset + 2) {
            return null
        }
        return this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)
    }

    private fun ByteArray.u32LittleEndian(offset: Int): Long? {
        if (size < offset + 4) {
            return null
        }
        return (this[offset].unsigned().toLong()) or
            (this[offset + 1].unsigned().toLong() shl 8) or
            (this[offset + 2].unsigned().toLong() shl 16) or
            (this[offset + 3].unsigned().toLong() shl 24)
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun Int.toSecurityState(armedMask: Int): QtSecurityState =
        if (this and armedMask != 0) {
            QtSecurityState.ARMED
        } else {
            QtSecurityState.DISARMED
        }

    private fun Byte.hex(): String =
        unsigned().toString(16).padStart(2, '0').uppercase(Locale.US)

    private fun openClosed(
        value: Int?,
        mask: Int,
        active: String = "Open",
        inactive: String = "Closed",
    ): String = if (value != null && value and mask != 0) active else inactive

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

    private fun formatMillivolts(millivolts: Int): String =
        "${millivolts / 1000}.${(millivolts % 1000).toString().padStart(3, '0')} V"

    private fun triggerSourceName(source: Int): String = when (source) {
        0 -> "Shock"
        1 -> "Door"
        2 -> "Hood"
        3 -> "Trunk"
        4 -> "Driver door"
        5 -> "Front passenger door"
        6 -> "Rear driver door"
        7 -> "Rear passenger door"
        8 -> "Tilt"
        9 -> "App"
        10 -> "Panic"
        11 -> "Server"
        12 -> "Crash"
        else -> "Unknown trigger 0x${source.toString(16).padStart(2, '0').uppercase(Locale.US)}"
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val days = totalSeconds / SECONDS_PER_DAY
        val hours = totalSeconds % SECONDS_PER_DAY / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${totalSeconds}s"
        }
    }

    private const val FRONT_DRIVER_MASK = 0x01
    private const val FRONT_PASSENGER_MASK = 0x02
    private const val REAR_DRIVER_MASK = 0x04
    private const val REAR_PASSENGER_MASK = 0x08
    private const val HOOD_MASK = 0x10
    private const val TRUNK_MASK = 0x20
    private const val IGNITION_MASK = 0x40
    private const val TRUNK_SHUNT_MASK = 0x80
    private const val ARMED_MASK = 0x04
    private const val ARM_INHIBIT_MASK = 0x08
    private const val DISARM_INHIBIT_MASK = 0x10
    private const val STATE_BATTERY_OFFSET = 2
    private const val EXTENDED_STATE_BYTES = 23
    private const val STATE_LATITUDE_OFFSET = 15
    private const val STATE_LONGITUDE_OFFSET = 19
    private const val FIRMWARE_RESPONSE_BYTES = 2
    private const val VIOLATION_HEADER_BYTES = 4
    private const val VIOLATION_SLOT_BYTES = 5
    private const val MAX_VIOLATION_SLOTS = 12
    private const val NO_VIOLATION_SOURCE = 0xff
    private const val MODE_RESPONSE_BYTES = 3
    private const val MODE_ARMED_MASK = 0x08
    private const val COORDINATE_SCALE = 1_000_000.0
    private const val LATITUDE_OFFSET = 90.0
    private const val LONGITUDE_OFFSET = 180.0
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
    private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
}
