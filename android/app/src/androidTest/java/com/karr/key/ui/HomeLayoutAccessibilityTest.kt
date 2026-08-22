package com.karr.key.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.karr.key.ble.BleSessionState
import com.karr.key.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithTag("home-device-card").assertHeightIsAtLeast(170.dp)
        composeRule.onNodeWithText("Set up access").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Enter an access key before connecting to a KARR unit.",
        ).assertIsDisplayed()
        val deviceTitleBounds = composeRule.onNodeWithTag("home-device-title")
            .fetchSemanticsNode().boundsInRoot
        val deviceActionBounds = composeRule.onNodeWithTag("home-device-action")
            .fetchSemanticsNode().boundsInRoot
        val deviceStatusBounds = composeRule.onNodeWithTag("home-device-status")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(deviceActionBounds.height <= deviceTitleBounds.height * 1.25f)
        assertTrue(deviceStatusBounds.top - deviceTitleBounds.bottom <= deviceTitleBounds.height * 0.5f)
        assertTrue(deviceStatusBounds.right >= deviceActionBounds.right)
        composeRule.onNodeWithText("What is KARR/SWDS?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "These devices use universal authentication",
            substring = true,
        ).fetchSemanticsNode()
        composeRule.onNodeWithText("Security research").assertIsDisplayed()
        composeRule.onNodeWithText("Firmware updates").assertIsDisplayed()
        composeRule.onNodeWithText("Developed by").assertIsDisplayed()
        composeRule.onNodeWithText("NeedNotApply").assertIsDisplayed()
        composeRule.onNodeWithText("for The Pirates' Plunder").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "The Pirates' Plunder skull and crossed tusks",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("NeedNotApply profile picture")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Join us on Discord").assertIsDisplayed()
    }

    @Test
    fun creditsAttributionActivatesWatchDogsModeWithoutUsingTheButton() {
        val openedUrls = mutableListOf<String>()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUrls += uri
                    }
                },
            ) {
                KarrKeyTheme(darkTheme = false) {
                    Surface {
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

        composeRule.onNodeWithText("Join us on Discord").performClick()
        (6 downTo 1).forEach { remainingTaps ->
            composeRule.onNodeWithTag("credits-attribution").performTouchInput { click() }
            val expectedMessage = if (remainingTaps == 1) {
                "You are now 1 tap away from Watch Dogs mode."
            } else {
                "You are now $remainingTaps taps away from Watch Dogs mode."
            }
            composeRule.onNodeWithText(expectedMessage).assertIsDisplayed()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("https://www.discord.gg/thepirates"), openedUrls)
            assertFalse(WATCH_DOGS_MODE_URL in openedUrls)
        }

        composeRule.onNodeWithTag("credits-attribution").performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(
                listOf("https://www.discord.gg/thepirates", WATCH_DOGS_MODE_URL),
                openedUrls,
            )
        }
    }
}
