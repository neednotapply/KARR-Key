package com.karr.key.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePolicyTest {
    private val policy = RolePolicy()

    @Test
    fun deniesSafetySensitiveFeatureWithoutAuthenticatedBluetooth() {
        val feature = FeatureCatalog.customer.first { it.id == "customer.controls" }
        val session = Session(
            displayName = "Offline",
            roles = setOf(UserRole.CUSTOMER),
            capabilities = setOf(Capability.CUSTOMER_CONTROL),
        )

        val decision = policy.authorize(
            session = session,
            feature = feature,
            deviceAuthenticated = false,
        )

        assertTrue(decision is AuthorizationDecision.Denied)
    }

    @Test
    fun allowsSafetySensitiveFeatureAfterBluetoothAuthentication() {
        val feature = FeatureCatalog.customer.first { it.id == "customer.controls" }
        val session = Session(
            displayName = "Offline",
            roles = setOf(UserRole.CUSTOMER),
            capabilities = setOf(Capability.CUSTOMER_CONTROL),
        )

        assertEquals(
            AuthorizationDecision.Allowed,
            policy.authorize(
                session = session,
                feature = feature,
                deviceAuthenticated = true,
            ),
        )
    }

    @Test
    fun installerValidationRequiresInstallerRoleCapabilityAndAuthentication() {
        val feature = FeatureCatalog.installer.first {
            it.id == "installer.immobilizer_test"
        }
        val installer = Session(
            displayName = "Installer",
            roles = setOf(UserRole.INSTALLER),
            capabilities = setOf(Capability.INSTALLER_FITMENT),
        )
        val customer = Session(
            displayName = "Customer",
            roles = setOf(UserRole.CUSTOMER),
            capabilities = setOf(Capability.CUSTOMER_CONTROL),
        )

        assertTrue(
            policy.authorize(
                session = installer,
                feature = feature,
                deviceAuthenticated = false,
            ) is AuthorizationDecision.Denied,
        )
        assertTrue(
            policy.authorize(
                session = customer,
                feature = feature,
                deviceAuthenticated = true,
            ) is AuthorizationDecision.Denied,
        )
        assertEquals(
            AuthorizationDecision.Allowed,
            policy.authorize(
                session = installer,
                feature = feature,
                deviceAuthenticated = true,
            ),
        )
    }
}
