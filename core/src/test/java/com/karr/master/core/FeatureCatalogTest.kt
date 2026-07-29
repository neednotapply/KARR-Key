package com.karr.master.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogTest {
    @Test
    fun exposesCompleteCatalogForEachRole() {
        assertEquals(23, FeatureCatalog.customer.size)
        assertEquals(27, FeatureCatalog.dealer.size)
        assertEquals(24, FeatureCatalog.installer.size)

        UserRole.entries.forEach { role ->
            val features = FeatureCatalog.forRole(role)

            assertTrue(features.isNotEmpty())
            assertTrue(features.all { it.requiredRole == role })
            assertTrue(features.all { it.category.isNotBlank() })
        }
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
}
