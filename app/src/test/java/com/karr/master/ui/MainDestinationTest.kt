package com.karr.master.ui

import com.karr.master.core.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MainDestinationTest {
    @Test
    fun noKeyRolesExposeOnlyLocalAndDevicePages() {
        assertEquals(
            listOf(MainDestination.HOME, MainDestination.DEVICE),
            mainDestinationsFor(emptySet()),
        )
    }

    @Test
    fun customerRoleExposesOnlyCustomerWorkspace() {
        assertEquals(
            listOf(
                MainDestination.HOME,
                MainDestination.CUSTOMER,
                MainDestination.DEVICE,
            ),
            mainDestinationsFor(setOf(UserRole.CUSTOMER)),
        )
    }

    @Test
    fun dealerMaterialRolesExposeDealerAndInstallerWorkspaces() {
        assertEquals(
            listOf(
                MainDestination.HOME,
                MainDestination.DEALER,
                MainDestination.INSTALLER,
                MainDestination.DEVICE,
            ),
            mainDestinationsFor(setOf(UserRole.DEALER, UserRole.INSTALLER)),
        )
    }

    @Test
    fun bothMaterialsExposeEveryWorkspace() {
        assertEquals(
            MainDestination.entries,
            mainDestinationsFor(UserRole.entries.toSet()),
        )
    }
}
