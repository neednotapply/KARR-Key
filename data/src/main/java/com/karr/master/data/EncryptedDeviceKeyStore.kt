package com.karr.master.data

import android.util.Base64
import com.karr.master.protocol.DeviceKeyProvisioning
import com.karr.master.protocol.DeviceKeyPurpose

class EncryptedDeviceKeyStore(
    private val values: SecureValueStore,
) : DeviceKeyProvisioning {
    override fun isProvisioned(purpose: DeviceKeyPurpose): Boolean =
        runCatching { values.get(purpose.storageName()) != null }
            .getOrDefault(false)

    override fun provision(
        purpose: DeviceKeyPurpose,
        key: ByteArray,
    ): Result<Unit> = runCatching {
        require(purpose != DeviceKeyPurpose.PER_UNIT_ENCRYPTION) {
            "Per-unit encrypted authentication requires a unit-scoped provisioning flow."
        }
        require(key.size == LEGACY_KEY_BYTES) {
            "A legacy research key must contain exactly 16 bytes."
        }
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        values.put(purpose.storageName(), encoded)
    }

    override fun remove(purpose: DeviceKeyPurpose) {
        values.remove(purpose.storageName())
    }

    override suspend fun keyForUnit(
        serial: String,
        purpose: DeviceKeyPurpose,
    ): Result<ByteArray> = runCatching {
        require(serial.isNotBlank()) { "A unit serial is required for authentication." }
        val encoded = requireNotNull(values.get(purpose.storageName())) {
            "${purpose.displayName()} authentication material is not provisioned."
        }
        Base64.decode(encoded, Base64.NO_WRAP).also { key ->
            require(key.size == LEGACY_KEY_BYTES) {
                "The locally stored ${purpose.displayName()} key is invalid."
            }
        }
    }

    private fun DeviceKeyPurpose.storageName(): String = when (this) {
        DeviceKeyPurpose.CUSTOMER -> "legacy_customer_auth_v1"
        DeviceKeyPurpose.DEALER -> "legacy_dealer_auth_v1"
        DeviceKeyPurpose.PER_UNIT_ENCRYPTION -> "per_unit_auth_v1"
    }

    private fun DeviceKeyPurpose.displayName(): String = when (this) {
        DeviceKeyPurpose.CUSTOMER -> "Customer"
        DeviceKeyPurpose.DEALER -> "Dealer"
        DeviceKeyPurpose.PER_UNIT_ENCRYPTION -> "Per-unit"
    }

    private companion object {
        const val LEGACY_KEY_BYTES = 16
    }
}
