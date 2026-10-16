package com.eunho.tagohub

import com.eunho.tagohub.data.ItsRoadParser
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItsRoadParserTest {
    @Test
    fun parsesCoordinatesAndTrafficRowsFromTheSameXmlEnvelope() {
        val xml = """
            <response><header><resultCode>00</resultCode></header><body>
              <item><occrrncId>ev-1</occrrncId><coordX>127.1</coordX><coordY>37.4</coordY><eventType>공사</eventType></item>
              <item><linkId>link-1</linkId><roadName>경부고속도로</roadName><speed>82.5</speed><travelTime>60</travelTime></item>
            </body></response>
        """.trimIndent()

        val event = ItsRoadParser.events(xml).single()
        assertEquals("its_events:ev-1", event.id)
        assertEquals(37.4, event.lat, 0.000001)
        assertEquals(127.1, event.lng, 0.000001)
        assertEquals("경부고속도로", ItsRoadParser.traffic(xml).single().roadName)
        assertEquals(82.5, ItsRoadParser.traffic(xml).single().speedKmh!!, 0.000001)
    }

    @Test
    fun parsesForecastRowsAndNormalizesVslCoordinateOrder() {
        val forecast = ItsRoadParser.forecasts(
            "<response><item><sectionId>12</sectionId><linkId>34</linkId><speed>70</speed></item></response>",
        ).single()
        assertEquals("12:34", forecast.id)
        assertEquals(70.0, forecast.speedKmh!!, 0.000001)

        val vsl = ItsRoadParser.vsl(
            "<response><item><vslId>v1</vslId><coordX>37.5</coordX><coordY>127.2</coordY></item></response>",
        ).single()
        assertEquals(37.5, vsl.lat, 0.000001)
        assertEquals(127.2, vsl.lng, 0.000001)
    }

    @Test
    fun parsesDisasterGeometryAndAssetFacilities() {
        val disaster = ItsRoadParser.disasters(
            "<response><item><eventId>d1</eventId><locationGeometry>LINESTRING(127 37, 129 39)</locationGeometry></item></response>",
        ).single()
        assertEquals(38.0, disaster.lat, 0.000001)
        assertEquals(128.0, disaster.lng, 0.000001)

        val vds = ItsRoadParser.vdsFacilities(
            "VDS - 루프\t관리기관\tvds-1\t고속국도\t경부선 1.0km\t127.1\t37.4\n",
        ).single()
        assertEquals("VDS · vds-1", vds.title)
        assertEquals(37.4, vds.lat, 0.000001)
    }

    @Test
    fun parsesSafetyMarkersAndSectionAssets() {
        val safeDriving = ItsRoadParser.safeDriving(
            "<response><item><occrrncId>s1</occrrncId><startX>127</startX><startY>37</startY><outbrkType>결빙</outbrkType></item></response>",
        ).single()
        assertEquals(37.0, safeDriving.lat, 0.000001)

        val dangerousCar = ItsRoadParser.dangerousCars(
            "<response><item><sntcManageNo>d1</sntcManageNo><xcrdnt>127.2</xcrdnt><ycrdnt>37.2</ycrdnt></item></response>",
        ).single()
        assertEquals(127.2, dangerousCar.lng, 0.000001)

        val ice = ItsRoadParser.iceSections(
            "id,a,b,c,road,length,startLat,startLng,endLat,endLng\n1,a,b,c,국도1호선,2,37.0,127.0,37.2,127.2",
        ).single()
        assertEquals(37.1, ice.lat, 0.000001)

        val fog = ItsRoadParser.fogSections("노선,이정,구간\n영동선,216~229,대관령IC~강릉SA").single()
        assertEquals(216.0, fog.startKm!!, 0.000001)
        assertEquals(229.0, fog.endKm!!, 0.000001)
    }

    @Test
    fun rejectsApiErrorsInsteadOfReturningEmptyResults() {
        try {
            ItsRoadParser.traffic("<response><resultCode>99</resultCode><resultMsg>인증 오류</resultMsg></response>")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("99"))
            assertTrue(e.message.orEmpty().contains("인증 오류"))
            return
        }
        throw AssertionError("ITS API error should be reported")
    }
}
