package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarrUnitModelTest {
    @Test
    fun decodesRecoveredReadModeModelTable() {
        assertEquals(
            listOf(
                KarrUnitModel.QT6_CAN,
                KarrUnitModel.QT6_BLUE,
                KarrUnitModel.QT6_GREEN,
                KarrUnitModel.QT6_RED,
                KarrUnitModel.DR1_CAN,
                KarrUnitModel.VW_PROGRAMMER,
                KarrUnitModel.CIU,
                KarrUnitModel.DR2_ESP,
                KarrUnitModel.QT7_GREEN,
            ),
            (0..8).map(KarrUnitModel::fromCode),
        )
    }

    @Test
    fun preservesUnknownLowNibbleWithoutTreatingItAsSupported() {
        val model = KarrUnitModel.fromTypeByte(0x7E)

        assertEquals(0x0E, model.code)
        assertEquals("Unknown type 0xE", model.displayName)
        assertEquals(KarrUnitFamily.UNKNOWN, model.family)
        assertFalse(model.isRecognized)
    }

    @Test
    fun classifiesQtAndDrFamilies() {
        assertEquals(KarrUnitFamily.QT, KarrUnitModel.QT7_GREEN.family)
        assertEquals(KarrUnitFamily.DR, KarrUnitModel.DR1_CAN.family)
        assertEquals(KarrUnitFamily.DR, KarrUnitModel.DR2_ESP.family)
        assertTrue(KarrUnitModel.QT6_RED.isRecognized)
    }
}
