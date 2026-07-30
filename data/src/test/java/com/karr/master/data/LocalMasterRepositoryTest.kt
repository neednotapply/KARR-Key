package com.karr.master.data

import com.karr.master.core.Capability
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMasterRepositoryTest {
    private val repository = LocalMasterRepository()

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
