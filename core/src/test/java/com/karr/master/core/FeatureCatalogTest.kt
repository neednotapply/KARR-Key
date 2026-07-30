package com.karr.master.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogTest {
    private val removedBackendFeatureIds = setOf(
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
        "installer.control_room",
    )

    @Test
    fun exposesOfflineCapableCatalogForEachRole() {
        assertEquals(16, FeatureCatalog.customer.size)
        assertEquals(18, FeatureCatalog.dealer.size)
        assertEquals(23, FeatureCatalog.installer.size)

        UserRole.entries.forEach { role ->
            val features = FeatureCatalog.forRole(role)

            assertTrue(features.isNotEmpty())
            assertTrue(features.all { it.requiredRole == role })
            assertTrue(features.all { it.category.isNotBlank() })
        }
    }

    @Test
    fun excludesRecoveredBackendDependentWorkflows() {
        assertTrue(FeatureCatalog.all.none { it.id in removedBackendFeatureIds })
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
        assertTrue(Capability.DEALER_INVENTORY !in capabilities)
    }
}
