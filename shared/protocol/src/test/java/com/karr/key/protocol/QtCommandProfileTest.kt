package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QtCommandProfileTest {
    @Test
    fun everyCommandHasAProfile() {
        // The profile getter is an exhaustive when, so this mostly guards against a future NPE-free
        // regression and documents that all codes, including markers, are classified.
        QtCommand.entries.forEach { command ->
            assertNotNull("No profile for $command", command.profile)
        }
    }

    @Test
    fun destructiveCommandsAlwaysRequireConfirmation() {
        QtCommand.entries
            .filter { it.profile.risk == QtCommandRisk.DESTRUCTIVE }
            .forEach { command ->
                assertTrue(
                    "$command is destructive but does not require confirmation",
                    command.profile.requiresConfirmation,
                )
            }
    }

    @Test
    fun protocolMarkersAreReadTierAndNeverConfirm() {
        QtCommand.entries
            .filter { it.profile.layer == QtCommandLayer.PROTOCOL }
            .forEach { command ->
                assertEquals(QtCommandRisk.SAFE_READ, command.profile.risk)
                assertTrue(
                    "$command should not need confirmation",
                    !command.profile.requiresConfirmation,
                )
            }
        // The known response/marker codes land in the PROTOCOL layer.
        assertEquals(QtCommandLayer.PROTOCOL, QtCommand.ACK_OK.profile.layer)
        assertEquals(QtCommandLayer.PROTOCOL, QtCommand.ACK_ERROR.profile.layer)
    }

    @Test
    fun readVinIsASafeCoreRead() {
        val profile = QtCommand.READ_VIN.profile
        assertEquals(QtCommandRisk.SAFE_READ, profile.risk)
        assertEquals(QtCommandLayer.CORE, profile.layer)
    }

    @Test
    fun secureKeyAndBootloaderAreDestructive() {
        assertEquals(QtCommandRisk.DESTRUCTIVE, QtCommand.SET_SECURE_KEY.profile.risk)
        assertEquals(QtCommandRisk.DESTRUCTIVE, QtCommand.BOOTLOAD_ENTER.profile.risk)
        assertEquals(QtCommandLayer.BOOTLOADER, QtCommand.BOOTLOAD_ENTER.profile.layer)
    }

    @Test
    fun everyDangerScoreStaysInRange() {
        QtCommand.entries.forEach { command ->
            val score = command.dangerScore
            assertTrue("$command danger $score out of range", score in 0..10)
        }
    }

    @Test
    fun dangerScoreTracksRiskTier() {
        // A plain read is the floor (0); a reversible state change sits in the middle; firmware
        // bootload is the ceiling.
        assertEquals(0, QtCommand.READ_STATE.dangerScore)
        assertTrue(QtCommand.CHANGE_MODE.dangerScore in 3..6)
        assertEquals(10, QtCommand.BOOTLOAD_ENTER.dangerScore)

        assertTrue(
            "Destructive should outrank state change",
            QtCommand.RESET_TO_DEFAULT.dangerScore > QtCommand.CHANGE_MODE.dangerScore,
        )
        assertTrue(
            "State change should outrank a read",
            QtCommand.CHANGE_MODE.dangerScore > QtCommand.READ_MODE.dangerScore,
        )

        // Every read stays in the calm band, every destructive command in the high band.
        QtCommand.entries.forEach { command ->
            when (command.profile.risk) {
                QtCommandRisk.SAFE_READ -> assertTrue(
                    "$command read danger too high",
                    command.dangerScore <= 3,
                )
                QtCommandRisk.DESTRUCTIVE -> assertTrue(
                    "$command destructive danger too low",
                    command.dangerScore >= 7,
                )
                QtCommandRisk.STATE_CHANGE -> Unit
            }
        }
    }
}
