package com.karr.master.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthenticationTest {
    @Test
    fun dealerChallengeMatchesIndependentLegacyVector() = runTest {
        val keys = FakeKeyProvider(
            dealer = ByteArray(16) { index -> index.toByte() },
            customer = ByteArray(16) { index -> (index + 16).toByte() },
        )
        val response = LegacyDeviceAuthenticator(keys).answerChallenge(
            unitSerial = "DR 12345678",
            challenge = bytes(0, 1, 2, 3, 4, 5, 6, 7),
            mode = KarrDeviceMode.DEALER,
        ).getOrThrow()

        assertEquals(DeviceKeyPurpose.DEALER, keys.lastPurpose)
        assertArrayEquals(
            hex(
                "1C 1F 1D 90 1E 01 1F 72 20 E3 21 54 22 C5 23 36 " +
                    "24 A7 25 18 26 89 27 FA 28 6B 29 DC 2A 4D 2B BE",
            ),
            response,
        )
    }

    @Test
    fun customerChallengeUsesRecoveredSerialPositions() = runTest {
        val keys = FakeKeyProvider(
            dealer = ByteArray(16) { index -> index.toByte() },
            customer = ByteArray(16) { index -> (index + 16).toByte() },
        )
        val response = LegacyDeviceAuthenticator(keys).answerChallenge(
            unitSerial = "dr 12345678",
            challenge = bytes(0, 1, 2, 3, 4, 5, 6, 7),
            mode = KarrDeviceMode.USER,
        ).getOrThrow()

        assertEquals(DeviceKeyPurpose.CUSTOMER, keys.lastPurpose)
        assertArrayEquals(
            hex(
                "2C 2F 2D A0 2E 11 51 84 30 F3 31 64 52 F5 33 46 " +
                    "34 B7 53 66 36 99 37 0A 38 7B 39 EC 3A 5D 3B CE",
            ),
            response,
        )
    }

    @Test
    fun rejectsWrongChallengeOrKeySize() = runTest {
        val shortKeyProvider = FakeKeyProvider(
            dealer = ByteArray(15),
            customer = ByteArray(16),
        )
        val authenticator = LegacyDeviceAuthenticator(shortKeyProvider)

        assertTrue(
            authenticator.answerChallenge(
                unitSerial = "DR 12345678",
                challenge = ByteArray(7),
                mode = KarrDeviceMode.DEALER,
            ).isFailure,
        )
        assertTrue(
            authenticator.answerChallenge(
                unitSerial = "DR 12345678",
                challenge = ByteArray(8),
                mode = KarrDeviceMode.DEALER,
            ).isFailure,
        )
    }

    @Test
    fun parsesSeparatedHexWithoutRetainingFormatting() {
        val parsed = ResearchKeyEncoding.parseLegacyHex(
            "00 11 22 33-44:55 66 77 88 99 AA BB CC DD EE FF",
        ).getOrThrow()

        assertArrayEquals(
            bytes(
                0x00,
                0x11,
                0x22,
                0x33,
                0x44,
                0x55,
                0x66,
                0x77,
                0x88,
                0x99,
                0xAA,
                0xBB,
                0xCC,
                0xDD,
                0xEE,
                0xFF,
            ),
            parsed,
        )
        assertTrue(ResearchKeyEncoding.parseLegacyHex("not a key").isFailure)
    }

    private class FakeKeyProvider(
        private val dealer: ByteArray,
        private val customer: ByteArray,
    ) : DeviceKeyProvider {
        var lastPurpose: DeviceKeyPurpose? = null

        override suspend fun keyForUnit(
            serial: String,
            purpose: DeviceKeyPurpose,
        ): Result<ByteArray> = runCatching {
            lastPurpose = purpose
            when (purpose) {
                DeviceKeyPurpose.CUSTOMER -> customer.copyOf()
                DeviceKeyPurpose.DEALER -> dealer.copyOf()
                DeviceKeyPurpose.PER_UNIT_ENCRYPTION ->
                    error("No per-unit test key was provisioned.")
            }
        }
    }

    private fun hex(value: String): ByteArray =
        value.split(" ").map { byte -> byte.toInt(16).toByte() }.toByteArray()

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
