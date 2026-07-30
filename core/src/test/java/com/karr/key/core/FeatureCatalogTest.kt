package com.karr.key.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogTest {
    private val unsupportedOfflineFeatureIds = setOf(
        "customer.account",
        "customer.location",
        "customer.local_history",
        "customer.instructions",
        "customer.contact",
        "customer.alerts",
        "customer.alert_configuration",
        "customer.geofences",
        "customer.configuration",
        "customer.logs",
        "customer.requests",
        "customer.violations",
        "customer.users",
        "dealer.sales",
        "dealer.pending_sales",
        "dealer.unwind",
        "dealer.offline_sales",
        "dealer.bookings",
        "dealer.returns",
        "dealer.tent_mode",
        "dealer.snow_mode",
        "dealer.statistics",
        "dealer.accounts",
        "dealer.sync",
        "dealer.inventory",
        "dealer.vehicle_search",
        "dealer.serial_search",
        "dealer.products",
        "dealer.status",
        "dealer.locations",
        "dealer.artifacts",
        "dealer.firmware",
        "dealer.user_settings",
        "installer.control_room",
        "installer.jobs",
        "installer.schedule",
        "installer.account",
        "installer.product",
        "installer.vehicle",
        "installer.vin",
        "installer.barcode",
        "installer.serial",
        "installer.can_selection",
        "installer.completion",
        "installer.replacement",
        "installer.can_transfer",
        "installer.firmware",
        "installer.offline",
    )

    @Test
    fun exposesBluetoothCapableCatalogForEachRole() {
        assertEquals(11, FeatureCatalog.customer.size)
        assertEquals(7, FeatureCatalog.dealer.size)
        assertEquals(9, FeatureCatalog.installer.size)

        UserRole.entries.forEach { role ->
            val features = FeatureCatalog.forRole(role)

            assertTrue(features.isNotEmpty())
            assertTrue(features.all { it.requiredRole == role })
            assertTrue(features.all { it.category.isNotBlank() })
        }
    }

    @Test
    fun excludesDatabaseAndArtifactDependentWorkflows() {
        assertTrue(FeatureCatalog.all.none { it.id in unsupportedOfflineFeatureIds })
        assertEquals(
            setOf(
                Capability.VEHICLE_READ,
                Capability.CUSTOMER_CONTROL,
                Capability.DEALER_DEVICE_SERVICE,
                Capability.INSTALLER_FITMENT,
                Capability.INSTALLER_REPAIR,
                Capability.INSTALLER_REMOVAL,
                Capability.DEVICE_CONNECT,
                Capability.DEVICE_SETTINGS,
                Capability.DEVICE_DIAGNOSTICS,
            ),
            FeatureCatalog.all.flatMap(FeatureDefinition::requiredCapabilities).toSet(),
        )
    }

    @Test
    fun featureIdsAreUniqueAcrossCatalog() {
        assertEquals(
            FeatureCatalog.all.size,
            FeatureCatalog.all.map(FeatureDefinition::id).toSet().size,
        )
    }

    @Test
    fun roleLookupReturnsCanonicalCatalog() {
        assertSame(FeatureCatalog.customer, FeatureCatalog.forRole(UserRole.CUSTOMER))
        assertSame(FeatureCatalog.dealer, FeatureCatalog.forRole(UserRole.DEALER))
        assertSame(FeatureCatalog.installer, FeatureCatalog.forRole(UserRole.INSTALLER))
    }

    @Test
    fun capabilityLookupContainsEveryFeatureRequirementForSelectedRoles() {
        val roles = setOf(UserRole.CUSTOMER, UserRole.INSTALLER)
        val capabilities = FeatureCatalog.capabilitiesFor(roles)

        assertTrue(
            roles
                .flatMap(FeatureCatalog::forRole)
                .all { feature -> feature.requiredCapabilities.all(capabilities::contains) },
        )
        assertTrue(Capability.DEALER_DEVICE_SERVICE !in capabilities)
    }
}
