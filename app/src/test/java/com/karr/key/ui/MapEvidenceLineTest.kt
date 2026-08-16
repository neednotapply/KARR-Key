package com.karr.key.ui

import com.karr.key.data.DeviceProfile
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MapEvidenceLineTest {
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
