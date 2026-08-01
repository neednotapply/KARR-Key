package com.karr.key.ble

import com.karr.key.protocol.KarrUnitModel
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleKarrDeviceTest {
    @Test
    fun confirmsExactDrAndQtSerialsSeenByRecoveredDealerApp() {
        listOf(
            "DR D029A54D",
            "DR 0F9ACB56",
            "QT ABC1234",
            "qt abcdef12",
        ).forEach { name ->
            val result = CompatibleKarrDevice.classify(name)
            assertEquals(name, KarrCandidateTier.CONFIRMED_FORMAT, result.tier)
            assertTrue(result.isCandidate)
        }
    }

    @Test
    fun keepsBroaderLegacyPrefixesAsPossibleCandidates() {
        listOf("DR", "DRlegacy", "QT", "QT-4", "QT X").forEach { name ->
            assertEquals(
                name,
                KarrCandidateTier.POSSIBLE,
                CompatibleKarrDevice.classify(name).tier,
            )
        }
    }

    @Test
    fun confirmsRecoveredNamedManufacturerFrame() {
        val payload = ByteArray(26)
        "DR D029A54Dxx".toByteArray(StandardCharsets.UTF_8).copyInto(payload)
        payload[13] = 0x40

        val result = CompatibleKarrDevice.classify(
            name = null,
            manufacturerData = listOf(payload),
        )

        assertEquals(KarrCandidateTier.CONFIRMED_FORMAT, result.tier)
        assertEquals("DR D029A54D", result.recoveredName)
        assertTrue(result.evidence.contains("QT6 CAN"))
    }

    @Test
    fun confirmsDealerBase32NameOnlyInsideValidNamedFrame() {
        val payload = ByteArray(26)
        "0123456789ABC".toByteArray(StandardCharsets.UTF_8).copyInto(payload)
        payload[13] = 0x40

        assertEquals(
            KarrCandidateTier.CONFIRMED_FORMAT,
            CompatibleKarrDevice.classify(
                name = null,
                manufacturerData = listOf(payload),
            ).tier,
        )
    }

    @Test
    fun confirmsStatusFrameWithRecoveredModelId() {
        val manufacturerResult =
            CompatibleKarrDevice.classify(
                name = null,
                manufacturerData = listOf(byteArrayOf(0x40, 0, 0, 0, 0)),
            )
        assertEquals(
            KarrCandidateTier.CONFIRMED_FORMAT,
            manufacturerResult.tier,
        )
        assertTrue(manufacturerResult.evidence.contains("QT6 CAN"))
    }

    @Test
    fun confirmsEveryDealerModelIdPresentInRecoveredSource() {
        KarrUnitModel.known.forEach { model ->
            val encodedType = model.code + 1
            val payload = ByteArray(5).apply {
                this[0] = ((encodedType and 0x03) shl 6).toByte()
                this[1] = (((encodedType ushr 2) and 0x03) shl 6).toByte()
            }

            val classification = CompatibleKarrDevice.classify(
                name = null,
                manufacturerData = listOf(payload),
            )

            assertEquals(model.displayName, KarrCandidateTier.CONFIRMED_FORMAT, classification.tier)
            assertTrue(model.displayName, classification.evidence.contains(model.displayName))
        }
    }

    @Test
    fun treatsSharedGenericUartAsPossible() {
        assertEquals(
            KarrCandidateTier.POSSIBLE,
            CompatibleKarrDevice.classify(
                name = "Unrecognized Unit",
                advertisedServices = listOf(KarrGatt.UART_SERVICE),
            ).tier,
        )
    }

    @Test
    fun retainsUnknownNonzeroDealerUnitTypeForLaterHardware() {
        assertEquals(
            KarrCandidateTier.POSSIBLE,
            CompatibleKarrDevice.classify(
                name = null,
                manufacturerData = listOf(
                    byteArrayOf(
                        0xC0.toByte(),
                        0xC0.toByte(),
                        0,
                        0,
                        0,
                    ),
                ),
            ).tier,
        )
    }

    @Test
    fun keepsBrandingOnlyNamesAsPossible() {
        listOf("KARR", "KARR Security", "swds", "SWDS-Installer").forEach { name ->
            assertEquals(
                name,
                KarrCandidateTier.POSSIBLE,
                CompatibleKarrDevice.classify(name).tier,
            )
        }
    }

    @Test
    fun retainsUnrelatedAndUnnamedDevicesAsOther() {
        listOf(
            null,
            "",
            "   ",
            "Pixel Buds",
            "Living Room TV",
            "Headphones",
            "SQT1",
            "My KARR-like speaker",
            "WQ-80A162640389",
        ).forEach { name ->
            val result = CompatibleKarrDevice.classify(
                name = name,
                advertisedServices = listOf(UUID.randomUUID()),
            )
            assertEquals(name ?: "<null>", KarrCandidateTier.OTHER, result.tier)
            assertFalse(result.isCandidate)
        }
    }

    @Test
    fun rejectsManufacturerDataWithoutRecoveredUnitTypeBits() {
        assertEquals(
            KarrCandidateTier.OTHER,
            CompatibleKarrDevice.classify(
                name = null,
                manufacturerData = listOf(byteArrayOf(0, 0, 0, 0, 0)),
            ).tier,
        )
    }

    @Test
    fun connectionRequiresConfirmedFormatAndConnectableAdvertisement() {
        val confirmed = KarrDeviceClassification(
            tier = KarrCandidateTier.CONFIRMED_FORMAT,
            evidence = "test",
        )
        val possible = KarrDeviceClassification(
            tier = KarrCandidateTier.POSSIBLE,
            evidence = "test",
        )

        assertTrue(
            DiscoveredDevice(
                address = "00:00:00:00:00:01",
                name = "DR D029A54D",
                rssi = -50,
                connectable = true,
                classification = confirmed,
            ).connectionEligible,
        )
        assertFalse(
            DiscoveredDevice(
                address = "00:00:00:00:00:02",
                name = null,
                rssi = -50,
                connectable = false,
                classification = confirmed,
            ).connectionEligible,
        )
        assertFalse(
            DiscoveredDevice(
                address = "00:00:00:00:00:03",
                name = "Nearby television",
                rssi = -50,
                connectable = true,
                classification = possible,
            ).connectionEligible,
        )
    }
}
