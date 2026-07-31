package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredActionPreflightTest {
    private val ignitionPreflight = RecoveredActionPreflight.IGNITION_OFF
    private val trunkPreflight = RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE
    private val silentPreflight = RecoveredActionPreflight.SILENT_CONTROL_SETTINGS

    @Test
    fun allowsReadStateWhenIgnitionBitIsClear() {
        assertEquals(
            RecoveredPreflightDecision.Allowed(),
            ignitionPreflight.evaluate(readState(0x00)),
        )
    }

    @Test
    fun deniesReadStateWhenIgnitionBitIsSet() {
        val result = ignitionPreflight.evaluate(readState(0x40))

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("ignition on"))
    }

    @Test
    fun deniesEmptyReadStatePayload() {
        val result = ignitionPreflight.evaluate(readState())

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("malformed"))
    }

    @Test
    fun deniesUnexpectedResponseCommand() {
        val result = ignitionPreflight.evaluate(
            QtPacket(
                mode = ProtocolMode.ORIGINAL,
                commandCode = QtCommand.ACK_OK.code,
                payload = byteArrayOf(),
            ),
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("READ_STATE"))
    }

    @Test
    fun allowsTrunkReleaseForQt6RedInUserMode() {
        assertEquals(
            RecoveredPreflightDecision.Allowed(),
            trunkPreflight.evaluate(readMode(mode = KarrDeviceMode.USER, unitType = 3)),
        )
    }

    @Test
    fun allowsTrunkReleaseForQt6RedInValetMode() {
        assertEquals(
            RecoveredPreflightDecision.Allowed(),
            trunkPreflight.evaluate(readMode(mode = KarrDeviceMode.VALET, unitType = 3)),
        )
    }

    @Test
    fun deniesTrunkReleaseForNonRedUnit() {
        val result = trunkPreflight.evaluate(
            readMode(mode = KarrDeviceMode.USER, unitType = 4),
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        val denied = result as RecoveredPreflightDecision.Denied
        assertTrue(denied.reason.contains("DR1 CAN"))
        assertTrue(denied.reason.contains("only for QT6 RED"))
    }

    @Test
    fun deniesTrunkReleaseOutsideCustomerModes() {
        val result = trunkPreflight.evaluate(
            readMode(mode = KarrDeviceMode.DEALER, unitType = 3),
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("dealer mode"))
    }

    @Test
    fun deniesTrunkReleaseWhenIdentityIsIncomplete() {
        val result = trunkPreflight.evaluate(
            QtPacket(
                mode = ProtocolMode.ORIGINAL,
                commandCode = QtCommand.READ_MODE.code,
                payload = byteArrayOf(KarrDeviceMode.USER.code.toByte()),
            ),
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("incomplete"))
    }

    @Test
    fun keepsDedicatedSilentCommandForBlueUnitWithTonesEnabled() {
        val result = silentPreflight.evaluate(
            responses = listOf(
                readMode(mode = KarrDeviceMode.USER, unitType = 1),
                readSettings(tonesEnabled = true),
            ),
            attemptedCommand = QtCommand.SILENT_LOCK,
        )

        assertTrue(result is RecoveredPreflightDecision.Allowed)
        val allowed = result as RecoveredPreflightDecision.Allowed
        assertEquals(null, allowed.commandOverride)
        assertTrue(allowed.explanation.orEmpty().contains("dedicated silent command"))
    }

    @Test
    fun routesSilentLockThroughOrdinaryLockWhenTonesAreOff() {
        val result = silentPreflight.evaluate(
            responses = listOf(
                readMode(mode = KarrDeviceMode.USER, unitType = 4),
                readSettings(tonesEnabled = false, color = 0),
            ),
            attemptedCommand = QtCommand.SILENT_LOCK,
        )

        assertTrue(result is RecoveredPreflightDecision.Allowed)
        val allowed = result as RecoveredPreflightDecision.Allowed
        assertEquals(QtCommand.LOCK_DOORS, allowed.commandOverride)
        assertTrue(allowed.explanation.orEmpty().contains("already off"))
    }

    @Test
    fun routesSilentUnlockThroughOrdinaryUnlockWhenTonesAreOff() {
        val result = silentPreflight.evaluate(
            responses = listOf(
                readMode(mode = KarrDeviceMode.VALET, unitType = 0),
                readSettings(tonesEnabled = false),
            ),
            attemptedCommand = QtCommand.SILENT_UNLOCK,
        )

        assertTrue(result is RecoveredPreflightDecision.Allowed)
        assertEquals(
            QtCommand.UNLOCK_DOORS,
            (result as RecoveredPreflightDecision.Allowed).commandOverride,
        )
    }

    @Test
    fun deniesSilentControlForGreenUnit() {
        val result = silentPreflight.evaluate(
            responses = listOf(
                readMode(mode = KarrDeviceMode.USER, unitType = 2),
                readSettings(tonesEnabled = true),
            ),
            attemptedCommand = QtCommand.SILENT_LOCK,
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("QT6 GREEN"))
    }

    @Test
    fun deniesDedicatedSilentControlForNonBlueDrConfiguration() {
        val result = silentPreflight.evaluate(
            responses = listOf(
                readMode(mode = KarrDeviceMode.USER, unitType = 4),
                readSettings(tonesEnabled = true, color = 0),
            ),
            attemptedCommand = QtCommand.SILENT_LOCK,
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("Blue CAN/DR"))
    }

    private fun readState(vararg payload: Int) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = QtCommand.READ_STATE.code,
        payload = ByteArray(payload.size) { index -> payload[index].toByte() },
    )

    private fun readMode(
        mode: KarrDeviceMode,
        unitType: Int,
    ) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = QtCommand.READ_MODE.code,
        payload = byteArrayOf(
            mode.code.toByte(),
            unitType.toByte(),
            0x2a,
        ),
    )

    private fun readSettings(
        tonesEnabled: Boolean,
        color: Int = 1,
    ) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = QtCommand.READ_ALL_SETTINGS.code,
        payload = ByteArray(22).also { settings ->
            settings[6] = if (tonesEnabled) 1 else 0
            settings[19] = color.toByte()
        },
    )
}
