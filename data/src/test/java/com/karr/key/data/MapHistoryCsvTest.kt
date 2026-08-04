package com.karr.key.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LARGE_FILE_NOISE_ROWS = 20_000

class MapHistoryCsvTest {
    @Test
    fun encodeProducesWigleCompatibleHeaderAndBleTypedRows() {
        val csv = MapHistoryCsv.encode(listOf(sampleObservation()))
        val lines = csv.trim().lines()

        assertTrue(lines[0].startsWith("WigleWifi-1.4,"))
        assertEquals(
            "\"MAC\",\"SSID\",\"AuthMode\",\"FirstSeen\",\"Channel\",\"RSSI\"," +
                "\"CurrentLatitude\",\"CurrentLongitude\",\"AltitudeMeters\",\"AccuracyMeters\"," +
                "\"Type\",\"KarrTier\",\"KarrEvidence\",\"EstimatedRangeMeters\"," +
                "\"ConfidenceRadiusMeters\",\"LocationProvider\"",
            lines[1],
        )
        assertTrue(lines[2].contains("\"BLE\""))
        assertTrue(lines[2].startsWith("\"44:B7:D0:29:A5:4D\""))
    }

    @Test
    fun decodeRoundTripsAnEncodedExportExactly() {
        val original = listOf(
            sampleObservation(),
            sampleObservation().copy(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = null,
                classificationTier = MapCandidateTier.POSSIBLE,
                rssi = -80,
            ),
        )

        val decoded = MapHistoryCsv.decode(MapHistoryCsv.encode(original))

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.deviceAddress, actual.deviceAddress)
            assertEquals(expected.deviceName, actual.deviceName)
            assertEquals(expected.classificationTier, actual.classificationTier)
            assertEquals(expected.classificationEvidence, actual.classificationEvidence)
            assertEquals(expected.rssi, actual.rssi)
            assertEquals(expected.phoneLatitude, actual.phoneLatitude, 0.0000001)
            assertEquals(expected.phoneLongitude, actual.phoneLongitude, 0.0000001)
            assertEquals(expected.phoneAccuracyMeters, actual.phoneAccuracyMeters, 0.001f)
            assertEquals(expected.locationProvider, actual.locationProvider)
            assertEquals(expected.estimatedRangeMeters, actual.estimatedRangeMeters, 0.0001)
            assertEquals(expected.confidenceRadiusMeters, actual.confidenceRadiusMeters, 0.0001)
            assertEquals(expected.observedUtc, actual.observedUtc)
        }
    }

    @Test
    fun decodeIgnoresNonBleRowsFromAForeignWigleExport() {
        val csv = buildString {
            appendLine("WigleWifi-1.4,appRelease=2.71,model=Pixel,release=15")
            appendLine(
                "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
                    "AltitudeMeters,AccuracyMeters,Type",
            )
            appendLine(
                "AA:AA:AA:AA:AA:AA,HomeWifi,[WPA2-PSK-CCMP][ESS],2024-01-01 10:00:00,6,-55," +
                    "32.715,-117.161,10.0,5.0,WIFI",
            )
            appendLine(
                "BB:BB:BB:BB:BB:BB,DR D029A54D,[BLE],2024-01-01 10:05:00,0,-70," +
                    "32.716,-117.162,0.0,8.0,BLE",
            )
        }

        val decoded = MapHistoryCsv.decode(csv)

        assertEquals(1, decoded.size)
        assertEquals("BB:BB:BB:BB:BB:BB", decoded.single().deviceAddress)
        assertEquals(MapCandidateTier.CONFIRMED_FORMAT, decoded.single().classificationTier)
    }

    @Test
    fun decodeCherryPicksOnlyKarrMatchingDevicesFromAForeignWigleExport() {
        val csv = buildString {
            appendLine("WigleWifi-1.4,appRelease=2.71,model=Pixel,release=15")
            appendLine(
                "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
                    "AltitudeMeters,AccuracyMeters,Type",
            )
            // A real KARR unit, recognized purely from its advertised name.
            appendLine(
                "11:11:11:11:11:11,DR D029A54D,[BLE],2024-01-01 10:00:00,0,-60," +
                    "32.715,-117.161,0.0,5.0,BLE",
            )
            // Ordinary Bluetooth devices a wardriving capture would also contain.
            appendLine(
                "22:22:22:22:22:22,Fitbit Charge 5,[BLE],2024-01-01 10:01:00,0,-65," +
                    "32.715,-117.161,0.0,5.0,BLE",
            )
            appendLine(
                "33:33:33:33:33:33,,[BLE],2024-01-01 10:02:00,0,-72," +
                    "32.715,-117.161,0.0,5.0,BLE",
            )
        }

        val decoded = MapHistoryCsv.decode(csv)

        assertEquals(1, decoded.size)
        assertEquals("11:11:11:11:11:11", decoded.single().deviceAddress)
    }

    @Test
    fun decodeFallsBackToRssiDerivedRangeWhenKarrColumnsAreMissing() {
        val csv = buildString {
            appendLine("WigleWifi-1.4,appRelease=2.71")
            appendLine(
                "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
                    "AltitudeMeters,AccuracyMeters,Type",
            )
            appendLine(
                "CC:CC:CC:CC:CC:CC,QT 1234567,[BLE],2024-01-01 10:05:00,0,-90," +
                    "32.716,-117.162,0.0,12.0,BLE",
            )
        }

        val decoded = MapHistoryCsv.decode(csv)

        assertEquals(1, decoded.size)
        val observation = decoded.single()
        assertTrue(observation.estimatedRangeMeters > 0.0)
        assertTrue(observation.confidenceRadiusMeters >= observation.estimatedRangeMeters)
    }

    @Test
    fun decodeReturnsEmptyForTextWithoutARecognizableHeader() {
        assertEquals(emptyList<LocalMapObservation>(), MapHistoryCsv.decode("not,a,csv,file"))
        assertEquals(emptyList<LocalMapObservation>(), MapHistoryCsv.decode(""))
    }

    @Test
    fun decodeAcceptsALazySequenceDirectly() {
        val header = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude," +
            "AltitudeMeters,AccuracyMeters,Type"
        val karrRow = "44:B7:D0:29:A5:4D,DR D029A54D,[BLE],2024-01-01 10:00:00,0,-67," +
            "36.6861,-121.8044,0.0,10.0,BLE"
        val lines = sequenceOf("WigleWifi-1.4,appRelease=2.71", header, karrRow)

        val decoded = MapHistoryCsv.decode(lines)

        assertEquals(1, decoded.size)
        assertEquals("44:B7:D0:29:A5:4D", decoded.single().deviceAddress)
    }

    @Test
    fun decodeHandlesALargeMixedFileCherryPickingOnlyKarrMatches() {
        // Regression coverage for the real-world shape that OOM'd the previous
        // materialize-everything-into-a-List implementation: a large file (WiGLE exports of a
        // well-scanned town run into the hundreds of thousands of lines) where only a tiny
        // fraction of rows are KARR candidates.
        val karrAddresses = (0 until 25).map { index -> "AA:AA:AA:AA:AA:%02d".format(index) }
        val csv = buildString {
            appendLine("WigleWifi-1.6,appRelease=2.110,model=Pixel 6a,release=17")
            appendLine(
                "MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude," +
                    "CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type",
            )
            karrAddresses.forEach { address ->
                appendLine(
                    "$address,DR ${address.replace(":", "")},,2024-01-01 10:00:00,,,-65," +
                        "36.68,-121.80,0.0,10.0,,,BLE",
                )
            }
            repeat(LARGE_FILE_NOISE_ROWS) { index ->
                val address = "BB:%02d:%02d:%02d:BB:BB".format(
                    index % 256,
                    (index / 256) % 256,
                    (index / 65_536) % 256,
                )
                appendLine(
                    "$address,Random Device $index,,2024-01-01 10:00:00,,,-70," +
                        "36.68,-121.80,0.0,10.0,,,${if (index % 3 == 0) "WIFI" else "BT"}",
                )
            }
        }

        val decoded = MapHistoryCsv.decode(csv)

        assertEquals(karrAddresses.toSet(), decoded.map { it.deviceAddress }.toSet())
    }

    private fun sampleObservation() = LocalMapObservation(
        id = "ignored-on-encode",
        observedUtc = Instant.parse("2024-05-01T12:34:56Z"),
        deviceAddress = "44:B7:D0:29:A5:4D",
        deviceName = "DR D029A54D",
        classificationTier = MapCandidateTier.CONFIRMED_FORMAT,
        classificationEvidence = "Recovered DR/QT serial format",
        rssi = -67,
        phoneLatitude = 36.6777,
        phoneLongitude = -121.8018,
        phoneAccuracyMeters = 12.5f,
        locationProvider = "gps",
        estimatedRangeMeters = 18.3,
        confidenceRadiusMeters = 30.8,
    )
}
