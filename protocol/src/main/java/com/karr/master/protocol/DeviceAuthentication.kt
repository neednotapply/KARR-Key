package com.karr.master.protocol

/**
 * Production implementations must retrieve scoped key material through an
 * authenticated server flow and place it in Android Keystore-backed storage.
 * The legacy static keys recovered from older clients are intentionally absent.
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
    INSTALLER,
    PER_UNIT_ENCRYPTION,
}

interface DeviceAuthenticator {
    suspend fun answerChallenge(
        unitSerial: String,
        challenge: ByteArray,
        purpose: DeviceKeyPurpose,
    ): Result<ByteArray>
}
