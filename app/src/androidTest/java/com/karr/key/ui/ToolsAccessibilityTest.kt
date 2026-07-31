package com.karr.key.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
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
        composeRule.onNodeWithText("Customer").assertIsDisplayed()
        composeRule.onAllNodesWithText("Ready").assertCountEquals(0)
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun setOperationsContent() {
        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    AdaptiveOperationsScreen(
                        selectedOperation = null,
                        deviceAuthenticated = false,
                        unitModel = null,
                        query = "",
                        selectedCategory = null,
                        listState = rememberLazyListState(),
                        onQueryChanged = {},
                        onCategorySelected = {},
                        onOpenOperation = {},
                        detailContent = { Text(it.label) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}
