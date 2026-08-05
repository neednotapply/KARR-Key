package com.karr.key.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.filters.SdkSuppress
import com.karr.key.ble.BleController
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredWorkflowCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 34)
class ImmobilizerRecoveryAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun immobilizerDetailExposesRecoveryBeforeEnabledCandidate() {
        val operation = RecoveredWorkflowCatalog.operations.single { candidate ->
            candidate.command == QtCommand.VEHICLE_CONTROL && candidate.payload == listOf(2)
        }
        val roles = setOf(UserRole.INSTALLER)
        val session = Session(
            displayName = "Installer",
            roles = roles,
            capabilities = FeatureCatalog.capabilitiesFor(roles),
        )
        var openedOperationId: String? = null

        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    DeviceOperationScreen(
                        session = session,
                        operation = operation,
                        deviceAuthenticated = true,
                        bleState = NoOpReadyState,
                        unitModel = KarrUnitModel.QT6_CAN,
                        unitMode = KarrDeviceMode.INSTALLER,
                        controller = NoOpBleController,
                        operationStore = LocalOperationStore(composeRule.activity),
                        deviceReadStore = LocalDeviceReadStore(composeRule.activity),
                        snackbar = SnackbarHostState(),
                        onOpenOperation = { recovery -> openedOperationId = recovery.id },
                    )
                }
            }
        }

        composeRule.onNodeWithText("RESTORE DRIVABLE STATE")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open recovery: Unlock and disarm")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("unlock-doors-00", openedOperationId)
        }
        composeRule.onNodeWithText("Run once")
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun selectedModeCommandShowsEffectWithoutExpandingCatalogSummary() {
        val operation = RecoveredWorkflowCatalog.operations.single { candidate ->
            candidate.bindings.any { binding ->
                binding.action.targetMode == KarrDeviceMode.BCA
            }
        }
        val roles = setOf(UserRole.INSTALLER)
        val session = Session(
            displayName = "Installer",
            roles = roles,
            capabilities = FeatureCatalog.capabilitiesFor(roles),
        )

        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    DeviceOperationScreen(
                        session = session,
                        operation = operation,
                        deviceAuthenticated = true,
                        bleState = NoOpReadyState,
                        unitModel = KarrUnitModel.QT6_CAN,
                        unitMode = KarrDeviceMode.DEALER,
                        controller = NoOpBleController,
                        operationStore = LocalOperationStore(composeRule.activity),
                        deviceReadStore = LocalDeviceReadStore(composeRule.activity),
                        snackbar = SnackbarHostState(),
                        onOpenOperation = {},
                    )
                }
            }
        }

        assertTrue(
            composeRule.onAllNodesWithText(
                "Sends the recovered Borrowed Car Agreement (BCA) mode transition once, then " +
                    "reads the unit mode back before reporting success.",
            ).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("MODE EFFECT")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(KarrDeviceMode.BCA.effectDescription)
            .performScrollTo()
            .assertIsDisplayed()
        val buttonTop = composeRule.onNodeWithText("Send command")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val requirementsTop = composeRule.onNodeWithText("Requirements")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(buttonTop < requirementsTop)
    }

    @Test
    fun disconnectedOperationRestoresConnectionWarning() {
        val operation = RecoveredWorkflowCatalog.operations.single { candidate ->
            candidate.bindings.any { binding ->
                binding.action.targetMode == KarrDeviceMode.BCA
            }
        }
        val roles = setOf(UserRole.INSTALLER)
        val session = Session(
            displayName = "Installer",
            roles = roles,
            capabilities = FeatureCatalog.capabilitiesFor(roles),
        )

        composeRule.setContent {
            KarrKeyTheme(darkTheme = false) {
                Surface {
                    DeviceOperationScreen(
                        session = session,
                        operation = operation,
                        deviceAuthenticated = false,
                        bleState = BleSessionState.Idle,
                        unitModel = null,
                        unitMode = null,
                        controller = NoOpBleController,
                        operationStore = LocalOperationStore(composeRule.activity),
                        deviceReadStore = LocalDeviceReadStore(composeRule.activity),
                        snackbar = SnackbarHostState(),
                        onOpenOperation = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("DEVICE NOT CONNECTED")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Connect and authenticate a unit.",
        ).performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("UNIT MODE UNKNOWN")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}

private val NoOpReadyState = BleSessionState.Ready(
    address = "00:11:22:33:44:55",
    name = "QT000001",
    unitModel = KarrUnitModel.QT6_CAN,
    unitMode = KarrDeviceMode.INSTALLER,
)

private object NoOpBleController : BleController {
    override val state = MutableStateFlow<BleSessionState>(NoOpReadyState)
    override val devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val scanObservations = MutableSharedFlow<BleScanObservation>()
    override val notifications = MutableSharedFlow<ByteArray>()

    override fun startScan(): Result<Unit> = Result.success(Unit)

    override fun stopScan() = Unit

    override fun connect(address: String): Result<Unit> = Result.success(Unit)

    override fun disconnect() = Unit

    override fun setReconnectAllowed(allowed: Boolean) = Unit

    override fun retryReconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun send(packet: ByteArray): Result<Unit> = Result.success(Unit)

    override fun shutdown() = Unit
}
