package com.karr.key.data

import com.karr.key.core.Capability
import com.karr.key.core.FeatureCatalog
import com.karr.key.core.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKeyRepositoryTest {
    private val repository = LocalKeyRepository()

    @Test
    fun localWorkspaceContainsOfflineRolesAndCapabilities() {
        val session = repository.localSession()

        assertEquals(UserRole.entries.toSet(), session.roles)
        assertEquals("Local access", session.displayName)
        assertTrue(Capability.CUSTOMER_CONTROL in session.capabilities)
        assertTrue(Capability.DEALER_DEVICE_SERVICE in session.capabilities)
        assertTrue(Capability.INSTALLER_FITMENT in session.capabilities)
        assertEquals(
            FeatureCatalog.capabilitiesFor(UserRole.entries.toSet()),
            session.capabilities,
        )
    }
}
