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
    fun shortEtaRoundsUpAndSaysSoonUnderHalfAMinute() {
        assertEquals("곧", HomeText.shortEta(0))
        assertEquals("곧", HomeText.shortEta(30))
        assertEquals("1분", HomeText.shortEta(31))
        assertEquals("1분", HomeText.shortEta(60))
        assertEquals("2분", HomeText.shortEta(61))
        assertEquals("12분", HomeText.shortEta(700))
    }
}
