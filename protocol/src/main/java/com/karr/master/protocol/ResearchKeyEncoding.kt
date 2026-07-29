package com.karr.master.protocol

object ResearchKeyEncoding {
    fun parseLegacyHex(value: String): Result<ByteArray> = runCatching {
        val normalized = value.filterNot { character ->
            character.isWhitespace() || character == ':' || character == '-'
        }
        require(normalized.length == LEGACY_HEX_CHARACTERS) {
            "Enter exactly 16 hexadecimal key bytes."
        }
        require(normalized.all { character -> character.isHexDigit() }) {
            "The key contains a non-hexadecimal character."
        }
        ByteArray(LEGACY_KEY_BYTES) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val LEGACY_KEY_BYTES = 16
    private const val LEGACY_HEX_CHARACTERS = LEGACY_KEY_BYTES * 2
}
