package com.karr.master.ui

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
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.Session
import com.karr.master.core.UserRole
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 34)
class ToolsAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noKeySessionShowsAccessibleSetupState() {
        setToolsContent(
            session = Session(
                displayName = "Local",
                roles = emptySet(),
                capabilities = emptySet(),
            ),
        )

        composeRule.onNodeWithText("27 of 27 tools").assertIsDisplayed()
        composeRule.onNodeWithText("Vehicle identity").assertIsDisplayed()
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun allRoleSessionShowsAccessibleSharedCatalog() {
        val roles = UserRole.entries.toSet()
        setToolsContent(
            session = Session(
                displayName = "Local",
                roles = roles,
                capabilities = FeatureCatalog.capabilitiesFor(roles),
            ),
        )

        composeRule.onAllNodesWithText("Customer").assertCountEquals(2)
        composeRule.onNodeWithText("Vehicle identity").assertIsDisplayed()
        composeRule.onNodeWithText("Device scan and connection").assertIsDisplayed()
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun setToolsContent(session: Session) {
        composeRule.setContent {
            KarrMasterTheme(darkTheme = false) {
                Surface {
                    AdaptiveToolsScreen(
                        session = session,
                        selectedFeature = null,
                        deviceAuthenticated = false,
                        query = "",
                        selectedCategory = null,
                        listState = rememberLazyListState(),
                        onQueryChanged = {},
                        onCategorySelected = {},
                        onOpenFeature = {},
                        detailContent = { Text(it.title) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}
