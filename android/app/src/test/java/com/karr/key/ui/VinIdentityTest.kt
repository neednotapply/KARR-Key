package com.karr.key.ui

import com.karr.key.data.parseVpicResponse
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class VinIdentityTest {
    @Test
    fun parsesRelevantIdentityFieldsFromVpicFlatResponse() {
        val decoded = parseVpicResponse(
            vin = "1HGCM82633A004352",
            response = """
                {"Results":[{
                  "ErrorCode":"0",
                  "ErrorText":"0 - VIN decoded clean.",
                  "ModelYear":"2003",
                  "Make":"HONDA",
                  "Model":"Accord",
                  "Trim":"EX-V6",
                  "BodyClass":"Coupe"
                }]}
            """.trimIndent(),
            decodedUtc = Instant.EPOCH,
        )

        assertEquals("2003 HONDA Accord", decoded.title)
        assertEquals("EX-V6 · Coupe", decoded.details)
        assertEquals(Instant.EPOCH, decoded.decodedUtc)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsVpicResponseWithoutVehicleIdentity() {
        parseVpicResponse(
            vin = "1HGCM82633A004352",
            response = """{"Results":[{"ErrorCode":"0","Make":"","Model":""}]}""",
        )
    }
}
