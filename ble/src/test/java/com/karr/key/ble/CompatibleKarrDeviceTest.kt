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
            // The scan line names the family (DR vs QT); model and vulnerable/patched are only
            // known after connecting and are layered on later.
            val expectedFamily = name.trim().uppercase().take(2)
            assertEquals(name, "Recovered $expectedFamily serial format", result.evidence)
        }
    }

    @Test
    fun keepsBroaderLegacyPrefixesAsPossibleCandidates() {
        // Two capitals, a space, then a serial that isn't the exact recovered hex format: still a
        // possible legacy candidate, just not a confirmed one.
        listOf("DR X", "QT X", "QT 123456", "DR LEGACY1").forEach { name ->
            assertEquals(
                name,
                KarrCandidateTier.POSSIBLE,
                CompatibleKarrDevice.classify(name).tier,
            )
        }
    }

    @Test
    fun rejectsDrQtLookalikesWithoutTheCapitalTwoLetterSpacePrefix() {
        // Valid matches are "DR <serial>" / "QT <serial>": two capital letters, a space, a serial.
        // A name that merely begins with those letters — a dryer, a drone, a lowercase or hyphenated
        // prefix, or the bare prefix — is not a candidate.
        listOf("Dryer", "Drone", "DRlegacy", "Quartz", "QT-4", "DR", "QT", "dr legacy").forEach { name ->
            val result = CompatibleKarrDevice.classify(name)
            assertEquals(name, KarrCandidateTier.OTHER, result.tier)
            assertFalse(name, result.isCandidate)
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
                manufacturerData = listOf(statusFrame(unitTypeCode = 0)),
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
            val payload = statusFrame(model.code)

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
                manufacturerData = listOf(statusFrame(unitTypeCode = 14)),
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
                manufacturerData = listOf("12345".toByteArray(StandardCharsets.US_ASCII)),
            ).tier,
        )
    }

    @Test
    fun rejectsUnrelatedManufacturerRecordsWithCoincidentalUnitTypeBits() {
        listOf(
            "4C0012020003",
            "4C001006341ECA9FD8B1",
            "4C00071106A63D54F99805F4FAE24877629FC21AB3",
            "C40031702105",
        ).forEach { payload ->
            assertEquals(
                payload,
                KarrCandidateTier.OTHER,
                CompatibleKarrDevice.classify(
                    name = null,
                    manufacturerData = listOf(payload.hexBytes()),
                ).tier,
            )
        }
    }

    @Test
    fun manualProbeAllowsConnectablePossibleSignalsWithoutEnablingConfirmedReconnect() {
        val confirmed = KarrDeviceClassification(
            tier = KarrCandidateTier.CONFIRMED_FORMAT,
            evidence = "test",
        )
        val possible = KarrDeviceClassification(
            tier = KarrCandidateTier.POSSIBLE,
            evidence = "test",
        )

        val confirmedDevice = DiscoveredDevice(
            address = "00:00:00:00:00:01",
            name = "DR D029A54D",
            rssi = -50,
            connectable = true,
            classification = confirmed,
        )
        assertTrue(confirmedDevice.manualProbeEligible)
        assertTrue(confirmedDevice.confirmedConnectionEligible)
        assertFalse(
            DiscoveredDevice(
                address = "00:00:00:00:00:02",
                name = null,
                rssi = -50,
                connectable = false,
                classification = confirmed,
            ).manualProbeEligible,
        )
        val possibleDevice = DiscoveredDevice(
            address = "00:00:00:00:00:03",
            name = "KARR Security",
            rssi = -50,
            connectable = true,
            classification = possible,
        )
        assertTrue(possibleDevice.manualProbeEligible)
        assertFalse(possibleDevice.confirmedConnectionEligible)
    }

    private fun statusFrame(unitTypeCode: Int): ByteArray {
        val encodedType = unitTypeCode + 1
        return "12345".toByteArray(StandardCharsets.US_ASCII).apply {
            this[0] = (this[0].toInt() or ((encodedType and 0x03) shl 6)).toByte()
            this[1] = (this[1].toInt() or (((encodedType ushr 2) and 0x03) shl 6)).toByte()
        }
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { octet -> octet.toInt(16).toByte() }.toByteArray()
}
