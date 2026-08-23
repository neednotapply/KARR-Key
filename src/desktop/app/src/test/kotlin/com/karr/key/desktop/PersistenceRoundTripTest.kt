package com.karr.key.desktop

import com.karr.key.core.UserRole
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.data.LocalOperationState
import com.karr.key.data.MapCandidateTier
import com.karr.key.desktop.data.DesktopCommandCompatStore
import com.karr.key.desktop.data.DesktopDeviceProfileStore
import com.karr.key.desktop.data.DesktopMapHistoryStore
import com.karr.key.desktop.data.DesktopOperationStore
import com.karr.key.desktop.data.FileSecureValueStore
import com.karr.key.desktop.data.FileStringCipher
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trips the desktop file stores through the real AES cipher in a temp dir. */
class PersistenceRoundTripTest {
    private val tmp = Files.createTempDirectory("karr-persist-test")
    private val cipher = FileStringCipher(tmp.resolve("secret.key"))

    @Test
    fun secureValueAndDeviceKeyRoundTrip() {
        val secure = FileSecureValueStore(cipher, tmp.resolve("secure.json"))
        secure.put("token", "hunter2")
        assertEquals("hunter2", secure.get("token"))
        secure.remove("token")
        assertNull(secure.get("token"))

        val keys = EncryptedDeviceKeyStore(secure)
        val key = ByteArray(16) { it.toByte() }
        assertTrue(keys.provision(DeviceKeyPurpose.CUSTOMER, key).isSuccess)
        assertTrue(keys.isProvisioned(DeviceKeyPurpose.CUSTOMER))
        val recovered = runBlocking { keys.keyForUnit("DR 1A2B3C4", DeviceKeyPurpose.CUSTOMER) }
        assertTrue(recovered.isSuccess)
        assertTrue(key.contentEquals(recovered.getOrThrow()))
    }

    @Test
    fun operationJournalRoundTrip() {
        val store = DesktopOperationStore(cipher, tmp.resolve("ops.json"))
        val op = store.enqueue(UserRole.CUSTOMER, "READ_STATE", "unit", "AA 00")
        assertEquals(1, store.pending().size)
        store.updateState(op.id, LocalOperationState.SUCCEEDED, attempts = 1)
        assertTrue(store.pending().isEmpty())
        assertEquals(LocalOperationState.SUCCEEDED, store.recent().first().state)
    }

    @Test
    fun mapHistoryRoundTripAndDedup() {
        val store = DesktopMapHistoryStore(cipher, tmp.resolve("map.json"))
        val now = Instant.now()
        repeat(3) { i ->
            store.add(
                observedUtc = now.plusSeconds(i.toLong()),
                deviceAddress = "AA:BB:CC:DD:EE:01",
                deviceName = "DR 1A2B3C4",
                classificationTier = MapCandidateTier.CONFIRMED_FORMAT,
                classificationEvidence = "test",
                rssi = -60,
                phoneLatitude = 32.7,
                phoneLongitude = -117.1,
                phoneAccuracyMeters = 5f,
                locationProvider = "gps",
                estimatedRangeMeters = 10.0,
                confidenceRadiusMeters = 5.0,
            )
        }
        assertEquals(3, store.count())
        // Same spot repeated -> dedup collapses to one.
        val removed = store.deduplicate()
        assertEquals(2, removed)
        assertEquals(1, store.count())
    }

    @Test
    fun deviceProfileMergeAndCompat() {
        val profiles = DesktopDeviceProfileStore(cipher, tmp.resolve("profiles.json"))
        profiles.record("AA:BB:CC:DD:EE:01", unitModelCode = 4, KarrVulnerabilityStatus.UNKNOWN)
        // A later null model must not erase the known one.
        val merged = profiles.record("AA:BB:CC:DD:EE:01", unitModelCode = null, KarrVulnerabilityStatus.VULNERABLE)
        assertEquals(4, merged.unitModelCode)
        assertEquals(KarrVulnerabilityStatus.VULNERABLE, merged.vulnerability)
        assertEquals(1, profiles.all().size)

        val compat = DesktopCommandCompatStore(tmp.resolve("compat.json"))
        compat.record(4, "READ_VIN", ByteArray(0), DesktopCommandCompatStore.OUTCOME_OK)
        compat.record(4, "SET_SECURE_KEY", byteArrayOf(1), "REJ:0x05")
        assertEquals(setOf("READ_VIN"), compat.workingCommands(4))
    }
}
