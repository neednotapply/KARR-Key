package com.karr.key.ble

/**
 * Returns complete manufacturer-specific AD payloads, including the two-byte
 * Bluetooth company identifier at the start of each payload.
 *
 * The recovered Xamarin Plugin.BLE client classified the raw AD payload. The
 * Android ScanRecord manufacturer-data map removes those first two bytes and
 * exposes them as its integer key, so callers using that map must restore them.
 */
fun manufacturerRecordsFromRawScanRecord(
    scanRecord: ByteArray?,
): List<ByteArray> {
    if (scanRecord == null) {
        return emptyList()
    }

    return buildList {
        var offset = 0
        while (offset < scanRecord.size) {
            val fieldLength = scanRecord[offset].toInt() and 0xff
            if (fieldLength == 0) {
                break
            }

            val nextOffset = offset + fieldLength + 1
            val typeOffset = offset + 1
            if (fieldLength < 1 || nextOffset > scanRecord.size) {
                break
            }

            val type = scanRecord[typeOffset].toInt() and 0xff
            if (type == MANUFACTURER_SPECIFIC_DATA_TYPE) {
                add(scanRecord.copyOfRange(typeOffset + 1, nextOffset))
            }
            offset = nextOffset
        }
    }
}

fun completeManufacturerRecord(
    manufacturerId: Int,
    manufacturerData: ByteArray,
): ByteArray = byteArrayOf(
    (manufacturerId and 0xff).toByte(),
    ((manufacturerId ushr 8) and 0xff).toByte(),
) + manufacturerData

private const val MANUFACTURER_SPECIFIC_DATA_TYPE = 0xff
