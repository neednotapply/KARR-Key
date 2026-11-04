package com.karr.key.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchKeyFileTest {
    @Test
    fun parsesKeyDeliveryFile() {
        val file = ResearchKeyFile.parse(
            """{"Customer_Key": "00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff",
                "Dealer_Key": "ff ee dd cc bb aa 99 88 77 66 55 44 33 22 11 00"}""",
        ).getOrThrow()
        assertEquals(0x00.toByte(), file.customer!![0])
        assertEquals(0xFF.toByte(), file.customer!![15])
        assertEquals(0xFF.toByte(), file.dealer!![0])
        assertEquals(0x00.toByte(), file.dealer!![15])
    }

    @Test
    fun acceptsOneKeyAndLooseKeyNames() {
        val dealerOnly = ResearchKeyFile.parse(
            """{"dealer_key": "ff ee dd cc bb aa 99 88 77 66 55 44 33 22 11 00"}""",
        ).getOrThrow()
        assertNull(dealerOnly.customer)
        assertNotNull(dealerOnly.dealer)
    }

    @Test
    fun rejectsBadInput() {
        assertTrue(ResearchKeyFile.parse("not json").isFailure)
        assertTrue(ResearchKeyFile.parse("{}").isFailure)
        assertTrue(ResearchKeyFile.parse("""{"Customer_Key": "00 11"}""").isFailure)
        assertTrue(ResearchKeyFile.parse("""{"Customer_Key": 5}""").isFailure)
    }
}
