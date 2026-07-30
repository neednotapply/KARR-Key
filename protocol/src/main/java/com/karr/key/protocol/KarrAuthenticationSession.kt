package com.karr.key.protocol

sealed interface AuthenticationAction {
    data object None : AuthenticationAction

    data class Send(
        val command: QtCommand,
        val payload: ByteArray = byteArrayOf(),
    ) : AuthenticationAction

    data object Authenticated : AuthenticationAction

    data class Failed(val reason: String) : AuthenticationAction
}

class KarrAuthenticationSession(
    private val authenticator: DeviceAuthenticator,
) {
    private var challenge: ByteArray? = null
    private var awaitingResult = false

    suspend fun onPacket(
        unitSerial: String,
        packet: QtPacket,
    ): AuthenticationAction = when (packet.command) {
        QtCommand.REQUEST_AUTH -> begin(packet.payload)
        QtCommand.READ_MODE -> answer(unitSerial, packet.payload)
        QtCommand.AUTH_SUCCESS -> complete()
        QtCommand.AUTH_FAILED -> reject()
        else -> AuthenticationAction.None
    }

    fun reset() {
        challenge?.fill(0)
        challenge = null
        awaitingResult = false
    }

    private fun begin(payload: ByteArray): AuthenticationAction {
        reset()
        if (payload.size != LEGACY_CHALLENGE_BYTES) {
            return AuthenticationAction.Failed(
                "This unit requested unsupported ${payload.size}-byte authentication. " +
                    "The locally provisioned legacy flow requires an 8-byte challenge.",
            )
        }
        challenge = payload.copyOf()
        return AuthenticationAction.Send(QtCommand.READ_MODE)
    }

    private suspend fun answer(
        unitSerial: String,
        payload: ByteArray,
    ): AuthenticationAction {
        val activeChallenge = challenge ?: return AuthenticationAction.None
        if (payload.isEmpty()) {
            reset()
            return AuthenticationAction.Failed(
                "The unit returned an empty mode response during authentication.",
            )
        }

        val mode = KarrDeviceMode.fromStatusByte(payload[0])
        val response = authenticator.answerChallenge(
            unitSerial = unitSerial,
            challenge = activeChallenge,
            mode = mode,
        ).getOrElse { failure ->
            reset()
            return AuthenticationAction.Failed(
                failure.message ?: "The local challenge response could not be generated.",
            )
        }
        activeChallenge.fill(0)
        challenge = null
        awaitingResult = true
        return AuthenticationAction.Send(
            command = QtCommand.PROVIDE_AUTH,
            payload = response,
        )
    }

    private fun complete(): AuthenticationAction {
        if (!awaitingResult) {
            return AuthenticationAction.None
        }
        reset()
        return AuthenticationAction.Authenticated
    }

    private fun reject(): AuthenticationAction {
        if (challenge == null && !awaitingResult) {
            return AuthenticationAction.None
        }
        reset()
        return AuthenticationAction.Failed(
            "KARR application authentication was rejected.",
        )
    }

    private companion object {
        const val LEGACY_CHALLENGE_BYTES = 8
    }
}
