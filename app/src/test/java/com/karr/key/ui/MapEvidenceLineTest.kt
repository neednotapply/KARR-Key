package com.karr.key.ui

import com.karr.key.data.DeviceProfile
import com.karr.key.data.DeviceReadRecord
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapEvidenceLineTest {
    @Test
    fun restoresReportedPositionFromNewestStoredRead() {
        val older = DeviceReadRecord(
            command = "READ_STATE",
            heading = "Vehicle state",
            lines = listOf("Position: 36.100000, -121.100000"),
            unitModel = null,
            capturedUtc = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val newer = older.copy(
            lines = listOf("Position: 36.686112, -121.804468"),
            capturedUtc = Instant.parse("2026-01-02T00:00:00Z"),
        )

        val position = reportedPositionFromReads(listOf(older, newer))

        assertEquals(36.686112, position!!.latitude, 0.0000001)
        assertEquals(-121.804468, position.longitude, 0.0000001)
    }

    @Test
    fun ignoresMalformedOrOutOfRangeStoredPositions() {
        val read = DeviceReadRecord(
            command = "READ_STATE",
            heading = "Vehicle state",
            lines = listOf("Position: 91.0, -121.0", "Battery: 12.5 V"),
            unitModel = null,
            capturedUtc = Instant.EPOCH,
        )

        assertNull(reportedPositionFromReads(listOf(read)))
    }

    @Test
    fun extractsOnlyStructuredVinLinesForMapIdentity() {
        assertEquals("1HGCM82633A004352", vinFromLines(listOf("VIN: 1HGCM82633A004352")))
        assertNull(vinFromLines(listOf("Serial: 1HGCM82633A004352")))
        assertNull(vinFromLines(listOf("VIN: SHORT")))
    }

    private fun profile(
        modelCode: Int?,
        vulnerability: KarrVulnerabilityStatus,
    ) = DeviceProfile(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        unitModelCode = modelCode,
        vulnerability = vulnerability,
        updatedUtc = Instant.EPOCH,
    )

    @Test
    fun keepsAlreadySpecificScanEvidenceWhenNeverConnected() {
        assertEquals(
            "Recovered DR serial format",
            deviceEvidenceLine("Recovered DR serial format", "DR D029A54D", null),
        )
    }

    @Test
    fun upgradesLegacyGenericLineFromDeviceName() {
        // Pre-existing and imported records stored the generic "DR/QT"; derive the family from the
        // device name so they read specifically without a re-scan.
        assertEquals(
            "Recovered DR serial format",
            deviceEvidenceLine("Recovered DR/QT serial format", "DR D029A54D", null),
        )
        assertEquals(
            "Recovered QT serial format",
            deviceEvidenceLine("Recovered DR/QT serial format", "qt abc1234", null),
        )
    }

    @Test
    fun leavesGenericLineWhenFamilyIsNotDerivable() {
        assertEquals(
            "Recovered DR/QT serial format",
            deviceEvidenceLine("Recovered DR/QT serial format", null, null),
        )
        assertEquals(
            "Recovered DR/QT serial format",
            deviceEvidenceLine("Recovered DR/QT serial format", "Unnamed KARR candidate", null),
        )
    }

    @Test
    fun labelsVulnerableWithRecoveredModel() {
        assertEquals(
            "Vulnerable · DR1 CAN",
            deviceEvidenceLine(
                "Recovered DR serial format",
                "DR D029A54D",
                profile(KarrUnitModel.DR1_CAN.code, KarrVulnerabilityStatus.VULNERABLE),
            ),
        )
    }

    @Test
    fun labelsVulnerableWithoutModelWhenTypeIsAbsentOrUnrecognized() {
        // A null model (never read) and an unrecognized type both fall back to the family-free
        // label rather than inventing a model.
        assertEquals(
            "Vulnerable KARR unit",
            deviceEvidenceLine(
                "Recovered QT serial format",
                "QT ABC1234",
                profile(null, KarrVulnerabilityStatus.VULNERABLE),
            ),
        )
        assertEquals(
            "Vulnerable KARR unit",
            deviceEvidenceLine(
                "Recovered QT serial format",
                "QT ABC1234",
                profile(14, KarrVulnerabilityStatus.VULNERABLE),
            ),
        )
    }

    @Test
    fun labelsPatched() {
        assertEquals(
            "Patched · QT6 CAN",
            deviceEvidenceLine(
                "Recovered QT serial format",
                "QT ABC1234",
                profile(KarrUnitModel.QT6_CAN.code, KarrVulnerabilityStatus.PATCHED),
            ),
        )
        assertEquals(
            "Patched KARR unit",
            deviceEvidenceLine(
                "Recovered QT serial format",
                "QT ABC1234",
                profile(null, KarrVulnerabilityStatus.PATCHED),
            ),
        )
    }

    @Test
    fun fallsBackToUpgradedScanEvidenceWhenVerdictUnknown() {
        // An UNKNOWN verdict still benefits from the legacy-line upgrade.
        assertEquals(
            "Recovered DR serial format",
            deviceEvidenceLine(
                "Recovered DR/QT serial format",
                "DR D029A54D",
                profile(KarrUnitModel.DR1_CAN.code, KarrVulnerabilityStatus.UNKNOWN),
            ),
        )
    }
}
