package com.karr.key.protocol

import java.util.Locale

/**
 * Offline implementations obtain per-unit key material through explicit local
 * provisioning and keep it in Android Keystore-backed storage. KARR Key
 * never contacts a server and never bundles recovered universal credentials.
 */
interface DeviceKeyProvider {
    suspend fun keyForUnit(
        serial: String,
        purpose: DeviceKeyPurpose,
    ): Result<ByteArray>
}

enum class DeviceKeyPurpose {
    CUSTOMER,
    DEALER,
    PER_UNIT_ENCRYPTION,
}

interface DeviceKeyProvisioning : DeviceKeyProvider {
    fun isProvisioned(purpose: DeviceKeyPurpose): Boolean
    fun provision(purpose: DeviceKeyPurpose, key: ByteArray): Result<Unit>
    fun remove(purpose: DeviceKeyPurpose)
}

enum class KarrDeviceMode(val code: Int) {
    INSTALLER(0),
    DEALER(1),
    USER(2),
    NO_SALE(3),
    BCA(4),
    VALET(5),
    BOOTLOAD(6),
    UNCONFIGURED(7),
    ;

    val keyPurpose: DeviceKeyPurpose
        get() = if (this == USER || this == VALET) {
            DeviceKeyPurpose.CUSTOMER
        } else {
            DeviceKeyPurpose.DEALER
        }

    companion object {
        fun fromStatusByte(value: Byte): KarrDeviceMode =
            entries.first { mode -> mode.code == (value.toInt() and MODE_MASK) }

        private const val MODE_MASK = 0x07
    }
}

interface DeviceAuthenticator {
    suspend fun answerChallenge(
        unitSerial: String,
        challenge: ByteArray,
        mode: KarrDeviceMode,
    ): Result<ByteArray>
}

class LegacyDeviceAuthenticator(
    private val keyProvider: DeviceKeyProvider,
) : DeviceAuthenticator {
    override suspend fun answerChallenge(
        unitSerial: String,
        challenge: ByteArray,
        mode: KarrDeviceMode,
    ): Result<ByteArray> = runCatching {
        require(challenge.size == LEGACY_CHALLENGE_BYTES) {
            "Legacy authentication requires an 8-byte challenge."
        }
        val normalizedSerial = unitSerial.trim().uppercase(Locale.US)
        val key = keyProvider.keyForUnit(normalizedSerial, mode.keyPurpose).getOrThrow()
        try {
            require(key.size == LEGACY_KEY_BYTES) {
                "The provisioned legacy key must contain exactly 16 bytes."
            }
            if (mode.keyPurpose == DeviceKeyPurpose.CUSTOMER) {
                require(normalizedSerial.length >= MIN_CUSTOMER_SERIAL_LENGTH) {
                    "Customer authentication requires a recovered unit serial."
                }
            }

            ByteArray(LEGACY_RESPONSE_BYTES).also { response ->
                for (index in 0 until LEGACY_KEY_BYTES) {
                    var hash = when {
                        mode.keyPurpose != DeviceKeyPurpose.CUSTOMER -> key[index].unsigned()
                        index == 3 -> normalizedSerial[7].code.toUInt()
                        index == 6 -> normalizedSerial[8].code.toUInt()
                        index == 9 -> normalizedSerial[9].code.toUInt()
                        else -> key[index].unsigned()
                    }
                    challenge.forEach { challengeByte ->
                        hash = hash * HASH_MULTIPLIER + challengeByte.unsigned()
                    }
                    response[index * 2] = hash.toByte()
                    response[index * 2 + 1] = (hash shr 8).toByte()
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private fun Byte.unsigned(): UInt = (toInt() and 0xff).toUInt()

    private companion object {
        const val LEGACY_CHALLENGE_BYTES = 8
        const val LEGACY_KEY_BYTES = 16
        const val LEGACY_RESPONSE_BYTES = 32
        const val MIN_CUSTOMER_SERIAL_LENGTH = 10
        const val HASH_MULTIPLIER = 33u
    }
}
