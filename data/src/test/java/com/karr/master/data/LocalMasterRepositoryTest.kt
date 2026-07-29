package com.karr.master.data

import com.karr.master.core.Capability
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
        assertEquals("Offline workspace", session.displayName)
        assertTrue(Capability.CUSTOMER_CONTROL in session.capabilities)
        assertTrue(Capability.DEALER_INVENTORY in session.capabilities)
        assertTrue(Capability.INSTALLER_FITMENT in session.capabilities)
        assertTrue(Capability.LOCAL_RECORDS in session.capabilities)
    }

    @Test
    fun localWorkspaceDoesNotInventRemoteRecords() {
        assertTrue(repository.vehicles().isEmpty())
        assertTrue(repository.jobs().isEmpty())
    }
}
