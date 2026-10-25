package com.eunho.tagohub

import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.ui.home.HomeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTextTest {
    private fun arr(no: String, sec: Int?, type: String? = null) = BusArrival("r$no", no, type, null, null, sec)

    @Test
    fun firstArrivalPicksTheSoonestBusWithATime() {
        val e = HomeText.firstArrival(listOf(arr("300", 600, "간선"), arr("5", 190, "지선"), arr("9", null)))
        assertEquals("5", e.routeNo)
        assertEquals("지선", e.routeType)
        assertEquals("4분", e.text)
    }

    @Test
    fun noArrivalsGivesAnEmptyEta() {
        val e = HomeText.firstArrival(emptyList())
        assertNull(e.routeNo)
        assertNull(e.text)
        assertNull(HomeText.firstArrival(listOf(arr("7", null))).text)
    }

    @Test
    fun routeEtasListsEveryRouteSoonestFirstWithOneEntryPerRoute() {
        val list = HomeText.routeEtas(listOf(arr("300", 600, "간선"), arr("5", 190, "지선"), arr("5", 400, "지선"), arr("9", null)))
        assertEquals(listOf("5", "300", "9"), list.map { it.routeNo })
        assertEquals("4분", list[0].text)   // 5번은 두 대 중 빠른 쪽
        assertNull(list[2].text)            // 도착 시각을 모르는 노선도 목록에는 나옴
    }

    @Test
    fun shortEtaRoundsUpAndSaysSoonUnderHalfAMinute() {
        assertEquals("곧", HomeText.shortEta(0))
        assertEquals("곧", HomeText.shortEta(30))
        assertEquals("1분", HomeText.shortEta(31))
        assertEquals("1분", HomeText.shortEta(60))
        assertEquals("2분", HomeText.shortEta(61))
        assertEquals("12분", HomeText.shortEta(700))
    }
}
