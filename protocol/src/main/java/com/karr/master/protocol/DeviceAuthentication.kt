package com.karr.master.protocol

/**
 * Offline implementations obtain per-unit key material through explicit local
 * provisioning and keep it in Android Keystore-backed storage. KARR Master
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
