package com.eunho.tagohub

import com.eunho.tagohub.data.AirRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirRoutesTest {
    private val body = """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":100,"pageNo":1,"totalCount":2,"items":{"item":[
        {"ARP":"CJU","ODP":"GMP","TOF":"D","KM":449.0,"TIME":48.0},{"ARP":"ADD","ODP":"ICN","TOF":"I","KM":9204.0,"TIME":705.0}]}}}}"""
    private val table = AirRoutes.parse(body)

    @Test
    fun findsTheRouteInEitherDirection() {
        assertEquals(449, AirRoutes.find(table, "GMP", "CJU")?.km)
        assertEquals(48, AirRoutes.find(table, "CJU", "GMP")?.minutes)
        assertNull(AirRoutes.find(table, "GMP", "PUS"))
    }

    @Test
    fun textShowsHoursAndMinutes() {
        assertEquals("비행 48분 · 449km", AirRoutes.find(table, "GMP", "CJU")?.text())
        assertEquals("비행 11시간 45분 · 9204km", AirRoutes.find(table, "ICN", "ADD")?.text())
        assertEquals("비행 1시간 · 100km", AirRoutes.Route(100, 60).text())
    }
}
