package com.karr.key.protocol

import java.util.Locale

data class QtDecodedResponse(
    val heading: String,
    val lines: List<String>,
    val securityState: QtSecurityState? = null,
    val unitModel: KarrUnitModel? = null,
    val vehiclePosition: VehiclePosition? = null,
)

/** A unit's self-reported GPS position, decoded from an extended READ_STATE response. */
data class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
)

enum class QtSecurityState(val displayName: String) {
    ARMED("Armed"),
    DISARMED("Disarmed"),
}

object QtResponseDecoder {
    fun decode(
        packet: QtPacket,
        unitModel: KarrUnitModel? = null,
    ): QtDecodedResponse? = when (packet.command) {
        QtCommand.READ_ALL_SETTINGS -> decodeSettings(packet.payload, unitModel)
        QtCommand.READ_VEHICLE_BATTERY_VOLTAGE -> decodeBattery(packet.payload)
        QtCommand.READ_STATE -> decodeState(packet.payload)
        QtCommand.READ_FIRMWARE_VERSION -> decodeFirmware(packet.payload)
        QtCommand.READ_VIOLATIONS -> decodeViolations(packet.payload)
        QtCommand.READ_MODE -> decodeMode(packet.payload)
        QtCommand.READ_VIOLATION_INHIBIT ->
            decodeViolationInhibit(packet.payload, unitModel)
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

    /**
     * Field offsets and values mirror the recovered FullSettings Parse/GetBytes
     * implementations. Only layouts selected by a known unit model are decoded.
     */
    private fun decodeSettings(
        payload: ByteArray,
        unitModel: KarrUnitModel?,
    ): QtDecodedResponse {
        if (payload.size < SETTINGS_RESPONSE_BYTES) {
            return malformed(
                heading = "Unit settings",
                expectation = "at least $SETTINGS_RESPONSE_BYTES settings bytes",
                actualBytes = payload.size,
            )
        }

        val modelSpecific = when (unitModel) {
            KarrUnitModel.QT6_CAN -> canOrDrSettings(
                payload = payload,
                parkLightLabel = "Parking lights as OEM trigger",
            )
            KarrUnitModel.DR1_CAN -> canOrDrSettings(
                payload = payload,
                parkLightLabel = "Parking-light OEM trigger",
            )
            KarrUnitModel.QT6_BLUE -> blueSettings(payload)
            KarrUnitModel.QT6_GREEN -> greenSettings(payload)
            KarrUnitModel.QT6_RED -> redSettings(payload)
            else -> listOf(
                "Some model-specific fields are hidden because this settings layout " +
                    "has not been confirmed for ${unitModel?.displayName ?: "this unit"}.",
            )
        }

        return QtDecodedResponse(
            heading = "Unit settings",
            lines = commonSettings(payload) + modelSpecific,
        )
    }

    private fun commonSettings(payload: ByteArray): List<String> = listOf(
        setting(payload, 0, "Auto-lock with ignition", ::onOff),
        setting(payload, 1, "Auto-unlock with ignition", ::onOff),
        setting(payload, 2, "Passive arm locks doors") {
            choice(it, "No", "Yes")
        },
        setting(payload, 4, "Lock / unlock pulse") {
            choice(it, "3.5 seconds", "Short pulse", "Double unlock")
        },
        setting(payload, 5, "Short pulse duration") {
            choice(it, "600 ms", "400 ms", "800 ms")
        },
        setting(payload, 10, "Confirmation chirp duration") {
            choice(it, "35 ms", "14 ms", "55 ms")
        },
        setting(payload, 17, "Shock sensor sensitivity") { "Level $it" },
        setting(payload, 18, "Borrowed Car Agreement (BCA) mode duration") { "$it days" },
        setting(payload, 20, "Dealer arming") {
            choice(it, "Passive after doors", "Passive after timer")
        },
        setting(payload, 21, "Auto-lock on Bluetooth disconnect", ::onOff),
    )

    private fun canOrDrSettings(
        payload: ByteArray,
        parkLightLabel: String,
    ): List<String> = listOf(
        setting(payload, 3, "User arming", ::userArming),
        setting(payload, 6, "Arm / disarm tones", ::onOff),
        setting(payload, 7, parkLightLabel, ::onOff),
        setting(payload, 11, "Factory-alarm output duration", ::factoryOutputDuration),
        setting(payload, 12, "Arm / disarm trigger polarity") {
            choice(it, "Active low", "Active high")
        },
        setting(payload, 13, "User factory-alarm inhibit", ::onOff),
        setting(payload, 14, "Alarm output", ::alarmOutput),
        setting(payload, 16, "Unlock in user mode", ::onOff),
        setting(payload, 19, "Alarm color") {
            choice(it, "Green", "Blue", "Red")
        },
    )

    private fun blueSettings(payload: ByteArray): List<String> = buildList {
        add(setting(payload, 3, "User arming", ::userArming))
        add(setting(payload, 6, "Arm / disarm tones", ::onOff))
        add(setting(payload, 7, "Active-low trunk input", ::onOff))
        add(
            setting(payload, 8, "Trunk shunt trigger") {
                choice(it, "Positive", "Negative")
            },
        )
        add(setting(payload, 9, "Active-low door inputs", ::onOff))
        add(
            setting(payload, 11, "Arm / disarm sensitivity") {
                choice(it, "1.5", "3", "4.5", "6")
            },
        )
        add(
            setting(payload, 12, "Arm / disarm trigger polarity") {
                choice(it, "Active low", "Active high")
            },
        )
        add(
            setting(payload, 13, "Inhibit input mode") {
                choice(it, "Automatic", "Active ground")
            },
        )
        add(setting(payload, 14, "Alarm output", ::alarmOutput))
        add(
            setting(payload, 15, "Dome-light fade") {
                choice(it, "Off", "5 seconds", "Learn")
            },
        )
        add(setting(payload, 16, "Unlock in user mode", ::onOff))
        add(setting(payload, 19, "OEM trigger duration", ::oemTriggerDuration))
        if (payload.size > 22) {
            add(setting(payload, 22, "Parking lights as OEM trigger", ::onOff))
        }
    }

    private fun greenSettings(payload: ByteArray): List<String> = listOf(
        setting(payload, 9, "Active-low door inputs", ::onOff),
        setting(payload, 11, "Factory-alarm output duration", ::factoryOutputDuration),
    )

    private fun redSettings(payload: ByteArray): List<String> = listOf(
        setting(payload, 3, "User arming", ::userArming),
        setting(payload, 6, "Arm / disarm tones", ::onOff),
        setting(payload, 7, "Active-low trunk input", ::onOff),
        setting(payload, 8, "Step unlock", ::onOff),
        setting(payload, 9, "Active-low door inputs", ::onOff),
        setting(payload, 14, "Alarm output", ::alarmOutput),
        setting(payload, 15, "Dome-light fade") {
            choice(it, "Off", "5 seconds", "Learn")
        },
    )

    /**
     * Bit positions mirror the recovered ViolationViewModel.UpdateInhibit.
     * Unmapped flags remain visible instead of being assigned guessed meanings.
     */
    private fun decodeViolationInhibit(
        payload: ByteArray,
        unitModel: KarrUnitModel?,
    ): QtDecodedResponse {
        val mask = payload.u16LittleEndian()
            ?: return malformed(
                heading = "Violation inhibits",
                expectation = "a two-byte inhibit mask",
                actualBytes = payload.size,
            )
        val doorFlag = if (unitModel == KarrUnitModel.QT6_CAN) {
            InhibitFlag(bit = 4, label = "Driver door")
        } else {
            InhibitFlag(bit = 1, label = "Door input")
        }
        val flags = listOf(
            InhibitFlag(bit = 0, label = "Shock sensor"),
            doorFlag,
            InhibitFlag(bit = 2, label = "Hood"),
            InhibitFlag(bit = 3, label = "Trunk"),
            InhibitFlag(bit = 5, label = "Front passenger door"),
            InhibitFlag(bit = 6, label = "Rear driver door"),
            InhibitFlag(bit = 7, label = "Rear passenger door"),
            InhibitFlag(bit = 8, label = "Tilt sensor"),
        )
        val inhibited = flags
            .filter { flag -> mask and (1 shl flag.bit) != 0 }
            .map(InhibitFlag::label)
        val knownMask = flags.fold(0) { value, flag -> value or (1 shl flag.bit) }
        val unmappedMask = mask and knownMask.inv() and 0xffff

        return QtDecodedResponse(
            heading = "Violation inhibits",
            lines = buildList {
                add(
                    "Inhibited triggers: " +
                        if (inhibited.isEmpty()) {
                            "None"
                        } else {
                            inhibited.joinToString()
                        },
                )
                if (unmappedMask != 0) {
                    add(
                        "Unmapped inhibit flags: 0x${
                            unmappedMask
                                .toString(16)
                                .padStart(4, '0')
                                .uppercase(Locale.US)
                        }",
                    )
                }
                if (payload.size > VIOLATION_INHIBIT_BYTES) {
                    add(
                        "${payload.size - VIOLATION_INHIBIT_BYTES} additional payload " +
                            "bytes were not mapped.",
                    )
                }
            },
        )
    }

    private fun setting(
        payload: ByteArray,
        offset: Int,
        label: String,
        display: (Int) -> String,
    ): String = "$label: ${display(payload[offset].unsigned())}"

    private fun onOff(value: Int): String = choice(value, "Off", "On")

    private fun userArming(value: Int): String = choice(
        value,
        "Passive after doors",
        "Active",
        "Passive after timer",
    )

    private fun factoryOutputDuration(value: Int): String = choice(
        value,
        "2 seconds",
        "1 second",
        "3 seconds",
        "Not available",
    )

    private fun alarmOutput(value: Int): String = choice(value, "Siren", "Horn")

    private fun oemTriggerDuration(value: Int): String = choice(
        value,
        "1 second",
        "2 seconds",
        "3 seconds",
        "Not available",
    )

    private fun choice(
        value: Int,
        vararg labels: String,
    ): String = labels.getOrNull(value) ?: "Unknown value $value"

    private data class InhibitFlag(
        val bit: Int,
        val label: String,
    )

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
        val vehiclePosition = decodeVehiclePosition(payload)
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

            vehiclePosition?.let { position ->
                add(
                    "Position: ${
                        String.format(
                            Locale.US,
                            "%.6f, %.6f",
                            position.latitude,
                            position.longitude,
                        )
                    }",
                )
            }
        }
        return QtDecodedResponse(
            heading = "Vehicle state",
            lines = lines,
            securityState = securityState,
            vehiclePosition = vehiclePosition,
        )
    }

    /**
     * Decodes the unit's self-reported GPS from the extended-position layout, or null when the
     * response is the short state format or the coordinates are out of range. Offsets and the
     * 1e6 fixed-point scaling mirror the recovered extended READ_STATE payload.
     */
    private fun decodeVehiclePosition(payload: ByteArray): VehiclePosition? {
        if (payload.size != EXTENDED_STATE_BYTES) {
            return null
        }
        val latitudeRaw = payload.u32LittleEndian(STATE_LATITUDE_OFFSET) ?: return null
        val longitudeRaw = payload.u32LittleEndian(STATE_LONGITUDE_OFFSET) ?: return null
        val latitude = latitudeRaw / COORDINATE_SCALE - LATITUDE_OFFSET
        val longitude = longitudeRaw / COORDINATE_SCALE - LONGITUDE_OFFSET
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null
        }
        return VehiclePosition(latitude, longitude)
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
    private const val SETTINGS_RESPONSE_BYTES = 22
    private const val VIOLATION_INHIBIT_BYTES = 2
    private const val COORDINATE_SCALE = 1_000_000.0
    private const val LATITUDE_OFFSET = 90.0
    private const val LONGITUDE_OFFSET = 180.0
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
    private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
}
