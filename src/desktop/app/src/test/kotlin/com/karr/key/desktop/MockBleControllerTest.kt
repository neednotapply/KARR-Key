package com.karr.key.desktop

import com.karr.key.ble.BleSessionState
import com.karr.key.core.KarrTrace
import com.karr.key.desktop.terminal.TraceLog
import com.karr.key.desktop.transport.MockBleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the "background activity shows up in the Terminal" pipeline:
 * the mock transport must walk the session state machine to Ready and its
 * KarrTrace output must land in the TraceLog that backs the Terminal panel.
 */
class MockBleControllerTest {

    @After
    fun tearDown() {
        KarrTrace.enabled = false
        KarrTrace.sink = KarrTrace.Sink { _, _, _ -> }
    }

    @Test
    fun scanConnectReachesReadyAndCapturesTrace() {
        val log = TraceLog()
        KarrTrace.sink = log.asSink(mirrorToStdout = false)
        KarrTrace.enabled = true

        val controller = MockBleController()
        try {
            controller.startScan()
            runBlocking {
                withTimeout(3_000) {
                    while (controller.devices.value.isEmpty()) delay(20)
                }
                val address = controller.devices.value.first().address
                controller.connect(address)
                withTimeout(5_000) {
                    while (controller.state.value !is BleSessionState.Ready) delay(20)
                }
            }

            val ready = controller.state.value as BleSessionState.Ready
            assertTrue("model should be reported", ready.unitModel != null)

            val lines = log.snapshot()
            assertTrue("scan trace captured", lines.any { it.tag == KarrTrace.SWEEP })
            assertTrue("auth trace captured", lines.any { it.tag == KarrTrace.AUTH })
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun tracingDisabledCapturesNothing() {
        val log = TraceLog()
        KarrTrace.sink = log.asSink(mirrorToStdout = false)
        KarrTrace.enabled = false

        val controller = MockBleController()
        try {
            controller.startScan()
            runBlocking { delay(700) }
            assertTrue("no lines when tracing disabled", log.snapshot().isEmpty())
        } finally {
            controller.shutdown()
        }
    }
}
