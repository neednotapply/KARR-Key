package com.karr.key.ble

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BleAdvertisementDataTest {
    @Test
    fun extractsCompleteManufacturerFieldFromRawScanRecord() {
        val payload = ByteArray(26)
        "DR D029A54Dxx".toByteArray(StandardCharsets.UTF_8).copyInto(payload)
        payload[13] = 0x40
        val scanRecord = byteArrayOf(2, 0x01, 0x06, 27, 0xff.toByte()) +
            payload +
            byteArrayOf(0)

        val records = manufacturerRecordsFromRawScanRecord(scanRecord)

        assertEquals(1, records.size)
        assertArrayEquals(payload, records.single())
    }

    @Test
    fun restoresLittleEndianManufacturerIdRemovedByAndroid() {
        val complete = completeManufacturerRecord(
            manufacturerId = 0x5244,
            manufacturerData = " D029A54Dxx".toByteArray(StandardCharsets.UTF_8),
        )

        assertArrayEquals(
            "DR D029A54Dxx".toByteArray(StandardCharsets.UTF_8),
            complete,
        )
    }

    @Test
    fun reconstructedAndroidManufacturerRecordConfirmsRecoveredDealerFrame() {
        val rawPayload = ByteArray(26)
        "DR D029A54Dxx".toByteArray(StandardCharsets.UTF_8).copyInto(rawPayload)
        rawPayload[13] = 0x40
        val androidManufacturerId =
            (rawPayload[0].toInt() and 0xff) or
                ((rawPayload[1].toInt() and 0xff) shl 8)

        val completePayload = completeManufacturerRecord(
            manufacturerId = androidManufacturerId,
            manufacturerData = rawPayload.copyOfRange(2, rawPayload.size),
        )
        val classification = CompatibleKarrDevice.classify(
            name = null,
            manufacturerData = listOf(completePayload),
        )

        assertEquals(KarrCandidateTier.CONFIRMED_FORMAT, classification.tier)
        assertEquals("DR D029A54D", classification.recoveredName)
    }

    @Test
    fun ignoresMalformedTrailingField() {
        val scanRecord = byteArrayOf(
            3,
            0xff.toByte(),
            0x01,
            0x02,
            8,
            0xff.toByte(),
            0x03,
        )

        val records = manufacturerRecordsFromRawScanRecord(scanRecord)

        assertEquals(1, records.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02), records.single())
    }
}
