package com.eunho.tagohub

import com.eunho.tagohub.data.ItsCctvParser
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItsCctvParserTest {
    @Test
    fun parsesDocumentedCctvFieldsAndCoordinates() {
        val cctvs = ItsCctvParser.parse(
            """
            <response>
              <datacount>1</datacount>
              <data>
                <roadsectionid>section-1</roadsectionid>
                <cctvtype>1</cctvtype>
                <cctvurl>https://cctvsec.ktict.co.kr/2/sample</cctvurl>
                <cctvresolution>1280x720</cctvresolution>
                <coordy>37.42889</coordy>
                <cctvformat>HLS</cctvformat>
                <cctvname>성남</cctvname>
                <coordx>127.12361</coordx>
              </data>
            </response>
            """.trimIndent(),
            roadType = "ex",
        )

        assertEquals(1, cctvs.size)
        assertEquals("성남", cctvs.single().name)
        assertEquals("ex:section-1:37.42889:127.12361", cctvs.single().id)
        assertEquals(37.42889, cctvs.single().lat, 0.000001)
        assertEquals(127.12361, cctvs.single().lng, 0.000001)
        assertEquals("https://cctvsec.ktict.co.kr/2/sample", cctvs.single().streamUrl)
        assertEquals("HLS", cctvs.single().format)
    }

    @Test
    fun ignoresUnsupportedMediaAndInvalidCoordinates() {
        val cctvs = ItsCctvParser.parse(
            """
            <response>
              <data><cctvtype>3</cctvtype><cctvurl>https://example.test/image.jpg</cctvurl><coordx>127</coordx><coordy>37</coordy></data>
              <data><cctvtype>1</cctvtype><cctvurl>https://example.test/live.m3u8</cctvurl><coordx>181</coordx><coordy>37</coordy></data>
            </response>
            """.trimIndent(),
            roadType = "its",
        )

        assertTrue(cctvs.isEmpty())
    }

    @Test
    fun reportsApiErrorsInsteadOfTreatingThemAsNoData() {
        try {
            ItsCctvParser.parse(
                "<response><errorCode>4005</errorCode><errorMessage>인증키 오류</errorMessage></response>",
                roadType = "ex",
            )
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("4005"))
            assertTrue(e.message.orEmpty().contains("인증키 오류"))
            return
        }
        throw AssertionError("ITS API error should be reported")
    }
}
