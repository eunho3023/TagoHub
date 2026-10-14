package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoTransit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카카오맵 REST API 문서의 대중교통 경로 조회 예제 (판교 → 성남시청, 마을 76) 를 바탕으로 */
class KakaoTransitTest {
    private val body = """{
      "status": "OK",
      "properties": {"total": 15, "bus": 9, "subway": 1, "busAndSubway": 5, "landingURL": "https://map.kakao.com/link/by/traffic/..."},
      "routes": [
        {"properties": {"type": "BUS", "totalDistance": 5013, "totalTime": 2115, "transfers": 0, "fare": {"value": 1350}},
         "steps": [
           {"properties": {"guidance": "판교역동편까지 도보", "type": "WALKING", "distance": 210, "time": 180},
            "path": {"points": [[127.11119217, 37.39477123], [127.11316378, 37.3948816]]}},
           {"properties": {"guidance": "마을 76 (판교역동편 > 성남시청전면)", "type": "BUS", "distance": 4127, "time": 1158,
              "stops": [{"name": "판교역동편"}, {"name": "봇들마을7.8단지.롯데마트판교점"}, {"name": "성남시청전면"}],
              "vehicles": [{"name": "76", "type": "마을"}, {"name": "76-1", "type": "마을"}]},
            "path": {"points": [[127.11316378, 37.3948816], [127.11312974, 37.39488161], [127.12500000, 37.41900000]]}},
           {"properties": {"guidance": "도착지까지 도보", "type": "WALKING", "distance": 676, "time": 600},
            "path": {"points": [[127.12500000, 37.41900000], [127.12628814, 37.41993056]]}}
         ]},
        {"properties": {"type": "BUS_AND_SUBWAY", "totalDistance": 6000, "totalTime": 2400, "transfers": 1, "fare": {"value": 1550}},
         "steps": [
           {"properties": {"type": "SUBWAY", "distance": 2000, "time": 300, "stops": [{"name": "판교"}, {"name": "정자"}], "vehicles": [{"name": "신분당선", "type": "수도권"}]},
            "path": {"points": [[127.111, 37.394], [127.108, 37.366]]}},
           {"properties": {"type": "WALKING", "distance": 150, "time": 120}, "path": {"points": [[127.108, 37.366], [127.109, 37.367]]}},
           {"properties": {"type": "BUS", "distance": 3000, "time": 900, "stops": [{"name": "정자역"}, {"name": "x"}, {"name": "성남시청"}], "vehicles": [{"name": "9", "type": "간선"}]},
            "path": {"points": [[127.109, 37.367], [127.126, 37.419]]}}
         ]}
      ]}"""

    @Test
    fun parse_routesStepsPaths() {
        val r = KakaoTransit.parse(body)
        assertEquals("OK", r.status)
        assertEquals(2, r.routes.size)
        val bus = r.routes[0]
        assertEquals(2115, bus.totalTime)
        assertEquals(1350, bus.fare)
        assertEquals(listOf("WALKING", "BUS", "WALKING"), bus.steps.map { it.type })
        assertEquals("76", bus.steps[1].vehicles[0].name)
        assertEquals("마을", bus.steps[1].vehicles[0].type)
        assertEquals(37.3948816, bus.steps[1].points[0].lat, 1e-9)   // [x, y] = [경도, 위도]
        assertEquals(127.11316378, bus.steps[1].points[0].lng, 1e-9)
    }

    @Test
    fun toPlan_legsWalksFare() {
        val r = KakaoTransit.parse(body)
        val p = KakaoTransit.toPlan(r.routes[0])!!
        assertEquals(1, p.legs.size)
        val leg = p.legs[0]
        assertEquals("76", leg.routeNo)
        assertEquals(listOf("76-1"), leg.alternatives)
        assertEquals("판교역동편", leg.boardName)
        assertEquals("성남시청전면", leg.alightName)
        assertEquals(2, leg.stops)
        assertEquals(3, leg.path!!.size)                 // 실제 경로 그대로
        assertEquals(210.0, p.walkStartM, 0.0)
        assertEquals(676.0, p.walkEndM, 0.0)
        assertEquals(36, p.minutes)                      // 2115초 → 36분 (대기 정보 없음)
        assertEquals(1350, p.fare)
        assertEquals(2, p.walkPaths!!.size)
        assertTrue(KakaoTransit.isKakao(leg.routeId))

        val mixed = KakaoTransit.toPlan(r.routes[1])!!
        assertEquals(listOf(true, false), mixed.legs.map { it.subway })
        assertEquals(150.0, mixed.transferWalkM, 0.0)    // 가운데 걷기 = 환승 걷기
        assertEquals(0.0, mixed.walkStartM, 0.0)
    }

    @Test
    fun statusMessages_andRouteNo() {
        assertEquals(0, KakaoTransit.parse("""{"status":"NO_RESULTS"}""").routes.size)
        assertEquals("출발지와 도착지가 같습니다.", KakaoTransit.message("EQUAL_POINTS"))
        assertEquals("INVALID_RESPONSE", KakaoTransit.parse("oops").status)
        assertTrue(KakaoTransit.sameRouteNo("76", "76번") && KakaoTransit.sameRouteNo("마을 76", "마을76") && !KakaoTransit.sameRouteNo("76", "761"))
    }
}
