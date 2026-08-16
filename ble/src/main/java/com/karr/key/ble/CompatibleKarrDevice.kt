package com.karr.key.ble

import com.karr.key.protocol.KarrUnitModel
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

enum class KarrCandidateTier(val rank: Int) {
    OTHER(0),
    POSSIBLE(1),
    CONFIRMED_FORMAT(2),
}

data class KarrDeviceClassification(
    val tier: KarrCandidateTier,
    val evidence: String,
    val recoveredName: String? = null,
) {
    val isCandidate: Boolean
        get() = tier != KarrCandidateTier.OTHER
}

/**
 * Classifies every BLE advertisement instead of silently discarding unknowns.
 *
 * "Confirmed format" means that an advertisement matches a format accepted by
 * the recovered KARR apps. It does not prove account ownership, authorization,
 * or that a live GATT connection will succeed.
 */
object CompatibleKarrDevice {
    private val exactDrQtSerial = Regex("^(DR|QT) [A-F0-9]{7,8}$")
    private val dealerBase32Serial =
        Regex("^[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{13}$")

    fun classify(
        name: String?,
        advertisedServices: Collection<UUID> = emptyList(),
        manufacturerData: Collection<ByteArray> = emptyList(),
    ): KarrDeviceClassification {
        val normalizedName = name.normalizedBluetoothName()
        val manufacturerEvidence = manufacturerData
            .asSequence()
            .mapNotNull(::classifyManufacturerPayload)
            .maxByOrNull { it.tier.rank }

        if (normalizedName != null && exactDrQtSerial.matches(normalizedName)) {
            // The name carries the family (DR vs QT) but not the specific model; the model and
            // vulnerable/patched status are only known after connecting, and are layered on later.
            val family = normalizedName.take(2)
            return KarrDeviceClassification(
                tier = KarrCandidateTier.CONFIRMED_FORMAT,
                evidence = "Recovered $family serial format",
                recoveredName = normalizedName,
            )
        }
        if (manufacturerEvidence?.tier == KarrCandidateTier.CONFIRMED_FORMAT) {
            return manufacturerEvidence
        }

        if (normalizedName?.startsWith("DR") == true ||
            normalizedName?.startsWith("QT") == true
        ) {
            return KarrDeviceClassification(
                tier = KarrCandidateTier.POSSIBLE,
                evidence = "Legacy KARR app DR/QT prefix",
                recoveredName = normalizedName,
            )
        }
        if (manufacturerEvidence != null) {
            return manufacturerEvidence
        }
        if (KarrGatt.UART_SERVICE in advertisedServices) {
            return KarrDeviceClassification(
                tier = KarrCandidateTier.POSSIBLE,
                evidence = "Microchip transparent UART service",
            )
        }
        if (normalizedName?.startsWith("KARR") == true ||
            normalizedName?.startsWith("SWDS") == true
        ) {
            return KarrDeviceClassification(
                tier = KarrCandidateTier.POSSIBLE,
                evidence = "KARR/SWDS branded name",
                recoveredName = normalizedName,
            )
        }
        if (normalizedName != null && dealerBase32Serial.matches(normalizedName)) {
            return KarrDeviceClassification(
                tier = KarrCandidateTier.POSSIBLE,
                evidence = "Dealer 13-character serial format",
                recoveredName = normalizedName,
            )
        }
        return KarrDeviceClassification(
            tier = KarrCandidateTier.OTHER,
            evidence = "No recovered KARR identifier",
        )
    }

    private fun classifyManufacturerPayload(payload: ByteArray): KarrDeviceClassification? {
        if (payload.size < MIN_DEALER_STATUS_BYTES) {
            return null
        }

        if (payload.size == DEALER_NAMED_FRAME_BYTES) {
            val embeddedName = recoverDealerEmbeddedName(payload)
            val unitTypeCode = dealerUnitTypeCode(payload, DEALER_NAME_BYTES)
            if (embeddedName != null && unitTypeCode != null) {
                return KarrDeviceClassification(
                    tier = KarrCandidateTier.CONFIRMED_FORMAT,
                    evidence = dealerFrameEvidence(
                        prefix = "Recovered KARR dealer manufacturer frame",
                        unitTypeCode = unitTypeCode,
                    ),
                    recoveredName = embeddedName,
                )
            }
            return null
        }

        if (!hasDealerVinTail(payload, 0)) {
            return null
        }
        val unitTypeCode = dealerUnitTypeCode(payload, 0) ?: return null
        val model = KarrUnitModel.fromCode(unitTypeCode)
        return KarrDeviceClassification(
            tier = if (model.isRecognized) {
                KarrCandidateTier.CONFIRMED_FORMAT
            } else {
                KarrCandidateTier.POSSIBLE
            },
            evidence = dealerFrameEvidence(
                prefix = if (model.isRecognized) {
                    "Recovered KARR dealer manufacturer status frame"
                } else {
                    "Dealer-compatible manufacturer status frame"
                },
                unitTypeCode = unitTypeCode,
            ),
        )
    }

    private fun recoverDealerEmbeddedName(payload: ByteArray): String? {
        val rawName = String(
            payload,
            0,
            DEALER_NAME_BYTES,
            StandardCharsets.UTF_8,
        ).uppercase(Locale.US)

        if (rawName.startsWith("DR ") || rawName.startsWith("QT ")) {
            var recovered = rawName.take(11)
            if (recovered.lastOrNull()?.isUpperHexDigit() != true) {
                recovered = recovered.take(10)
            }
            return recovered.takeIf(exactDrQtSerial::matches)
        }
        return rawName.takeIf(dealerBase32Serial::matches)
    }

    /**
     * Dealer 3.131 stores the unit type in the two high bits of the first two
     * status bytes and rejects only zero. Values 1..9 map to enums known by
     * that app; retaining other nonzero values avoids hiding later hardware.
     */
    private fun dealerUnitTypeCode(payload: ByteArray, offset: Int): Int? {
        if (payload.size < offset + 2) {
            return null
        }
        val encodedType =
            ((payload[offset].toInt() and 0xC0) ushr 6) +
                ((payload[offset + 1].toInt() and 0xC0) ushr 4)
        return encodedType.takeIf { it != 0 }?.minus(1)
    }

    /**
     * The recovered Dealer parser masks the status bits from the first five
     * bytes and decodes the remainder as the numeric last five VIN digits.
     * Unit-type bits alone are common in unrelated manufacturer records, so
     * the VIN-tail shape is required for status-only advertisement evidence.
     */
    private fun hasDealerVinTail(payload: ByteArray, offset: Int): Boolean {
        if (payload.size < offset + DEALER_VIN_TAIL_BYTES) {
            return false
        }
        return (offset until offset + DEALER_VIN_TAIL_BYTES).all { index ->
            (payload[index].toInt() and STATUS_VALUE_MASK) in ASCII_ZERO..ASCII_NINE
        }
    }

    private fun dealerFrameEvidence(prefix: String, unitTypeCode: Int): String {
        val model = KarrUnitModel.fromCode(unitTypeCode)
        return if (model.isRecognized) {
            "$prefix (${model.displayName})"
        } else {
            "$prefix (unrecognized unit type ${model.code})"
        }
    }

    private fun String?.normalizedBluetoothName(): String? =
        this
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf(String::isNotEmpty)

    private fun Char.isUpperHexDigit(): Boolean = this in '0'..'9' || this in 'A'..'F'

    private const val MIN_DEALER_STATUS_BYTES = 5
    private const val DEALER_VIN_TAIL_BYTES = 5
    private const val DEALER_NAME_BYTES = 13
    private const val DEALER_NAMED_FRAME_BYTES = 26
    private const val STATUS_VALUE_MASK = 0x3F
    private const val ASCII_ZERO = 0x30
    private const val ASCII_NINE = 0x39
}
