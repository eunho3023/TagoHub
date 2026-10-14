package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.KakaoLocal
import com.eunho.tagohub.data.KakaoTransit
import com.eunho.tagohub.data.KakaoWalk
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoExtrasTest {
    @Test
    fun removeDetours_replacesBlockLoopOnly() {
        // 동쪽으로 곧게 가는 노선. 두 번째 구간에서 OSRM 이 블록(약 300m × 300m)을 한 바퀴 돌았음
        val s0 = LatLng(35.8000, 127.1000); val s1 = LatLng(35.8000, 127.1050); val s2 = LatLng(35.8000, 127.1100)
        val shape = listOf(s0, LatLng(35.8000, 127.1025), s1,
            LatLng(35.8000, 127.1080), LatLng(35.7973, 127.1080), LatLng(35.7973, 127.1050), LatLng(35.8000, 127.1050), // 한 바퀴
            LatLng(35.8000, 127.1075), s2)
        val fixed = Shapes.removeDetours(shape, listOf(s0, s1, s2))
        val before = Shapes.length(shape)
        val after = Shapes.length(fixed)
        assertTrue("고친 뒤 ${after.toInt()}m < 고치기 전 ${before.toInt()}m", after < before - 800)
        assertTrue(fixed.contains(LatLng(35.8000, 127.1025)))                 // 정상 구간은 그대로
        assertTrue(fixed.none { it.lat < 35.7999 })                          // 블록 아래쪽으로 내려가지 않음
        assertEquals(Geo.meters(s0.lat, s0.lng, s2.lat, s2.lng), after, 5.0)
    }

    @Test
    fun ridePath_pickRouteNumberNearStops() {
        val body = """{"status":"OK","routes":[
          {"properties":{"type":"BUS","totalDistance":900,"totalTime":600,"transfers":0},
           "steps":[{"properties":{"type":"BUS","distance":800,"time":300,"stops":[{"name":"가"},{"name":"나"}],"vehicles":[{"name":"119","type":"일반"}]},
                     "path":{"points":[[127.1000,35.8000],[127.1050,35.8001],[127.1100,35.8000]]}}]},
          {"properties":{"type":"BUS","totalDistance":900,"totalTime":500,"transfers":0},
           "steps":[{"properties":{"type":"BUS","distance":800,"time":300,"stops":[{"name":"가"},{"name":"나"}],"vehicles":[{"name":"85","type":"일반"}]},
                     "path":{"points":[[127.1000,35.8000],[127.1100,35.8000]]}}]}]}"""
        val r = KakaoTransit.parse(body)
        val a = LatLng(35.8000, 127.1000); val b = LatLng(35.8000, 127.1100)
        assertEquals(3, KakaoTransit.ridePath(r, "119", a, b)!!.size)        // 119번 구간만
        assertEquals(2, KakaoTransit.ridePath(r, "85번", a, b)!!.size)
        assertEquals(null, KakaoTransit.ridePath(r, "5", a, b))              // 이 번호는 없음
        assertEquals(null, KakaoTransit.ridePath(r, "119", a, LatLng(35.81, 127.12)))  // 끝이 다른 곳
        assertTrue(Shapes.passesStops(KakaoTransit.ridePath(r, "119", a, b)!!, listOf(LatLng(35.80005, 127.105)), 50.0))
    }

    @Test
    fun walk_andAddressName() {
        val walk = """{"route":{"properties":{"totalDistance":4025,"totalTime":3914},"legs":[{"properties":{"distance":4025,"time":3914},
          "steps":[{"properties":{"distance":93,"guidance":"판교역 1번 출구까지 역사 내 이동","time":84,"x":127.11119783,"y":37.39477573},
                    "path":{"points":[[127.11119783,37.39477573],[127.11119779,37.39474869],[127.1114835,37.39474572]]}},
                   {"properties":{"distance":50,"time":40},"path":{"points":[[127.1114835,37.39474572],[127.11148211,37.39533499]]}}]}]},"status":"OK"}"""
        val (st, pts) = KakaoWalk.parse(walk)
        assertEquals("OK", st)
        assertEquals(4, pts.size)                                            // 단계 사이 겹치는 점은 한 번
        assertEquals(37.39477573, pts[0].lat, 1e-9)
        assertEquals("TOO_FAR_AWAY", KakaoWalk.parse("""{"status":"TOO_FAR_AWAY"}""").first)

        // 문서 예제 (경기 안성시 죽산면 죽산초교길 69-4)
        val addr = """{"meta":{"total_count":1},"documents":[{"road_address":{"address_name":"경기도 안성시 죽산면 죽산초교길 69-4","building_name":"","zone_no":"17519"},
          "address":{"address_name":"경기 안성시 죽산면 죽산리 343-1"}}]}"""
        assertEquals("경기도 안성시 죽산면 죽산초교길 69-4", KakaoLocal.addressName(addr))   // 건물 이름 없으면 도로명
        val bld = addr.replace("\"building_name\":\"\"", "\"building_name\":\"죽산초등학교\"")
        assertEquals("죽산초등학교", KakaoLocal.addressName(bld))
        assertEquals("경기 안성시 죽산면 죽산리 343-1", KakaoLocal.addressName("""{"documents":[{"road_address":null,"address":{"address_name":"경기 안성시 죽산면 죽산리 343-1"}}]}"""))
    }
}
