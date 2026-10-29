package com.eunho.tagohub

import com.eunho.tagohub.data.AirportGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportGuideTest {
    private fun wrap(items: String) =
        """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":100,"pageNo":1,"totalCount":2,"items":{"item":$items}}}}"""

    private val fees = wrap(
        """[{"SITE_NAME":"광주공항","PARKING_PARKING_NAME":"제 1 주차장","PARKING_BASIC_M":30.0,"PARKING_BASIC_ACCOUNT":500.0,"PARKING_MINUTE_M":10.0,"PARKING_MINUTE_ACCOUNT":200.0,"PARKING_MAX_ACCOUNT":9000.0,"PARKING_HOLI_MAX_ACCOUNT":10000.0,"PARKING_FREE_M":10.0},
            {"SITE_NAME":"군산공항","PARKING_PARKING_NAME":"일반","PARKING_BASIC_M":30.0,"PARKING_BASIC_ACCOUNT":600.0,"PARKING_MINUTE_M":10.0,"PARKING_MINUTE_ACCOUNT":200.0,"PARKING_MAX_ACCOUNT":6000.0,"PARKING_HOLI_MAX_ACCOUNT":6000.0,"PARKING_FREE_M":10.0}]""",
    )

    @Test
    fun feesAreFilteredToTheAirportAndFormatted() {
        val list = AirportGuide.fees("KWJ", fees)
        assertEquals(listOf("제 1 주차장"), list.map { it.lot })
        assertEquals("최초 30분 500원 · 이후 10분마다 200원 · 하루 최대 9,000원(주말 10,000원) · 10분 무료", list.single().text())
    }

    @Test
    fun sameWeekendMaxIsNotRepeated() {
        assertEquals("최초 30분 600원 · 이후 10분마다 200원 · 하루 최대 6,000원 · 10분 무료", AirportGuide.fees("KUV", fees).single().text())
    }

    @Test
    fun airportWithoutFeeDataGetsNone() {
        assertTrue(AirportGuide.fees("KPO", fees).isEmpty())
    }

    @Test
    fun busRoutesParse() {
        val body = wrap(
            """[{"BUS_CATEGORY_KOR_NAME":"시외버스","BUS_DATA_GETON_KOR":"1번 승강장","BUS_DATA_BUS_NUM":"700","BUS_DATA_MONEY":"3,000","BUS_DATA_CARD":"2,000","BUS_DATA_ROUTE_KOR":"공항-포항","BUS_DATA_FIRST_TIME":"07:20","BUS_DATA_END_TIME":"21:30","BUS_DATA_COMNAME_KOR":"금남여객 064-753-4423","BUS_DATA_ETC_KOR":"35~70분"},
                {"BUS_CATEGORY_KOR_NAME":"x"}]""",
        )
        val b = AirportGuide.buses(body).single()
        assertEquals("700 · 시외버스", b.title())
        assertEquals("첫차 07:20 · 막차 21:30 · 배차 35~70분 · 현금 3,000원 · 카드 2,000원", b.detail())
    }
}
