package com.karr.key.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReadCodecTest {
    @Test
    fun roundTripsRecordsWithTrickyText() {
        val records = listOf(
            DeviceReadRecord(
                command = "READ_STATE",
                heading = "Vehicle state",
                lines = listOf(
                    "Ignition: Off",
                    "Note with \"quotes\", a comma, and a \\backslash",
                    "Tab\tand newline\nembedded",
                ),
                unitModel = "DR1_CAN",
                capturedUtc = Instant.parse("2026-08-04T12:00:00Z"),
            ),
            DeviceReadRecord(
                command = "READ_ALL_SETTINGS",
                heading = "Unit settings",
                lines = emptyList(),
                unitModel = null,
                capturedUtc = Instant.parse("2026-08-04T12:05:30Z"),
            ),
        )

        val decoded = DeviceReadCodec.decode(DeviceReadCodec.encode(records))

        assertEquals(records, decoded)
    }

    @Test
    fun encodeStaysOnASingleLine() {
        val encoded = DeviceReadCodec.encode(
            listOf(
                DeviceReadRecord(
                    command = "READ_STATE",
                    heading = "Vehicle state",
                    lines = listOf("Line one\nLine two", "Carriage\rreturn"),
                    unitModel = null,
                    capturedUtc = Instant.parse("2026-08-04T12:00:00Z"),
                ),
            ),
        )

        assertEquals(1, encoded.lines().size)
    }

    @Test
    fun decodeReturnsEmptyForBlankOrMalformedInput() {
        assertEquals(emptyList<DeviceReadRecord>(), DeviceReadCodec.decode(""))
        assertEquals(emptyList<DeviceReadRecord>(), DeviceReadCodec.decode("not json"))
        assertEquals(emptyList<DeviceReadRecord>(), DeviceReadCodec.decode("[{"))
    }

    @Test
    fun decodeSkipsRecordsMissingRequiredFields() {
        // Missing capturedUtc -> that record is dropped, the valid one survives.
        val json = """
            [{"command":"READ_STATE","heading":"h","lines":["a"],"unitModel":null},
             {"command":"READ_MODE","heading":"Unit identity","lines":[],"unitModel":"QT6_CAN","capturedUtc":"2026-08-04T12:00:00Z"}]
        """.trimIndent()

        val decoded = DeviceReadCodec.decode(json)

        assertEquals(1, decoded.size)
        assertEquals("READ_MODE", decoded.single().command)
    }

    @Test
    fun stringListRoundTrips() {
        val values = listOf("a", "b, with comma", "c \"quoted\"", "")
        assertEquals(values, DeviceReadCodec.decodeStringList(DeviceReadCodec.encodeStringList(values)))
    }
}
