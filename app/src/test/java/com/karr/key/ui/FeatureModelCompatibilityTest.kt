package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrUnitModel
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureModelCompatibilityTest {
    @Test
    fun drModelsRejectQtOnlyImmobilizerOutputBeforeExecution() {
        val feature = feature("installer.immobilizer_test")

        assertTrue(
            featureModelCompatibility(feature, KarrUnitModel.DR1_CAN) is
                FeatureModelCompatibility.Unsupported,
        )
        assertTrue(
            featureModelCompatibility(feature, KarrUnitModel.DR2_ESP) is
                FeatureModelCompatibility.Unsupported,
        )
    }

    @Test
    fun qtModelsAllowQtFamilyImmobilizerOutput() {
        val feature = feature("installer.immobilizer_test")

        assertTrue(
            featureModelCompatibility(feature, KarrUnitModel.QT6_CAN) ==
                FeatureModelCompatibility.Supported,
        )
        assertTrue(
            featureModelCompatibility(feature, KarrUnitModel.QT7_GREEN) ==
                FeatureModelCompatibility.Supported,
        )
    }

    @Test
    fun exactModelAndUnknownRequirementsRemainDistinct() {
        val trunk = feature("customer.trunk")

        assertTrue(
            featureModelCompatibility(trunk, KarrUnitModel.DR1_CAN) is
                FeatureModelCompatibility.Unsupported,
        )
        assertTrue(
            featureModelCompatibility(trunk, null) is
                FeatureModelCompatibility.Unknown,
        )
        assertTrue(
            featureModelCompatibility(trunk, KarrUnitModel.QT6_RED) ==
                FeatureModelCompatibility.Supported,
        )
    }

    @Test
    fun unrestrictedCommandsRemainAvailableAcrossModels() {
        val lock = feature("customer.controls")

        assertTrue(
            featureModelCompatibility(lock, KarrUnitModel.DR1_CAN) ==
                FeatureModelCompatibility.Supported,
        )
    }

    @Test
    fun toolsAvailabilitySurfacesUnsupportedConnectedModel() {
        val feature = feature("installer.immobilizer_test")
        val session = Session(
            displayName = "Installer",
            roles = setOf(UserRole.INSTALLER),
            capabilities = FeatureCatalog.capabilitiesFor(setOf(UserRole.INSTALLER)),
        )

        assertTrue(
            featureAvailability(
                session = session,
                feature = feature,
                deviceAuthenticated = true,
                unitModel = KarrUnitModel.DR1_CAN,
            ) is FeatureAvailability.ModelUnsupported,
        )
    }

    private fun feature(id: String): FeatureDefinition =
        FeatureCatalog.all.single { feature -> feature.id == id }
}
