package com.karr.master.ui

import com.karr.master.core.FeatureCatalog
import com.karr.master.core.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarrNavigationStateTest {
    @Test
    fun shellAlwaysExposesThreeStableDestinationsAndStartsAtHome() {
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.TOOLS,
                TopLevelDestination.DEVICE,
            ),
            TopLevelDestination.entries,
        )
        val navigation = KarrNavigationState()
        assertEquals(TopLevelDestination.HOME, navigation.selectedTopLevel)
        assertEquals(AppRoute.Home, navigation.currentRoute)
    }

    @Test
    fun directRoleShortcutSelectsRequestedToolsRoot() {
        val navigation = KarrNavigationState()

        navigation.openTools(UserRole.INSTALLER)

        assertEquals(TopLevelDestination.TOOLS, navigation.selectedTopLevel)
        assertEquals(AppRoute.Tools(UserRole.INSTALLER), navigation.currentRoute)
    }

    @Test
    fun topLevelSwitchPreservesFeatureStackAndReselectReturnsToRoot() {
        val navigation = KarrNavigationState()
        val feature = FeatureCatalog.dealer.first()
        navigation.openFeature(feature)

        navigation.openDevice(DeviceSection.DIAGNOSTICS)
        navigation.selectTopLevel(TopLevelDestination.TOOLS)

        assertEquals(
            AppRoute.FeatureDetail(feature.id, UserRole.DEALER),
            navigation.currentRoute,
        )

        navigation.selectTopLevel(TopLevelDestination.TOOLS)

        assertEquals(AppRoute.Tools(UserRole.DEALER), navigation.currentRoute)
    }

    @Test
    fun navigateUpReturnsFromFeatureToMatchingRoleCatalog() {
        val navigation = KarrNavigationState()
        val feature = FeatureCatalog.customer.first()
        navigation.openFeature(feature)

        assertTrue(navigation.navigateUp())
        assertEquals(AppRoute.Tools(UserRole.CUSTOMER), navigation.currentRoute)
        assertFalse(navigation.navigateUp())
    }

    @Test
    fun unavailableSelectedRoleFallsBackAndLockedSessionKeepsToolsRoot() {
        val navigation = KarrNavigationState()
        navigation.openTools(UserRole.DEALER)

        navigation.normalizeRoles(setOf(UserRole.CUSTOMER))
        assertEquals(AppRoute.Tools(UserRole.CUSTOMER), navigation.currentRoute)

        navigation.normalizeRoles(emptySet())
        assertEquals(AppRoute.Tools(null), navigation.currentRoute)
    }

    @Test
    fun allFiftySevenCatalogEntriesAreVisibleAndOpenMatchingDetailRoutes() {
        assertEquals(16, toolsFeaturesFor(UserRole.CUSTOMER).size)
        assertEquals(18, toolsFeaturesFor(UserRole.DEALER).size)
        assertEquals(23, toolsFeaturesFor(UserRole.INSTALLER).size)

        val navigation = KarrNavigationState()
        UserRole.entries.forEach { role ->
            assertEquals(
                FeatureCatalog.forRole(role).map { it.id },
                toolsFeaturesFor(role).map { it.id },
            )
            toolsFeaturesFor(role).forEach { feature ->
                navigation.openFeature(feature)
                assertEquals(
                    AppRoute.FeatureDetail(feature.id, role),
                    navigation.currentRoute,
                )
            }
        }
    }
}
