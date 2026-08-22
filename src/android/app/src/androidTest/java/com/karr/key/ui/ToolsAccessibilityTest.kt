package com.karr.key.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredDeviceOperation
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 34)
class ToolsAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun operationCatalogIsDirectAndDeduplicated() {
        setOperationsContent()

        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        composeRule.onNodeWithText("Read unit state").assertIsDisplayed()
        composeRule.onAllNodesWithText("Connection").assertCountEquals(0)
        composeRule.onAllNodesWithText("Research access").assertCountEquals(0)
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun sharedOperationShowsRoleBindingsWithoutReadyBadge() {
        setOperationsContent()

        composeRule.onNodeWithText("Read unit state").assertIsDisplayed()
        composeRule.onNodeWithTag("operation:read-state").assertTextContains("Customer")
        composeRule.onAllNodesWithText("Ready").assertCountEquals(0)
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun activeSearchCanBeClearedFromTheSearchField() {
        setOperationsContent(query = "unlock")

        composeRule.onNodeWithContentDescription("Clear search")
            .assertIsDisplayed()
            .performClick()

        composeRule.onAllNodes(hasContentDescription("Clear search")).assertCountEquals(0)
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
    }

    @Test
    fun readCardActionIsDisabledUntilConnected() {
        var openedOperationId: String? = null
        var executedOperationId: String? = null
        setOperationsContent(
            query = "Read unit state",
            onOpenOperation = { operation -> openedOperationId = operation.id },
            onExecuteOperation = { operation -> executedOperationId = operation.id },
        )

        composeRule.onNodeWithTag("operation-action:read-state")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertTextContains("Not connected")

        composeRule.runOnIdle {
            assertEquals(null, executedOperationId)
            assertEquals(null, openedOperationId)
        }
    }

    @Test
    fun readCardActionExecutesWithoutOpeningItsDetailPage() {
        var openedOperationId: String? = null
        var executedOperationId: String? = null
        setOperationsContent(
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
            query = "Read unit state",
            onOpenOperation = { operation -> openedOperationId = operation.id },
            onExecuteOperation = { operation -> executedOperationId = operation.id },
        )

        composeRule.onNodeWithTag("operation-action:read-state")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("read-state", executedOperationId)
            assertEquals(null, openedOperationId)
        }
    }

    @Test
    fun commandCardActionExecutesWithoutOpeningItsDetailPage() {
        var openedOperationId: String? = null
        var executedOperationId: String? = null
        setOperationsContent(
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
            query = "Lock and arm",
            onOpenOperation = { operation -> openedOperationId = operation.id },
            onExecuteOperation = { operation -> executedOperationId = operation.id },
        )

        composeRule.onNodeWithTag("operation-action:lock-doors-00")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("lock-doors-00", executedOperationId)
            assertEquals(null, openedOperationId)
        }
    }

    @Test
    fun catalogExposesClickableTypeAndWorkflowTags() {
        setOperationsContent()

        val readCount = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.READS),
        ).size
        val commandCount = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.COMMANDS),
        ).size
        val unitModeCount = deviceOperationsFor(
            filters = OperationFilters(workflow = UNIT_MODE_FILTER),
        ).size
        composeRule.onAllNodes(hasText("All") and hasClickAction()).assertCountEquals(0)
        listOf("Vehicle controls", "Diagnostics", "Validation", "Unit service").forEach { redundant ->
            composeRule.onAllNodes(hasText(redundant) and hasClickAction()).assertCountEquals(0)
        }
        // The Experimental filter is gone; a tri-state Danger sort stands in its place.
        composeRule.onAllNodesWithText("Experimental").assertCountEquals(0)
        val filterTags = listOf(
            "operation-filter:reads",
            "operation-filter:commands",
            "operation-filter:unit-mode",
            "operation-filter:danger",
        )
        filterTags.forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        val filterTop = composeRule.onNodeWithTag(filterTags.first())
            .fetchSemanticsNode().boundsInRoot.top
        filterTags.drop(1).forEach { tag ->
            assertEquals(
                filterTop,
                composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top,
                0.5f,
            )
        }
        composeRule.onNodeWithTag("operation-filter:reads").performClick()
        composeRule.onNodeWithText("$readCount of 36 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:commands").performClick()
        composeRule.onNodeWithText("$commandCount of 36 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:commands").performClick()
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        clickWorkflowFilter(UNIT_MODE_FILTER)
        composeRule.onNodeWithText("$unitModeCount of 36 operations").assertIsDisplayed()
        clickWorkflowFilter(UNIT_MODE_FILTER)
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        // The Danger sort keeps the full set (it re-orders, not filters) and cycles through three states.
        composeRule.onNodeWithTag("operation-filter:danger").performClick()
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:danger").performClick()
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:danger").performClick()
        composeRule.onNodeWithText("36 of 36 operations").assertIsDisplayed()
        composeRule.onAllNodesWithText("Type: All").assertCountEquals(0)
        composeRule.onAllNodesWithText("Role: All").assertCountEquals(0)
        composeRule.onAllNodesWithText("Vehicle information").assertCountEquals(0)
        composeRule.onAllNodesWithText("Unit inspection").assertCountEquals(0)
        composeRule.onAllNodesWithText("Installation checks").assertCountEquals(0)
        composeRule.onAllNodesWithText("Removal checks").assertCountEquals(0)
    }

    @Test
    fun incompatibleOperationIsLabeledAndDisabledBeforeOpening() {
        setOperationsContent(
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR2_ESP,
            query = "silent lock",
        )

        composeRule.onNodeWithTag("operations-catalog").performScrollToNode(
            hasTestTag("operation:silent-lock-00"),
        )
        composeRule.onNodeWithText("Silent lock").assertIsDisplayed()
        composeRule.onNodeWithTag("operation:silent-lock-00")
            .assertTextContains("Hardware")
            .assertIsNotEnabled()
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun modeRestrictedOperationStaysRunnableAndAutoResolvesOnSend() {
        var executedOperation: RecoveredDeviceOperation? = null
        setOperationsContent(
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
            query = "bump test",
            onExecuteOperation = { operation -> executedOperation = operation },
        )

        composeRule.onNodeWithTag("operations-catalog").performScrollToNode(
            hasTestTag("operation:bump-test-01"),
        )
        // Blocked only by mode, so it reads as runnable and stays enabled — the send auto-switches
        // to a supported mode rather than offering a manual mode change.
        composeRule.onNodeWithTag("operation:bump-test-01").assertTextContains("Send command")
        composeRule.onNodeWithTag("operation-action:bump-test-01")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            // Tapping runs the operation itself; the executor drives the mode. It is no longer a
            // manual CHANGE_MODE step.
            assertEquals(QtCommand.BUMP_TEST, executedOperation?.command)
            assertEquals(listOf(1), executedOperation?.payload)
        }
    }

    private fun clickWorkflowFilter(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun setOperationsContent(
        deviceAuthenticated: Boolean = false,
        unitModel: KarrUnitModel? = null,
        unitMode: KarrDeviceMode? = null,
        query: String = "",
        onOpenOperation: (RecoveredDeviceOperation) -> Unit = {},
        onExecuteOperation: (RecoveredDeviceOperation) -> Unit = {},
    ) {
        val roles = UserRole.entries.toSet()
        val session = Session(
            displayName = "Test access",
            roles = roles,
            capabilities = FeatureCatalog.capabilitiesFor(roles),
        )
        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    var filters by remember { mutableStateOf(OperationFilters()) }
                    var currentQuery by remember { mutableStateOf(query) }
                    AdaptiveOperationsScreen(
                        session = session,
                        selectedOperation = null,
                        deviceAuthenticated = deviceAuthenticated,
                        unitModel = unitModel,
                        unitMode = unitMode,
                        query = currentQuery,
                        filters = filters,
                        listState = rememberLazyListState(),
                        onQueryChanged = { currentQuery = it },
                        onFiltersChanged = { filters = it },
                        onOpenOperation = onOpenOperation,
                        onExecuteOperation = onExecuteOperation,
                        detailContent = { Text(it.label) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}
