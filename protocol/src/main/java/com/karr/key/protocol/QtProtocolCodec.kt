package com.karr.key.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object QtProtocolCodec {
    private const val MAX_PAYLOAD = 65_534

    fun generateOriginal(
        command: QtCommand,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Payload is too large." }
        val length = payload.size + 1
        return ByteArray(payload.size + 5).also { packet ->
            packet[0] = ProtocolMode.ORIGINAL.marker.toByte()
            packet[1] = (length ushr 8).toByte()
            packet[2] = length.toByte()
            packet[3] = command.code.toByte()
            payload.copyInto(packet, destinationOffset = 4)
            packet[packet.lastIndex] = checksum(packet, 1, packet.lastIndex)
        }
    }

    fun generateCounter(
        command: QtCommand,
        payload: ByteArray = byteArrayOf(),
        counter: Int,
    ): ByteArray {
        require(counter in 0..255) { "Counter must fit in one byte." }
        require(payload.size <= MAX_PAYLOAD) { "Payload is too large." }
        val length = payload.size + 1
        return ByteArray(payload.size + 6).also { packet ->
            packet[0] = ProtocolMode.COUNTER.marker.toByte()
            packet[1] = counter.toByte()
            packet[2] = (length ushr 8).toByte()
            packet[3] = length.toByte()
            packet[4] = command.code.toByte()
            payload.copyInto(packet, destinationOffset = 5)
            packet[packet.lastIndex] = checksum(packet, 1, packet.lastIndex)
        }
    }

    fun generateEncrypted(
        command: QtCommand,
        payload: ByteArray = byteArrayOf(),
        counter: Int,
        key: ByteArray,
        iv: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes),
    ): ByteArray {
        require(key.size >= 16) { "Encryption key must contain at least 16 bytes." }
        require(iv.size == 16) { "AES-CBC IV must be 16 bytes." }

        val counterBody = generateCounter(command, payload, counter).copyOfRange(1, payload.size + 6)
        val paddedSize = ((counterBody.size + 15) / 16) * 16
        val padded = counterBody.copyOf(paddedSize)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.copyOfRange(0, 16), "AES"),
            IvParameterSpec(iv),
        )
        val encrypted = cipher.doFinal(padded)
        return ByteArray(1 + iv.size + encrypted.size).also { packet ->
            packet[0] = ProtocolMode.ENCRYPTED.marker.toByte()
            iv.copyInto(packet, destinationOffset = 1)
            encrypted.copyInto(packet, destinationOffset = 17)
        }
    }

    fun parse(
        incoming: ByteArray,
        key: ByteArray? = null,
    ): PacketParseResult {
        if (incoming.isEmpty()) {
            return PacketParseResult.Failure("Packet is empty.")
        }
        return when (incoming[0].unsigned()) {
            ProtocolMode.ORIGINAL.marker -> parseOriginal(incoming)
            ProtocolMode.COUNTER.marker -> parseCounter(incoming)
            ProtocolMode.ENCRYPTED.marker -> parseEncrypted(incoming, key)
            else -> PacketParseResult.Failure("Unknown packet marker.")
        }
    }

    private fun parseOriginal(incoming: ByteArray): PacketParseResult {
        if (incoming.size < 5) {
            return PacketParseResult.Failure("Original packet is too short.")
        }
        val length = (incoming[1].unsigned() shl 8) or incoming[2].unsigned()
        if (length < 1) {
            return PacketParseResult.Failure("Original packet length is invalid.")
        }
        val total = length + 4
        if (incoming.size != total) {
            return PacketParseResult.Failure("Original packet length does not match its frame.")
        }
        if (!hasValidChecksum(incoming, 1, total)) {
            return PacketParseResult.Failure("Original packet checksum mismatch.")
        }
        return PacketParseResult.Success(
            QtPacket(
                mode = ProtocolMode.ORIGINAL,
                commandCode = incoming[3].unsigned(),
                payload = incoming.copyOfRange(4, total - 1),
            ),
        )
    }

    private fun parseCounter(incoming: ByteArray): PacketParseResult {
        if (incoming.size < 6) {
            return PacketParseResult.Failure("Counter packet is too short.")
        }
        return parseCounterBody(
            body = incoming.copyOfRange(1, incoming.size),
            encrypted = false,
            requireExactLength = true,
        )
    }

    private fun parseCounterBody(
        body: ByteArray,
        encrypted: Boolean,
        requireExactLength: Boolean,
    ): PacketParseResult {
        if (body.size < 5) {
            return PacketParseResult.Failure("Counter packet body is too short.")
        }
        val length = (body[1].unsigned() shl 8) or body[2].unsigned()
        if (length < 1) {
            return PacketParseResult.Failure("Counter packet length is invalid.")
        }
        val bodyFrameSize = length + 4
        if (body.size < bodyFrameSize || (requireExactLength && body.size != bodyFrameSize)) {
            return PacketParseResult.Failure("Counter packet length does not match its frame.")
        }
        if (!hasValidChecksum(body, 0, bodyFrameSize)) {
            return PacketParseResult.Failure("Counter packet checksum mismatch.")
        }
        return PacketParseResult.Success(
            QtPacket(
                mode = if (encrypted) ProtocolMode.ENCRYPTED else ProtocolMode.COUNTER,
                counter = body[0].unsigned(),
                commandCode = body[3].unsigned(),
                payload = body.copyOfRange(4, bodyFrameSize - 1),
            ),
        )
    }

    private fun parseEncrypted(
        incoming: ByteArray,
        key: ByteArray?,
    ): PacketParseResult {
        if (key == null || key.size < 16) {
            return PacketParseResult.Failure("No valid encryption key was provided.")
        }
        if (incoming.size < 33) {
            return PacketParseResult.Failure("Encrypted packet is too short.")
        }
        val encryptedSize = incoming.size - 17
        if (encryptedSize % 16 != 0) {
            return PacketParseResult.Failure("Encrypted packet is not block aligned.")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.copyOfRange(0, 16), "AES"),
                IvParameterSpec(incoming.copyOfRange(1, 17)),
            )
            parseCounterBody(
                body = cipher.doFinal(incoming.copyOfRange(17, incoming.size)),
                encrypted = true,
                requireExactLength = false,
            )
        } catch (_: Exception) {
            PacketParseResult.Failure("Encrypted packet could not be decrypted.")
        }
    }

    private fun checksum(
        bytes: ByteArray,
        startInclusive: Int,
        endExclusive: Int,
    ): Byte {
        var sum = 0
        for (index in startInclusive until endExclusive) {
            sum = (sum + bytes[index].unsigned()) and 0xff
        }
        return ((-sum) and 0xff).toByte()
    }

    private fun hasValidChecksum(
        bytes: ByteArray,
        startInclusive: Int,
        endExclusive: Int,
    ): Boolean {
        var sum = 0
        for (index in startInclusive until endExclusive) {
            sum = (sum + bytes[index].unsigned()) and 0xff
        }
        return sum == 0
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}
