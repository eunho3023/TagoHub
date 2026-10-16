package com.eunho.tagohub

import com.eunho.tagohub.data.UticRoadRiskParser
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UticRoadRiskParserTest {
    @Test
    fun parsesDocumentedCoordinatesAndWeatherFields() {
        val risks = UticRoadRiskParser.parse(
            """
            {
              "resultCode":"00",
              "items":[{
                "SAFEDATA_ID":"L94100042807",
                "GRADE":"G",
                "PTY":"3",
                "RN1":"5",
                "TH1":"12",
                "LOCATION_X":126.473441710064,
                "LOCATION_Y":35.3282925793755,
                "ADDRESS_NEW":"전라남도 영광군 법성면 신장길 57"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(1, risks.size)
        assertEquals("L94100042807", risks.single().id)
        assertEquals(126.473441710064, risks.single().lng, 0.000001)
        assertEquals(35.3282925793755, risks.single().lat, 0.000001)
        assertEquals("5", risks.single().precipitation)
    }

    @Test
    fun treatsNoCurrentItemsAsAnEmptyResult() {
        assertTrue(UticRoadRiskParser.parse("""{"resultCode":"04","resultMsg":"데이터가 존재하지 않습니다.","items":[]}""").isEmpty())
        assertTrue(UticRoadRiskParser.parse("""{"resultCode":"01","resultMsg":"데이터가 존재하지 않습니다.","items":[]}""").isEmpty())
    }

    @Test
    fun reportsApiErrors() {
        try {
            UticRoadRiskParser.parse("""{"resultCode":"02","resultMsg":"유효하지 않은 KEY","items":[]}""")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("유효하지 않은 KEY"))
            return
        }
        throw AssertionError("UTIC API error should be reported")
    }

    @Test
    fun reportsTemporaryServiceErrorsInsteadOfTreatingThemAsNoData() {
        try {
            UticRoadRiskParser.parse("""{"resultCode":"01","resultMsg":"서비스 제공 상태가 원할하지 않습니다.","items":[]}""")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("서비스 제공 상태가 원할하지 않습니다"))
            return
        }
        throw AssertionError("UTIC temporary service error should be reported")
    }
}
