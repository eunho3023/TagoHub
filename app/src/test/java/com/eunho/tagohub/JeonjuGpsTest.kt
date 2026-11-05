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
