package com.karr.key.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.karr.key.ble.BleSessionState
import com.karr.key.core.Session
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class HomeLayoutAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun standardPhoneHeightKeepsDeviceAndCreditsVisible() {
        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(610.dp),
                    ) {
                        DashboardScreen(
                            session = Session(
                                displayName = "Research",
                                roles = emptySet(),
                                capabilities = emptySet(),
                            ),
                            bleState = BleSessionState.Idle,
                            deviceAuthenticated = false,
                            onOpenDevice = {},
                            onOpenAccessKeys = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Device").assertIsDisplayed()
        composeRule.onNodeWithText("Ready to scan.").assertIsDisplayed()
        composeRule.onNodeWithText("What is KARR/SWDS?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "These devices use universal authentication",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Security research").assertIsDisplayed()
        composeRule.onNodeWithText("Firmware updates").assertIsDisplayed()
        composeRule.onNodeWithText("Developed by").assertIsDisplayed()
        composeRule.onNodeWithText("NeedNotApply").assertIsDisplayed()
        composeRule.onNodeWithText("for The Pirates' Plunder").assertIsDisplayed()
        composeRule.onNodeWithText("Join us online").assertIsDisplayed()
    }
}
