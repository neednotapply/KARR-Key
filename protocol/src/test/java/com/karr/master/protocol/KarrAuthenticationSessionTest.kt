package com.karr.master.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KarrAuthenticationSessionTest {
    private val response = ByteArray(32) { index -> (index + 1).toByte() }
    private val authenticator = RecordingAuthenticator(response)

    @Test
    fun performsRecoveredLegacyHandshakeInOrder() = runTest {
        val session = KarrAuthenticationSession(authenticator)
        val challenge = ByteArray(8) { index -> (0x80 + index).toByte() }

        val readMode = session.onPacket(
            unitSerial = SERIAL,
            packet = packet(QtCommand.REQUEST_AUTH, challenge),
        )
        assertTrue(readMode is AuthenticationAction.Send)
        readMode as AuthenticationAction.Send
        assertEquals(QtCommand.READ_MODE, readMode.command)

        val provideAuth = session.onPacket(
            unitSerial = SERIAL,
            packet = packet(QtCommand.READ_MODE, byteArrayOf(KarrDeviceMode.USER.code.toByte())),
        )
        assertTrue(provideAuth is AuthenticationAction.Send)
        provideAuth as AuthenticationAction.Send
        assertEquals(QtCommand.PROVIDE_AUTH, provideAuth.command)
        assertArrayEquals(response, provideAuth.payload)
        assertEquals(SERIAL, authenticator.serial)
        assertEquals(KarrDeviceMode.USER, authenticator.mode)
        assertArrayEquals(challenge, authenticator.challenge)

        assertEquals(
            AuthenticationAction.Authenticated,
            session.onPacket(SERIAL, packet(QtCommand.AUTH_SUCCESS)),
        )
        assertEquals(
            AuthenticationAction.None,
            session.onPacket(SERIAL, packet(QtCommand.AUTH_SUCCESS)),
        )
    }

    @Test
    fun rejectsUnsupportedChallengeBeforeSendingAnything() = runTest {
        val session = KarrAuthenticationSession(authenticator)

        val action = session.onPacket(
            unitSerial = SERIAL,
            packet = packet(QtCommand.REQUEST_AUTH, ByteArray(32)),
        )

        assertTrue(action is AuthenticationAction.Failed)
        assertTrue((action as AuthenticationAction.Failed).reason.contains("unsupported 32-byte"))
    }

    @Test
    fun authFailureCannotLeaveSessionReady() = runTest {
        val session = KarrAuthenticationSession(authenticator)
        session.onPacket(SERIAL, packet(QtCommand.REQUEST_AUTH, ByteArray(8)))
        session.onPacket(
            SERIAL,
            packet(QtCommand.READ_MODE, byteArrayOf(KarrDeviceMode.DEALER.code.toByte())),
        )

        assertTrue(
            session.onPacket(SERIAL, packet(QtCommand.AUTH_FAILED)) is
                AuthenticationAction.Failed,
        )
        assertEquals(
            AuthenticationAction.None,
            session.onPacket(SERIAL, packet(QtCommand.AUTH_SUCCESS)),
        )
    }

    private class RecordingAuthenticator(
        private val response: ByteArray,
    ) : DeviceAuthenticator {
        var serial: String? = null
        var challenge: ByteArray? = null
        var mode: KarrDeviceMode? = null

        override suspend fun answerChallenge(
            unitSerial: String,
            challenge: ByteArray,
            mode: KarrDeviceMode,
        ): Result<ByteArray> = Result.success(response.copyOf()).also {
            serial = unitSerial
            this.challenge = challenge.copyOf()
            this.mode = mode
        }
    }

    private fun packet(
        command: QtCommand,
        payload: ByteArray = byteArrayOf(),
    ) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = command.code,
        payload = payload,
    )

    private companion object {
        const val SERIAL = "DR 12345678"
    }
}
