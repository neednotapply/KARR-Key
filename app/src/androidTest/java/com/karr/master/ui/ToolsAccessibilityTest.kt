package com.karr.master.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
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
            selectedRole = null,
        )

        composeRule.onNodeWithText("Tools are locked").assertIsDisplayed()
        composeRule.onNodeWithText("Open access keys").assertIsDisplayed()
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
            selectedRole = UserRole.CUSTOMER,
        )

        composeRule.onNodeWithText("Customer").assertIsDisplayed()
        composeRule.onNodeWithText("Dealer").assertIsDisplayed()
        composeRule.onNodeWithText("Installer").assertIsDisplayed()
        composeRule.onNodeWithText("Local workspace").assertIsDisplayed()
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun setToolsContent(
        session: Session,
        selectedRole: UserRole?,
    ) {
        composeRule.setContent {
            KarrMasterTheme(darkTheme = false) {
                Surface {
                    AdaptiveToolsScreen(
                        session = session,
                        selectedRole = selectedRole,
                        selectedFeature = null,
                        vehicles = emptyList(),
                        jobs = emptyList(),
                        deviceAuthenticated = false,
                        query = "",
                        selectedCategory = null,
                        listState = rememberLazyListState(),
                        onRoleSelected = {},
                        onQueryChanged = {},
                        onCategorySelected = {},
                        onOpenFeature = {},
                        onOpenAccessKeys = {},
                        detailContent = { Text(it.title) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}
