package com.karr.key.ui

import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.QtSecurityState

internal sealed interface SecurityStateReconciliation {
    data class Confirmed(val state: QtSecurityState) : SecurityStateReconciliation
    data class Different(val state: QtSecurityState) : SecurityStateReconciliation
    data object Unavailable : SecurityStateReconciliation
}

internal suspend fun BleCommandExchange.reconcileSecurityState(
    expected: QtSecurityState,
    timeoutMillis: Long = SECURITY_STATE_TIMEOUT_MILLIS,
    onGattAccepted: suspend () -> Unit = {},
): SecurityStateReconciliation {
    val result = sendAndAwait(
        packet = QtProtocolCodec.generateOriginal(
            QtCommand.READ_MODE,
            byteArrayOf(0),
        ),
        expectedCommand = QtCommand.READ_MODE,
        acceptGenericAck = false,
        timeoutMillis = timeoutMillis,
        onGattAccepted = onGattAccepted,
    )
    val actual = (result as? BleCommandExchangeResult.Acknowledged)
        ?.response
        ?.let(QtResponseDecoder::decode)
        ?.securityState
        ?: return SecurityStateReconciliation.Unavailable

    return if (actual == expected) {
        SecurityStateReconciliation.Confirmed(actual)
    } else {
        SecurityStateReconciliation.Different(actual)
    }
}

private const val SECURITY_STATE_TIMEOUT_MILLIS = 5_000L
