package com.eunho.tagohub

import com.eunho.tagohub.data.UticSafetyRowParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UticSafetyRowParserTest {
    @Test
    fun parsesCoordinatesAndDescriptionFromDocumentedColumns() {
        val point = UticSafetyRowParser.parse(
            "위험요소_도로환경별.xlsx",
            mapOf(
                "A" to "L94100058408",
                "B" to "Y",
                "D" to "경북 고령군",
                "M" to "128.178179137924",
                "N" to "35.6728639624302",
                "O" to "급커브",
            ),
        )

        assertEquals("L94100058408", point?.id?.substringAfter(':'))
        assertEquals("급커브", point?.description)
        assertEquals(128.178179137924, point?.lng ?: 0.0, 0.000001)
        assertEquals(35.6728639624302, point?.lat ?: 0.0, 0.000001)
    }

    @Test
    fun skipsRecordsWithoutCoordinatesOrLocation() {
        assertNull(UticSafetyRowParser.parse("TAAS.xlsx", mapOf("A" to "id", "B" to "N")))
        assertNull(UticSafetyRowParser.parse("TAAS.xlsx", mapOf("A" to "id", "B" to "Y", "M" to "x", "N" to "y")))
    }
}
