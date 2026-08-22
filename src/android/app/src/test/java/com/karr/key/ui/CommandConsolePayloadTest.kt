package com.karr.key.ui

import com.karr.key.core.Capability
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtCommandLayer
import com.karr.key.protocol.profile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandConsolePayloadTest {
    private val fullAccess = Session(
        displayName = "Test",
        roles = UserRole.entries.toSet(),
        capabilities = Capability.entries.toSet(),
    )

    @Test
    fun consoleExcludesOperationsByCommandIdentity() {
        val operationCommands = setOf(QtCommand.READ_STATE, QtCommand.VEHICLE_CONTROL)

        val commands = commandConsoleCommands(operationCommands)

        assertTrue(QtCommand.READ_STATE !in commands)
        assertTrue(QtCommand.VEHICLE_CONTROL !in commands)
        assertTrue(QtCommand.READ_VIN in commands)
    }

    @Test
    fun defaultConsoleHasNoCommandAlreadyListedInOperations() {
        val operationCommands = listableOperations().map { operation -> operation.command }.toSet()

        assertTrue(commandConsoleCommands().none(operationCommands::contains))
        assertTrue(QtCommand.READ_SPECIFIC_SETTING !in commandConsoleCommands())
    }

    @Test
    fun consoleExcludesUnitEmittedProtocolResponses() {
        val commands = commandConsoleCommands(emptySet())

        assertTrue(commands.none { command -> command.profile.layer == QtCommandLayer.PROTOCOL })
        assertTrue(QtCommand.ACK_OK !in commands)
        assertTrue(QtCommand.ACK_ERROR !in commands)
        assertTrue(QtCommand.CAN_MESSAGE !in commands)
    }

    @Test
    fun researchedPayloadsExposeOnlyObservedNonDestructiveOptions() {
        assertTrue(QtCommand.READ_SPECIFIC_SETTING !in researchedConsolePayloads)
        assertEquals(
            listOf(listOf(0x00), listOf(0x01), listOf(0x03), listOf(0x04)),
            researchedConsolePayloads.getValue(QtCommand.LEARN_DOORS).map { it.payload },
        )
        assertEquals(
            listOf(listOf(0x00), listOf(0x01)),
            researchedConsolePayloads.getValue(QtCommand.REMOTE_TEST_MODE).map { it.payload },
        )
        assertTrue(QtCommand.SET_SECURE_KEY !in researchedConsolePayloads)
        assertTrue(QtCommand.READ_REGISTER !in researchedConsolePayloads)
        assertTrue(QtCommand.WRITE_SPECIFIC_SETTING !in researchedConsolePayloads)
    }

    @Test
    fun curatedModeGatedActionResolvesOnlyKnownModeGatedCommands() {
        // BUMP_TEST 01 maps to the installer-mode-only bump-test operation, so it is drivable.
        val gated = curatedModeGatedAction(
            QtCommand.BUMP_TEST,
            byteArrayOf(0x01),
            fullAccess,
            KarrUnitModel.DR1_CAN,
            KarrDeviceMode.USER,
        )
        assertTrue(gated != null && gated.modeRequirement != null)

        // A plain read has no mode requirement, so there is nothing to drive.
        assertNull(
            curatedModeGatedAction(
                QtCommand.READ_STATE,
                ByteArray(0),
                fullAccess,
                KarrUnitModel.DR1_CAN,
                KarrDeviceMode.USER,
            ),
        )

        // A raw command with no matching recovered operation resolves to nothing.
        assertNull(
            curatedModeGatedAction(
                QtCommand.BOOTLOAD_ENTER,
                ByteArray(0),
                fullAccess,
                KarrUnitModel.DR1_CAN,
                KarrDeviceMode.USER,
            ),
        )
    }

    @Test
    fun compatLineTagsModelModeCommandOutcomeAndPayload() {
        assertEquals(
            "DR1 CAN / User | READ_VIN(0x23) | ACCEPTED rx=READ_VIN 17B | tx=-",
            compatLogLine(
                KarrUnitModel.DR1_CAN,
                KarrDeviceMode.USER,
                QtCommand.READ_VIN,
                ByteArray(0),
                "ACCEPTED rx=READ_VIN 17B",
            ),
        )
        assertEquals(
            "QT6 CAN / Installer | VEHICLE_CONTROL(0x18) | REJECTED code=0x05 | tx=0A",
            compatLogLine(
                KarrUnitModel.QT6_CAN,
                KarrDeviceMode.INSTALLER,
                QtCommand.VEHICLE_CONTROL,
                byteArrayOf(0x0A),
                "REJECTED code=0x05",
            ),
        )
    }

    @Test
    fun compatLineFallsBackWhenModelOrModeIsUnknown() {
        assertEquals(
            "UNKNOWN-MODEL / ? | READ_STATE(0x21) | NO_RESPONSE | tx=-",
            compatLogLine(null, null, QtCommand.READ_STATE, ByteArray(0), "NO_RESPONSE"),
        )
    }

    @Test
    fun blankInputIsAnEmptyPayload() {
        assertArrayEquals(ByteArray(0), bytesOf("   "))
    }

    @Test
    fun spaceSeparatedHexParses() {
        assertArrayEquals(byteArrayOf(0x01, 0x1E), bytesOf("01 1E"))
    }

    @Test
    fun continuousAndPrefixedFormsAreEquivalent() {
        val expected = byteArrayOf(0x01, 0x1E)
        assertArrayEquals(expected, bytesOf("011E"))
        assertArrayEquals(expected, bytesOf("0x01, 0x1e"))
    }

    @Test
    fun fullByteRangeRoundTrips() {
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte()), bytesOf("00 ff 80"))
    }

    @Test
    fun oddLengthTokenIsRejected() {
        val result = parseHexPayload("123")
        assertTrue(result is HexPayload.Invalid)
    }

    @Test
    fun nonHexIsRejected() {
        val result = parseHexPayload("zz")
        assertTrue(result is HexPayload.Invalid)
    }

    @Test
    fun oversizePayloadIsRejected() {
        val huge = "00".repeat(65_535)
        val result = parseHexPayload(huge)
        assertTrue(result is HexPayload.Invalid)
    }

    private fun bytesOf(text: String): ByteArray {
        val result = parseHexPayload(text)
        assertTrue("Expected valid payload for \"$text\" but got $result", result is HexPayload.Bytes)
        return (result as HexPayload.Bytes).value
    }
}
