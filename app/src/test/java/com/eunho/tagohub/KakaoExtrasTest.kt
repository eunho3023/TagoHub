package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.KakaoLocal
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.KakaoTransit
import com.eunho.tagohub.data.KakaoWalk
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoExtrasTest {
    @Test
    fun kakaoCarRoute_parsesSummaryAndRoadGeometry() {
        val body = """{"routes":[
          {"result_code":0,"result_msg":"길찾기 성공",
           "summary":{"distance":1850,"duration":420},
           "sections":[{"roads":[
             {"name":"테헤란로","vertexes":[127.0001,37.5001,127.001,37.501]},
             {"name":"역삼로","vertexes":[127.001,37.501,127.002,37.502]}
           ]}]
          }]}"""

        val (status, route) = KakaoDrive.parse(body)

        assertEquals("OK", status)
        assertEquals(1850, route?.distanceMeters)
        assertEquals(420, route?.durationSeconds)
        assertEquals(listOf(LatLng(37.5001, 127.0001), LatLng(37.501, 127.001), LatLng(37.502, 127.002)), route?.points)
        assertEquals("서비스 신청 필요", KakaoDrive.parse("""{"routes":[{"result_code":1,"result_msg":"서비스 신청 필요"}]}""").first)
        assertEquals("INVALID_RESPONSE", KakaoDrive.parse("oops").first)
    }

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

class ShapeSliceTest {
    @Test
    fun slice_betweenTwoStops() {
        // ㄱ자로 꺾이는 공식 모양: 동쪽으로 가다 북쪽으로
        val shape = listOf(LatLng(35.80, 127.10), LatLng(35.80, 127.11), LatLng(35.80, 127.12), LatLng(35.81, 127.12))
        val a = LatLng(35.80001, 127.105)     // 첫 구간 가운데 (도로 옆 1m)
        val b = LatLng(35.805, 127.12001)     // 꺾인 뒤 가운데
        val s = Shapes.slice(shape, a, b)!!
        assertEquals(127.105, s.first().lng, 1e-4)
        assertTrue(s.contains(LatLng(35.80, 127.11)) && s.contains(LatLng(35.80, 127.12)))   // 사이 점과 꺾이는 점 그대로
        assertEquals(35.805, s.last().lat, 1e-4)
        assertEquals(null, Shapes.slice(shape, b, a))                       // 순서가 거꾸로면 null
        assertEquals(null, Shapes.slice(shape, a, LatLng(35.9, 127.3)))     // 모양에서 먼 정류장이면 null
    }
}
