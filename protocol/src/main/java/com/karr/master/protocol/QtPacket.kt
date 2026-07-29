package com.karr.master.protocol

enum class ProtocolMode(val marker: Int) {
    ORIGINAL(0xAA),
    COUNTER(0xBB),
    ENCRYPTED(0x55),
}

data class QtPacket(
    val mode: ProtocolMode,
    val commandCode: Int,
    val payload: ByteArray,
    val counter: Int? = null,
) {
    val command: QtCommand?
        get() = QtCommand.fromCode(commandCode)

    override fun equals(other: Any?): Boolean =
        other is QtPacket &&
            mode == other.mode &&
            commandCode == other.commandCode &&
            payload.contentEquals(other.payload) &&
            counter == other.counter

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + commandCode
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (counter ?: 0)
        return result
    }
}

sealed interface PacketParseResult {
    data class Success(val packet: QtPacket) : PacketParseResult
    data class Failure(val reason: String) : PacketParseResult
}
