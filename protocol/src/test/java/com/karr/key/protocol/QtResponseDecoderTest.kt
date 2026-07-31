package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QtResponseDecoderTest {
    @Test
    fun decodesCapturedBatteryVoltageAsLittleEndianMillivolts() {
        val decoded = decode(
            command = QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
            payload = byteArrayOf(0xAD.toByte(), 0x30),
        )

        assertEquals("Vehicle battery", decoded.heading)
        assertTrue(decoded.lines.contains("Voltage: 12.461 V"))
        assertTrue(decoded.lines.contains("Raw measurement: 12461 mV"))
    }

    @Test
    fun decodesCapturedExtendedVehicleState() {
        val decoded = decode(
            command = QtCommand.READ_STATE,
            payload = bytes(
                0x01, 0x00, 0xA6, 0x30, 0x44, 0x52, 0x20, 0x44,
                0x30, 0x32, 0x39, 0x41, 0x35, 0x34, 0x44, 0xA0,
                0x13, 0x8D, 0x07, 0x4C, 0xFE, 0x77, 0x03,
            ),
        )

        assertEquals("Vehicle state", decoded.heading)
        assertTrue(decoded.lines.contains("Ignition: Off"))
        assertTrue(decoded.lines.contains("Security: Disarmed"))
        assertTrue(decoded.lines.contains("Front driver / wired doors: Open"))
        assertTrue(decoded.lines.contains("Front passenger door: Closed"))
        assertTrue(decoded.lines.contains("Battery: 12.454 V"))
        assertTrue(decoded.lines.contains("Position: 36.686112, -121.804468"))
    }

    @Test
    fun decodesCapturedViolationHistorySlots() {
        val payload = bytes(
            0x17, 0x82, 0x44, 0x06,
            0xA9, 0x69, 0x76, 0x01, 0x00,
            0x51, 0xE4, 0x1F, 0x02, 0x00,
        ) + ByteArray(10 * 5).also { emptySlots ->
            repeat(10) { slot ->
                emptySlots[slot * 5 + 4] = 0xFF.toByte()
            }
        }

        val decoded = decode(
            command = QtCommand.READ_VIOLATIONS,
            payload = payload,
        )

        assertEquals("Violation history", decoded.heading)
        assertTrue(decoded.lines.contains("Stored triggers: 2"))
        assertTrue(decoded.lines.any { line -> line.startsWith("Slot 1: Shock — ") })
        assertTrue(decoded.lines.any { line -> line.startsWith("Slot 2: Shock — ") })
        assertTrue(decoded.lines.none { line -> line.contains("Partial history") })
    }

    @Test
    fun reportsPartialViolationPayloadWithoutInventingSlots() {
        val decoded = decode(
            command = QtCommand.READ_VIOLATIONS,
            payload = bytes(
                0x10, 0x00, 0x00, 0x00,
                0x08, 0x00, 0x00, 0x00, 0x08,
            ),
        )

        assertTrue(decoded.lines.contains("Stored triggers: 1"))
        assertTrue(decoded.lines.contains("Slot 1: Tilt — 8s ago"))
        assertTrue(decoded.lines.any { line -> line.startsWith("Partial history: 1 of 12") })
    }

    @Test
    fun decodesFirmwareVersionFromRecoveredVersionByte() {
        val decoded = decode(
            command = QtCommand.READ_FIRMWARE_VERSION,
            payload = bytes(0x03, 0x2A),
        )

        assertTrue(decoded.lines.contains("Firmware version: 42"))
        assertTrue(decoded.lines.contains("Firmware selector: 0x03"))
    }

    @Test
    fun decodesModeIdentity() {
        val decoded = decode(
            command = QtCommand.READ_MODE,
            payload = bytes(
                KarrDeviceMode.USER.code or 0x08,
                0x04,
                0x2A,
            ),
        )

        assertTrue(decoded.lines.contains("Mode: User"))
        assertTrue(decoded.lines.contains("Security: Armed"))
        assertTrue(decoded.lines.contains("Unit type: DR1 CAN"))
        assertTrue(decoded.lines.contains("Unit version: 42"))
    }

    @Test
    fun explainsMalformedKnownResponse() {
        val decoded = decode(
            command = QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
            payload = byteArrayOf(0x01),
        )

        assertTrue(decoded.lines.single().contains("expected two voltage bytes"))
        assertTrue(decoded.lines.single().contains("received 1"))
    }

    @Test
    fun leavesUnknownResponseForRawProtocolDisplay() {
        val decoded = QtResponseDecoder.decode(
            packet(
                command = QtCommand.READ_ALL_SETTINGS,
                payload = byteArrayOf(0),
            ),
        )

        assertNull(decoded)
    }

    private fun decode(
        command: QtCommand,
        payload: ByteArray,
    ): QtDecodedResponse {
        val decoded = QtResponseDecoder.decode(packet(command, payload))
        assertNotNull(decoded)
        return decoded!!
    }

    private fun packet(
        command: QtCommand,
        payload: ByteArray,
    ) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = command.code,
        payload = payload,
    )

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
