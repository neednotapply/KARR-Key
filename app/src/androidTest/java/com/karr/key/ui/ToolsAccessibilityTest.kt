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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onAllNodesWithText
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
import com.karr.key.protocol.KarrUnitModel
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 34)
class ToolsAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun operationCatalogIsDirectAndDeduplicated() {
        setOperationsContent()

        composeRule.onNodeWithText("21 of 21 operations").assertIsDisplayed()
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
    fun catalogExposesClickableTypeAndWorkflowTags() {
        setOperationsContent()

        val vehicleControlCount = deviceOperationsFor(
            filters = OperationFilters(workflow = "Vehicle controls"),
        ).size
        val readCount = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.READS),
        ).size
        val commandCount = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.COMMANDS),
        ).size
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:reads").performClick()
        composeRule.onNodeWithText("$readCount of 21 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:commands").performClick()
        composeRule.onNodeWithText("$commandCount of 21 operations").assertIsDisplayed()
        composeRule.onNodeWithTag("operation-filter:commands").performClick()
        composeRule.onNodeWithText("21 of 21 operations").assertIsDisplayed()
        composeRule.onNodeWithText("Vehicle controls")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("$vehicleControlCount of 21 operations").assertIsDisplayed()
        composeRule.onNodeWithText("Vehicle controls").performClick()
        composeRule.onNodeWithText("21 of 21 operations").assertIsDisplayed()
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
            .assertTextContains("Not supported")
            .assertIsNotEnabled()
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun setOperationsContent(
        deviceAuthenticated: Boolean = false,
        unitModel: KarrUnitModel? = null,
        query: String = "",
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
                    AdaptiveOperationsScreen(
                        session = session,
                        selectedOperation = null,
                        deviceAuthenticated = deviceAuthenticated,
                        unitModel = unitModel,
                        query = query,
                        filters = filters,
                        listState = rememberLazyListState(),
                        onQueryChanged = {},
                        onFiltersChanged = { filters = it },
                        onOpenOperation = {},
                        detailContent = { Text(it.label) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}
