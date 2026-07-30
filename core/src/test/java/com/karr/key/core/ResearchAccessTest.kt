package com.karr.key.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAccessTest {
    private val base = Session(
        displayName = "Offline",
        roles = UserRole.entries.toSet(),
        capabilities = Capability.entries.toSet(),
    )

    @Test
    fun noProvisionedKeyExposesNoRoleWorkflow() {
        val session = ResearchAccess.restrictSession(
            base = base,
            customerKeyProvisioned = false,
            dealerKeyProvisioned = false,
        )

        assertTrue(session.roles.isEmpty())
        assertTrue(session.capabilities.isEmpty())
    }

    @Test
    fun customerKeyExposesOnlyCustomerWorkflow() {
        val session = ResearchAccess.restrictSession(
            base = base,
            customerKeyProvisioned = true,
            dealerKeyProvisioned = false,
        )

        assertEquals(setOf(UserRole.CUSTOMER), session.roles)
        assertTrue(Capability.CUSTOMER_CONTROL in session.capabilities)
        assertFalse(Capability.DEALER_DEVICE_SERVICE in session.capabilities)
        assertFalse(Capability.INSTALLER_FITMENT in session.capabilities)
    }

    @Test
    fun dealerKeyExposesDealerAndInstallerWorkflows() {
        val session = ResearchAccess.restrictSession(
            base = base,
            customerKeyProvisioned = false,
            dealerKeyProvisioned = true,
        )

        assertEquals(setOf(UserRole.DEALER, UserRole.INSTALLER), session.roles)
        assertTrue(Capability.DEALER_DEVICE_SERVICE in session.capabilities)
        assertTrue(Capability.INSTALLER_FITMENT in session.capabilities)
        assertFalse(Capability.CUSTOMER_CONTROL in session.capabilities)
    }

    @Test
    fun bothKeysExposeEveryOfflineRoleWorkflow() {
        val session = ResearchAccess.restrictSession(
            base = base,
            customerKeyProvisioned = true,
            dealerKeyProvisioned = true,
        )

        assertEquals(UserRole.entries.toSet(), session.roles)
        assertTrue(
            FeatureCatalog.all.all { feature ->
                feature.requiredRole in session.roles &&
                    feature.requiredCapabilities.all(session.capabilities::contains)
            },
        )
    }
}
