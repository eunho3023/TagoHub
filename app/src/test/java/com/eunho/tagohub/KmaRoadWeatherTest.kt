package com.eunho.tagohub

import com.eunho.tagohub.data.KmaRoadParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class KmaRoadWeatherTest {
    @Test
    fun bundledStationListHas861Stations() {
        val f = listOf("src/main/assets/kma_road_stations.json", "app/src/main/assets/kma_road_stations.json").map { File(it) }.first { it.exists() }
        val list = KmaRoadParser.stations(f.readText(Charsets.UTF_8))
        assertEquals(861, list.size)
        assertEquals(861, list.map { it.id }.toSet().size)
        assertTrue(list.all { it.route.isNotBlank() && it.name.isNotBlank() })
        assertTrue(list.any { it.route == "서해안고속도로" })
    }

    @Test
    fun parsesWeatherFromRealResponse() {
        val w = KmaRoadParser.weather("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},"body":{"dataType":"JSON","items":{"item":[{"baseDate":"20261021","baseTime":"1435","weatherNm":"현상없음"}]},"pageNo":1,"numOfRows":5,"totalCount":1}}}""")!!
        assertEquals("현상없음", w.weather)
        assertEquals("14:35 기준", w.timeLabel)
    }

    @Test
    fun noDataIsNullAndKeyErrorIsReadable() {
        assertNull(KmaRoadParser.weather("""{"response":{"header":{"resultCode":"03","resultMsg":"NO_DATA"}}}"""))
        val e = try {
            KmaRoadParser.weather("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}""")
            null
        } catch (e: IOException) {
            e.message
        }
        assertEquals("기상청 도로날씨 API 오류 (30) 등록되지 않은 서비스키", e)
    }
}
