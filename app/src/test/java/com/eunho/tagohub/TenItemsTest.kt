package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.KakaoTransit
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.PlaceNames
import com.eunho.tagohub.data.TripLeg
import com.eunho.tagohub.data.TripPlan
import com.eunho.tagohub.ui.bus.withIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenItemsTest {
    private fun bus(v: String?, node: String, ord: Int) = BusLocation(node, node, ord, v, 37.0, 127.0)

    @Test
    fun busesWithoutPlateNumbersStayDistinct() {
        val ids = withIds(listOf(bus(null, "A", 3), bus("", "A", 3), bus("  ", "B", 5), bus(" 12가3456 ", "C", 1))).map { it.vehicleNo }
        assertEquals(4, ids.toSet().size)
        assertEquals("12가3456", ids.last())
        assertTrue(ids.take(3).all { it!!.startsWith("#") })
    }

    @Test
    fun placeNamesMatchAcrossSlightlyDifferentSpellings() {
        assertTrue(PlaceNames.same("서울경부", "서울"))
        assertTrue(PlaceNames.same("동서울터미널", "동서울"))
        assertFalse(PlaceNames.same("서울", "부산"))
        assertFalse(PlaceNames.same("역", "서울"))
        val kept = PlaceNames.keepConnected(listOf(Place("1", "부산"), Place("2", "대구"), Place("3", "서울")), listOf("부산 (서부)", "대구"))
        assertEquals(listOf("부산", "대구"), kept.map { it.name })
    }

    private fun leg(no: String, type: String?, path: Boolean) = TripLeg(
        routeId = "r$no", routeNo = no, boardStopId = "b", boardName = "b", alightStopId = "a", alightName = "a",
        fromSeq = 0, toSeq = 0, stops = 3, vehicleType = type,
        path = if (path) listOf(LatLng(37.0, 127.0), LatLng(37.1, 127.1)) else null,
    )

    private fun plan(minutes: Int, vararg legs: TripLeg) = TripPlan(
        origin = BusStop("", "o", "o", null, 37.0, 127.0), dest = BusStop("", "d", "d", null, 37.1, 127.1),
        walkStartM = 0.0, walkEndM = 0.0, legs = legs.toList(), fixedMinutes = minutes,
    )

    @Test
    fun samePlanWithSameRoutesKeepsTheOneThatDrawsOnTheMap() {
        val noPath = plan(50, leg("9000", "시외", false))
        val withPath = plan(60, leg("9000", "시외", true))
        val other = plan(40, leg("7", "간선", false))
        val out = KakaoTransit.dedupe(listOf(noPath, other, withPath))
        assertEquals(2, out.size)
        assertTrue(out[0].legs.single().path != null)   // 같은 노선이면 경로가 나오는 쪽
        assertEquals("7", out[1].legs.single().routeNo)
    }

    @Test
    fun intercityMeansSiOeOrExpressVehicleTypesOnly() {
        assertTrue(leg("9000", "시외", false).intercity)
        assertTrue(leg("1", "고속", false).intercity)
        assertFalse(leg("7", "간선", false).intercity)
        assertFalse(leg("7", null, false).intercity)
    }
}
