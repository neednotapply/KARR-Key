package com.karr.master.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePolicyTest {
    private val policy = RolePolicy()

    @Test
    fun deniesSafetySensitiveFeatureInDemoSession() {
        val feature = FeatureCatalog.customer.first { it.id == "customer.controls" }
        val session = Session(
            userId = "demo",
            displayName = "Demo",
            roles = setOf(UserRole.CUSTOMER),
            capabilities = setOf(Capability.CUSTOMER_CONTROL),
            accounts = emptyList(),
            activeAccountId = null,
            liveAuthorization = false,
            expiresAt = null,
            demo = true,
        )

        val decision = policy.authorize(session, feature)

        assertTrue(decision is AuthorizationDecision.Denied)
    }

    @Test
    fun allowsReadFeatureWithRoleAndCapability() {
        val feature = FeatureCatalog.dealer.first { it.id == "dealer.inventory" }
        val session = Session(
            userId = "dealer",
            displayName = "Dealer",
            roles = setOf(UserRole.DEALER),
            capabilities = setOf(Capability.DEALER_INVENTORY),
            accounts = emptyList(),
            activeAccountId = null,
            liveAuthorization = true,
            expiresAt = null,
        )

        assertEquals(AuthorizationDecision.Allowed, policy.authorize(session, feature))
    }
}
