package com.karr.key.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patched-unit signature is decided by [isPatchedUnitDisconnect]. These cases mirror real
 * captured hardware behaviour: a vulnerable unit (DR D029A54D) issues the legacy 8-byte challenge
 * and authenticates, while a patched unit (DR 0F9ACB56) accepts the UART link but drops it — silent
 * or after a non-legacy frame — before any legacy `REQUEST_AUTH`.
 */
class PatchedUnitDetectionTest {
    private val subscribed = BleSessionState.UartSubscribed(
        address = "74:C9:0F:9A:CB:56",
        name = "DR 0F9ACB56",
    )

    @Test
    fun patchedWhenLinkDropsAfterUartSubscribeWithoutLegacyChallenge() {
        assertTrue(
            isPatchedUnitDisconnect(
                uartSubscribed = true,
                sawLegacyAuthChallenge = false,
                isReconnect = false,
                previousState = subscribed,
            ),
        )
    }

    @Test
    fun notPatchedWhenUnitIssuedTheLegacyChallenge() {
        // The vulnerable unit sent REQUEST_AUTH; a later drop must not be misread as patched.
        assertFalse(
            isPatchedUnitDisconnect(
                uartSubscribed = true,
                sawLegacyAuthChallenge = true,
                isReconnect = false,
                previousState = BleSessionState.AuthenticationRequired(
                    address = "44:B7:D0:29:A5:4D",
                    name = "DR D029A54D",
                    challengeBytes = 8,
                ),
            ),
        )
    }

    @Test
    fun notPatchedWhenTheLinkNeverReachedUartSubscription() {
        // An early failure (e.g. service discovery) is an ordinary error, not a patched verdict.
        assertFalse(
            isPatchedUnitDisconnect(
                uartSubscribed = false,
                sawLegacyAuthChallenge = false,
                isReconnect = false,
                previousState = BleSessionState.DiscoveringUart("74:C9:0F:9A:CB:56"),
            ),
        )
    }

    @Test
    fun notPatchedForAutomaticReconnectDrops() {
        assertFalse(
            isPatchedUnitDisconnect(
                uartSubscribed = true,
                sawLegacyAuthChallenge = false,
                isReconnect = true,
                previousState = subscribed,
            ),
        )
    }

    @Test
    fun notPatchedWhenAnAuthenticatedSessionDrops() {
        // A drop after Ready is a lost link to a known-vulnerable unit, not a patched signature.
        assertFalse(
            isPatchedUnitDisconnect(
                uartSubscribed = true,
                sawLegacyAuthChallenge = true,
                isReconnect = false,
                previousState = BleSessionState.Ready(
                    address = "44:B7:D0:29:A5:4D",
                    name = "DR D029A54D",
                ),
            ),
        )
    }
}
