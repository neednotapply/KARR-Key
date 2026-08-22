package com.karr.key.protocol

import java.util.Locale

enum class KarrUnitFamily {
    QT,
    DR,
    CIU,
    PROGRAMMER,
    UNKNOWN,
}

data class KarrUnitModel private constructor(
    val code: Int,
    val displayName: String,
    val family: KarrUnitFamily,
) {
    val isRecognized: Boolean
        get() = family != KarrUnitFamily.UNKNOWN

    companion object {
        val QT6_CAN = KarrUnitModel(0, "QT6 CAN", KarrUnitFamily.QT)
        val QT6_BLUE = KarrUnitModel(1, "QT6 BLUE", KarrUnitFamily.QT)
        val QT6_GREEN = KarrUnitModel(2, "QT6 GREEN", KarrUnitFamily.QT)
        val QT6_RED = KarrUnitModel(3, "QT6 RED", KarrUnitFamily.QT)
        val DR1_CAN = KarrUnitModel(4, "DR1 CAN", KarrUnitFamily.DR)
        val VW_PROGRAMMER = KarrUnitModel(5, "VW programmer", KarrUnitFamily.PROGRAMMER)
        val CIU = KarrUnitModel(6, "CIU", KarrUnitFamily.CIU)
        val DR2_ESP = KarrUnitModel(7, "DR2 ESP", KarrUnitFamily.DR)
        val QT7_GREEN = KarrUnitModel(8, "QT7 GREEN", KarrUnitFamily.QT)

        val known: List<KarrUnitModel> = listOf(
            QT6_CAN,
            QT6_BLUE,
            QT6_GREEN,
            QT6_RED,
            DR1_CAN,
            VW_PROGRAMMER,
            CIU,
            DR2_ESP,
            QT7_GREEN,
        )

        fun fromTypeByte(value: Byte): KarrUnitModel =
            fromCode(value.toInt() and UNIT_TYPE_MASK)

        fun fromCode(code: Int): KarrUnitModel {
            val normalized = code and UNIT_TYPE_MASK
            return known.firstOrNull { model -> model.code == normalized }
                ?: KarrUnitModel(
                    code = normalized,
                    displayName =
                        "Unknown type 0x${
                            normalized.toString(16).uppercase(Locale.US)
                        }",
                    family = KarrUnitFamily.UNKNOWN,
                )
        }

        private const val UNIT_TYPE_MASK = 0x0f
    }
}
