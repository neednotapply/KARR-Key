package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.UserRole
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
    fun successfulConnectionFromDeviceOpensUnifiedToolsRootOnce() {
        val navigation = KarrNavigationState()
        navigation.openDevice(DeviceSection.DIAGNOSTICS)

        assertTrue(
            navigation.openToolsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(TopLevelDestination.TOOLS, navigation.selectedTopLevel)
        assertEquals(AppRoute.Tools(null), navigation.currentRoute)
        assertFalse(
            navigation.openToolsAfterSuccessfulConnection(
                wasAuthenticated = true,
                isAuthenticated = true,
            ),
        )
    }

    @Test
    fun authenticationDoesNotRedirectAwayFromNonDevicePages() {
        val navigation = KarrNavigationState()

        assertFalse(
            navigation.openToolsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(AppRoute.Home, navigation.currentRoute)

        navigation.openTools(UserRole.CUSTOMER)
        assertFalse(
            navigation.openToolsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(AppRoute.Tools(UserRole.CUSTOMER), navigation.currentRoute)
    }

    @Test
    fun incompleteConnectionDoesNotLeaveDevicePage() {
        val navigation = KarrNavigationState()
        navigation.openDevice()

        assertFalse(
            navigation.openToolsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = false,
            ),
        )
        assertEquals(AppRoute.Device(), navigation.currentRoute)
    }

    @Test
    fun adjacentTopLevelMovesFollowHomeToolsDeviceOrderAndStopAtEdges() {
        val navigation = KarrNavigationState()

        assertFalse(navigation.moveTopLevelBy(-1))
        assertTrue(navigation.moveTopLevelBy(1))
        assertEquals(TopLevelDestination.TOOLS, navigation.selectedTopLevel)
        assertTrue(navigation.moveTopLevelBy(1))
        assertEquals(TopLevelDestination.DEVICE, navigation.selectedTopLevel)
        assertFalse(navigation.moveTopLevelBy(1))
        assertTrue(navigation.moveTopLevelBy(-1))
        assertEquals(TopLevelDestination.TOOLS, navigation.selectedTopLevel)
    }

    @Test
    fun topLevelMoveDoesNotLeaveAnOpenFeatureDetail() {
        val navigation = KarrNavigationState()
        val feature = FeatureCatalog.installer.first()
        navigation.openFeature(feature)

        assertFalse(navigation.moveTopLevelBy(1))
        assertEquals(
            AppRoute.FeatureDetail(feature.id, UserRole.INSTALLER),
            navigation.currentRoute,
        )
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
    fun unifiedCatalogShowsEveryRoleAndOpensMatchingDetailRoutes() {
        val tools = toolsFeaturesFor()
        assertEquals(28, tools.size)
        assertEquals(FeatureCatalog.all.map { it.id }, tools.map { it.id })
        assertEquals(UserRole.entries.toSet(), tools.map { it.requiredRole }.toSet())

        val navigation = KarrNavigationState()
        tools.forEach { feature ->
            navigation.openFeature(feature)
            assertEquals(
                AppRoute.FeatureDetail(feature.id, feature.requiredRole),
                navigation.currentRoute,
            )
        }
    }

    @Test
    fun unifiedSearchMatchesRoleBadgesAndRetainsCategoryFiltering() {
        val dealerTools = toolsFeaturesFor(query = "dealer")
        assertEquals(7, dealerTools.size)
        assertTrue(dealerTools.all { it.requiredRole == UserRole.DEALER })

        val category = FeatureCatalog.all.first().category
        assertEquals(
            FeatureCatalog.all.filter { it.category == category }.map { it.id },
            toolsFeaturesFor(category = category).map { it.id },
        )
    }
}
