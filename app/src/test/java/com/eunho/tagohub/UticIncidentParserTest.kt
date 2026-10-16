package com.eunho.tagohub

import com.eunho.tagohub.data.UticIncidentParser
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UticIncidentParserTest {
    @Test
    fun parsesCoordinatesAndIncidentDetails() {
        val records = UticIncidentParser.parse(
            "\uFEFF  \n" + """
            <result>
              <record>
                <incidentId>incident-1</incidentId>
                <incidentTitle>[사고] 도로 통제</incidentTitle>
                <addressJibun>서울시 중구</addressJibun>
                <locationDataX>127.001</locationDataX>
                <locationDataY>37.501</locationDataY>
                <startDate>2026년 10월 16일</startDate>
              </record>
            </result>
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals("incident-1", records.single().id)
        assertEquals(127.001, records.single().lng, 0.000001)
        assertEquals(37.501, records.single().lat, 0.000001)
        assertEquals("서울시 중구", records.single().address)
    }

    @Test
    fun skipsRecordsWithoutValidCoordinates() {
        val records = UticIncidentParser.parse(
            """
            <result>
              <record><incidentId>no-coordinates</incidentId></record>
              <record><incidentId>invalid</incidentId><locationDataX>500</locationDataX><locationDataY>91</locationDataY></record>
            </result>
            """.trimIndent(),
        )

        assertTrue(records.isEmpty())
    }

    @Test
    fun reportsApiErrorsWhenThereAreNoRecords() {
        try {
            UticIncidentParser.parse("<result><resultCode>02</resultCode><resultMsg>유효하지 않은 KEY</resultMsg></result>")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("유효하지 않은 KEY"))
            return
        }
        throw AssertionError("UTIC API error should be reported")
    }
}
