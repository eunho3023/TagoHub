package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.ui.bus.jeonjuWithGps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JeonjuGpsTest {
    private fun bus(no: String, lat: Double?, low: Boolean? = null, ord: Int = 3) = BusLocation("JUB1", null, ord, no, lat, lat?.let { 127.1 }, low)

    @Test fun tagoGpsWinsAndKeepsLowFloor() {
        val tago = listOf(bus("전북71자1234", 35.8), bus("전북71자5678", 35.81))
        val jeonju = listOf(bus("1234", null, low = true), bus("5678", null, low = false))
        val got = jeonjuWithGps(tago, jeonju)!!
        assertEquals(listOf(35.8, 35.81), got.map { it.lat })
        assertEquals(listOf(true, false), got.map { it.lowFloor })
    }

    @Test fun fallsBackToJeonjuWithoutGps() {
        val jeonju = listOf(bus("1234", null, low = true))
        assertEquals(jeonju, jeonjuWithGps(emptyList(), jeonju))
        assertEquals(jeonju, jeonjuWithGps(listOf(bus("9999", null)), jeonju))
        assertNull(jeonjuWithGps(null, null))
    }
}

class StopPositionTest {
    private val stops = listOf(
        com.eunho.tagohub.data.RouteStop("A", "가", null, 1, null, 37.0, 127.0),
        com.eunho.tagohub.data.RouteStop("B", "나", null, 2, null, 37.1, 127.1),
        com.eunho.tagohub.data.RouteStop("A", "가", null, 9, null, 37.0, 127.0),
    )

    @Test fun byStopIdThenOrder() {
        val b = BusLocation("B", null, 5, "경기70아1234", null, null)
        assertEquals(com.eunho.tagohub.data.LatLng(37.1, 127.1), com.eunho.tagohub.ui.bus.stopPosition(b, stops))
        val byOrd = BusLocation("Z", null, 2, "x", null, null)
        assertEquals(37.1, com.eunho.tagohub.ui.bus.stopPosition(byOrd, stops)!!.lat, 0.0)
        assertNull(com.eunho.tagohub.ui.bus.stopPosition(BusLocation("Z", null, 7, "x", null, null), stops))
    }
}
