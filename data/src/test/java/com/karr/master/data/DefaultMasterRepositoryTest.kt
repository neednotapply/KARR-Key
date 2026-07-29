package com.karr.master.data

import com.karr.master.core.Capability
import com.karr.master.core.UserRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMasterRepositoryTest {
    private val repository = DefaultMasterRepository()

    @Test
    fun demoSessionContainsExactlyTheSelectedRole() = runBlocking {
        val session = repository.demoSession(UserRole.CUSTOMER)

        assertEquals(setOf(UserRole.CUSTOMER), session.roles)
        assertEquals("Customer demo", session.displayName)
        assertEquals("demo-customer", session.activeAccountId)
        assertTrue(Capability.CUSTOMER_CONTROL in session.capabilities)
        assertFalse(Capability.DEALER_SALES in session.capabilities)
        assertFalse(Capability.INSTALLER_FITMENT in session.capabilities)
    }

    @Test
    fun demoDataIsScopedToTheSelectedRole() = runBlocking {
        val customer = repository.demoSession(UserRole.CUSTOMER)
        val dealer = repository.demoSession(UserRole.DEALER)
        val installer = repository.demoSession(UserRole.INSTALLER)

        assertEquals(1, repository.vehicles(customer).getOrThrow().size)
        assertTrue(repository.jobs(customer).getOrThrow().isEmpty())
        assertEquals(2, repository.vehicles(dealer).getOrThrow().size)
        assertTrue(repository.jobs(dealer).getOrThrow().isEmpty())
        assertTrue(repository.vehicles(installer).getOrThrow().isEmpty())
        assertEquals(2, repository.jobs(installer).getOrThrow().size)
    }
}
