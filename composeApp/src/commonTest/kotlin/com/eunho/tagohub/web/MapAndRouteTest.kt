package com.eunho.tagohub.web

import com.eunho.tagohub.data.BusLocation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapAndRouteTest {
    @Test fun mercatorRoundTripAndFit() {
        val z = 15
        val x = Mercator.x(127.3845, z); val y = Mercator.y(36.3504, z)
        assertTrue(abs(Mercator.lng(x, z) - 127.3845) < 1e-9)
        assertTrue(abs(Mercator.lat(y, z) - 36.3504) < 1e-9)
        // 대전 시내 노선 (약 10km) 은 600×300 화면에 12~14 사이 확대로
        val zf = Mercator.fitZoom(listOf(36.30 to 127.33, 36.40 to 127.43), 600, 300)
        assertTrue(zf in 11..13, "zoom $zf")
    }

    @Test fun zoomKeepsPointUnderCursor() {
        val m = MapState(36.35, 127.38, 14)
        m.size = androidx.compose.ui.unit.IntSize(800, 600)
        val at = androidx.compose.ui.geometry.Offset(700f, 100f)
        val la = Mercator.lat(Mercator.y(m.lat, 14) + at.y - 300, 14); val lo = Mercator.lng(Mercator.x(m.lng, 14) + at.x - 400, 14)
        m.zoomAt(1, at)
        val p = m.toScreen(la, lo)
        assertTrue(abs(p.x - 700f) < 0.5f && abs(p.y - 100f) < 0.5f, "$p")
    }

    @Test fun parsesRouteAndPlacesBuses() {
        val json = """{"DJB30300054":{"no":"301","start":"A","end":"B","city":"25","region":"대전","rtype":"간선버스","realtime":"tago",
            "info":["05:30","22:40",12,15,null],"stops":[[2,"DJB2","둘",36.31,127.31,"1"],[1,"DJB1","하나",36.30,127.30,"0"],[3,null,"셋",null,null,"1"]]}}"""
        val r = WebRepo.parseRoutes(json)["DJB30300054"]!!
        assertEquals(listOf(1, 2, 3), r.stops.map { it.seq })
        assertEquals(15, r.sum!!.intervalOn(6))
        val at = busAtStops(r.stops, listOf(
            BusLocation("DJB2", null, 9, "대전75자9355", 36.31, 127.31),   // id 로
            BusLocation(null, null, 3, "대전75자1111", null, null),         // 순번으로
            BusLocation("X", null, 99, "대전75자2222", null, null),         // 못 찾음
        ))
        assertEquals(mapOf(1 to listOf("대전75자9355"), 2 to listOf("대전75자1111")), at.mapValues { e -> e.value.map { it.vehicleNo } })
    }
}
