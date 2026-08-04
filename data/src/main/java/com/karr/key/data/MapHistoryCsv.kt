package com.karr.key.data

import com.karr.key.ble.CompatibleKarrDevice
import com.karr.key.ble.KarrCandidateTier
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.pow

/**
 * Encodes and decodes [LocalMapObservation] rows as a WiGLE-compatible Wi-Fi/BLE CSV export:
 * a `WigleWifi-1.4` app-info line, the standard WiGLE column header, and one row per
 * observation with `Type=BLE`. Extra trailing columns (KarrTier, KarrEvidence,
 * EstimatedRangeMeters, ConfidenceRadiusMeters, LocationProvider) are ignored by WiGLE's own
 * importer but let a round-trip through this app restore full fidelity.
 *
 * A file without those extra columns (a real WiGLE export, or another tool's wardriving
 * capture) is not trusted at face value: every non-KARR row in a wardriving file is noise, so
 * each BLE row is re-classified from its advertised name with [CompatibleKarrDevice] and
 * dropped unless it matches a recovered KARR identifier.
 */
object MapHistoryCsv {
    fun encode(observations: List<LocalMapObservation>): String {
        val builder = StringBuilder()
        builder.append(APP_INFO_LINE).append(LINE_BREAK)
        builder.append(HEADER_COLUMNS.joinToString(",") { it.csvField() }).append(LINE_BREAK)
        observations.forEach { observation -> builder.append(observation.toCsvRow()).append(LINE_BREAK) }
        return builder.toString()
    }

    fun decode(csv: String): List<LocalMapObservation> = decode(csv.lineSequence())

    /**
     * Streams [lines] one at a time instead of materializing the whole file (a real WiGLE
     * export of a well-scanned town can be hundreds of thousands of lines). Only the small set
     * of KARR-matching rows is ever held in memory; everything else is parsed and discarded
     * immediately. Callers reading from a file should pass a lazy [Sequence], e.g.
     * `BufferedReader.lineSequence()` while the reader is still open.
     */
    fun decode(lines: Sequence<String>): List<LocalMapObservation> {
        val iterator = lines.iterator()
        var columnIndex: Map<String, Int>? = null
        val results = mutableListOf<LocalMapObservation>()

        while (iterator.hasNext()) {
            val line = iterator.next().trimEnd('\r')
            if (line.isBlank()) continue

            val header = columnIndex
            if (header == null) {
                if (line.contains("MAC", ignoreCase = true) && line.contains("RSSI", ignoreCase = true)) {
                    columnIndex = parseCsvLine(line)
                        .mapIndexed { index, name -> name.trim() to index }
                        .toMap()
                }
                continue
            }

            val row = parseCsvLine(line)
            fun column(name: String): String? =
                header[name]?.let(row::getOrNull)?.takeIf(String::isNotBlank)

            val type = column("Type") ?: continue
            if (!type.equals("BLE", ignoreCase = true)) continue

            // Normalize casing: WiGLE exports commonly use lowercase MACs while live BLE
            // scans report uppercase, and matching/grouping downstream is case-sensitive.
            val address = column("MAC")?.uppercase() ?: continue
            val rssi = column("RSSI")?.toIntOrNull() ?: continue
            val latitude = column("CurrentLatitude")?.toDoubleOrNull() ?: continue
            val longitude = column("CurrentLongitude")?.toDoubleOrNull() ?: continue
            val accuracy = column("AccuracyMeters")?.toFloatOrNull() ?: DEFAULT_ACCURACY_METERS
            val observedUtc = column("FirstSeen")?.let(::parseTimestamp) ?: Instant.now()
            val estimatedRange = column("EstimatedRangeMeters")?.toDoubleOrNull()
                ?: fallbackRangeMeters(rssi)
            val confidenceRadius = column("ConfidenceRadiusMeters")?.toDoubleOrNull()
                ?: (estimatedRange + accuracy).coerceAtLeast(MIN_CONFIDENCE_RADIUS_METERS)
            val name = column("SSID")

            // Our own exports always carry KarrTier: trust it, since the original live
            // classification may have used manufacturer bytes a CSV can't represent. A file
            // without it (a foreign WiGLE/wardriving export) is cherry-picked by name instead
            // of importing every device it happened to see.
            val storedTier = column("KarrTier")
                ?.let { encoded -> runCatching { MapCandidateTier.valueOf(encoded) }.getOrNull() }
            val (tier, evidence) = if (storedTier != null) {
                storedTier to (column("KarrEvidence") ?: "Imported from KARR Key export.")
            } else {
                val classification = CompatibleKarrDevice.classify(name = name)
                val mappedTier = classification.tier.toMapCandidateTier() ?: continue
                mappedTier to classification.evidence
            }

            results.add(
                LocalMapObservation(
                    id = "",
                    observedUtc = observedUtc,
                    deviceAddress = address,
                    deviceName = name,
                    classificationTier = tier,
                    classificationEvidence = evidence,
                    rssi = rssi,
                    phoneLatitude = latitude,
                    phoneLongitude = longitude,
                    phoneAccuracyMeters = accuracy,
                    locationProvider = column("LocationProvider") ?: "imported",
                    estimatedRangeMeters = estimatedRange,
                    confidenceRadiusMeters = confidenceRadius,
                ),
            )
        }
        return results
    }

    private fun LocalMapObservation.toCsvRow(): String = listOf(
        deviceAddress,
        deviceName ?: "",
        "[BLE]",
        TIMESTAMP_FORMAT.format(observedUtc),
        "0",
        rssi.toString(),
        phoneLatitude.toString(),
        phoneLongitude.toString(),
        "0.0",
        phoneAccuracyMeters.toString(),
        "BLE",
        classificationTier.name,
        classificationEvidence,
        estimatedRangeMeters.toString(),
        confidenceRadiusMeters.toString(),
        locationProvider,
    ).joinToString(",") { it.csvField() }

    private fun KarrCandidateTier.toMapCandidateTier(): MapCandidateTier? = when (this) {
        KarrCandidateTier.CONFIRMED_FORMAT -> MapCandidateTier.CONFIRMED_FORMAT
        KarrCandidateTier.POSSIBLE -> MapCandidateTier.POSSIBLE
        KarrCandidateTier.OTHER -> null
    }

    private fun parseTimestamp(value: String): Instant =
        runCatching { Instant.from(TIMESTAMP_FORMAT.parse(value)) }
            .recoverCatching { Instant.parse(value) }
            .getOrDefault(Instant.now())

    // Mirrors the app's live RSSI-to-range estimate; used only as an import fallback when a
    // foreign WiGLE file has no EstimatedRangeMeters column of its own.
    private fun fallbackRangeMeters(rssi: Int): Double {
        if (rssi >= 0) return MAX_RANGE_METERS
        val estimate = 10.0.pow((RSSI_ONE_METER_DBM - rssi) / (10.0 * PATH_LOSS_EXPONENT))
        return estimate.coerceIn(MIN_RANGE_METERS, MAX_RANGE_METERS)
    }

    private fun String.csvField(): String = "\"" + replace("\"", "\"\"") + "\""

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                inQuotes -> when {
                    char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }
                    char == '"' -> inQuotes = false
                    else -> current.append(char)
                }
                char == '"' -> inQuotes = true
                char == ',' -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        fields.add(current.toString())
        return fields
    }

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private const val LINE_BREAK = "\r\n"
    private const val APP_INFO_LINE = "WigleWifi-1.4,appRelease=1.0.3,model=KARR Key," +
        "release=1,device=karr-key,display=karr-key,board=karr-key,brand=NeedNotApply"
    private val HEADER_COLUMNS = listOf(
        "MAC", "SSID", "AuthMode", "FirstSeen", "Channel", "RSSI",
        "CurrentLatitude", "CurrentLongitude", "AltitudeMeters", "AccuracyMeters", "Type",
        "KarrTier", "KarrEvidence", "EstimatedRangeMeters", "ConfidenceRadiusMeters",
        "LocationProvider",
    )
    private const val DEFAULT_ACCURACY_METERS = 50f
    private const val MIN_CONFIDENCE_RADIUS_METERS = 5.0
    private const val RSSI_ONE_METER_DBM = -59
    private const val PATH_LOSS_EXPONENT = 2.4
    private const val MIN_RANGE_METERS = 1.0
    private const val MAX_RANGE_METERS = 120.0
}
