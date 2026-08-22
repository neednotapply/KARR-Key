package com.karr.key.protocol

enum class QtErrorCode(
    val code: Int,
    val displayName: String,
    val explanation: String,
) {
    COMMAND_OK(
        code = 0,
        displayName = "Command OK",
        explanation = "The unit did not report an error.",
    ),
    INVALID_COMMAND(
        code = 1,
        displayName = "Invalid command",
        explanation = "The unit does not recognize or permit this command.",
    ),
    INVALID_PARAMETER(
        code = 2,
        displayName = "Invalid parameter",
        explanation = "The unit rejected the command payload.",
    ),
    INVALID_MODE(
        code = 3,
        displayName = "Invalid mode",
        explanation = "The unit's current product or operating mode does not permit this command.",
    ),
    INVALID_SETTING(
        code = 4,
        displayName = "Invalid setting",
        explanation = "The requested setting is not available on this unit.",
    ),
    INVALID_VIN(
        code = 5,
        displayName = "Invalid VIN",
        explanation = "The unit rejected the supplied VIN.",
    ),
    ERROR_NONE(
        code = 0xff,
        displayName = "No error",
        explanation = "The unit did not identify a specific error.",
    ),
    ;

    companion object {
        private val byCode = entries.associateBy(QtErrorCode::code)

        fun fromCode(code: Int): QtErrorCode? = byCode[code and 0xff]

        fun fromAckError(packet: QtPacket): QtErrorCode? {
            if (packet.command != QtCommand.ACK_ERROR || packet.payload.size != 1) {
                return null
            }
            return fromCode(packet.payload[0].toInt())
        }
    }
}
