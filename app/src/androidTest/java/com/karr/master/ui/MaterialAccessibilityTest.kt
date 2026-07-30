package com.karr.master.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 34)
class MaterialAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightThemeSharedStatesPassAccessibilityChecks() {
        setAccessibleContent(darkTheme = false)
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun darkThemeSharedStatesPassAccessibilityChecks() {
        setAccessibleContent(darkTheme = true)
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun rtlSharedStatesPassAccessibilityChecks() {
        setAccessibleContent(
            darkTheme = false,
            layoutDirection = LayoutDirection.Rtl,
        )
        composeRule.enableAccessibilityChecks()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun setAccessibleContent(
        darkTheme: Boolean,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                KarrMasterTheme(darkTheme = darkTheme) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            SectionHeader(
                                title = "Device status",
                                supportingText = "Representative operator controls.",
                            )
                            StatusPill(FeatureAvailability.Available)
                            StatusPill(
                                FeatureAvailability.DeviceRequired(
                                    "Authenticate a compatible unit.",
                                ),
                            )
                            StatusPill(
                                FeatureAvailability.Locked(
                                    "Provision authorized local access.",
                                ),
                            )
                            Button(onClick = {}) {
                                Text("Primary action")
                            }
                            OutlinedButton(onClick = {}) {
                                Text("Secondary action")
                            }
                            TechnicalDetails {
                                CodeSurface("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
                            }
                        }
                    }
                }
            }
        }
    }
}
