package com.eunho.tagohub

import com.eunho.tagohub.data.DriveGuide
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.TurnKind
import com.eunho.tagohub.data.ViaTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자동차 길찾기 경유지 (카카오 길찾기 waypoints): 요청 값, 구간 이어 붙이기, 지난 경유지 걸러내기 */
class DriveWaypointsTest {
    // 출발 → 경유 → 도착 : 경유지가 1개면 sections 가 2개. 둘째 구간은 시작 안내(100)로 다시 시작하고 첫 구간은 경유지(1000)로 끝남
    private val body = """
    {"routes":[{"result_code":0,"summary":{"distance":3000,"duration":600,"fare":{"toll":0,"taxi":9000}},
      "sections":[
        {"distance":1000,"duration":200,"roads":[{"name":"a","distance":1000,"duration":200,"traffic_state":4,"vertexes":[127.0,37.0,127.01,37.01]}],
         "guides":[{"name":"출발","x":127.0,"y":37.0,"distance":0,"type":100,"guidance":"출발지"},
                   {"name":"경유","x":127.01,"y":37.01,"distance":1000,"type":1000,"guidance":"경유지"}]},
        {"distance":2000,"duration":400,"roads":[{"name":"b","distance":2000,"duration":400,"traffic_state":4,"vertexes":[127.01,37.01,127.02,37.02]}],
         "guides":[{"name":"경유","x":127.01,"y":37.01,"distance":0,"type":100,"guidance":"출발지"},
                   {"name":"도착","x":127.02,"y":37.02,"distance":2000,"type":101,"guidance":"목적지"}]}
      ]}]}
    """.trimIndent()

    @Test
    fun waypointRequestValueIsLngLatPairsJoinedByPipeAndCappedAtFive() {
        assertNull(KakaoDrive.waypointsParam(emptyList()))
        assertEquals("127.1,37.1", KakaoDrive.waypointsParam(listOf(LatLng(37.1, 127.1))))
        assertEquals("127.1,37.1|128.2,36.2", KakaoDrive.waypointsParam(listOf(LatLng(37.1, 127.1), LatLng(36.2, 128.2))))
        val seven = (1..7).map { LatLng(30.0 + it, 120.0 + it) }
        assertEquals(5, KakaoDrive.waypointsParam(seven)!!.split("|").size)
    }

    @Test
    fun sectionsAreJoinedIntoOneRouteWithTheWaypointKeptAndNoSecondStart() {
        val (status, route) = KakaoDrive.parse(body)
        assertEquals("OK", status)
        route!!
        // 경로 점은 경계에서 중복 없이 이어짐
        assertEquals(listOf(LatLng(37.0, 127.0), LatLng(37.01, 127.01), LatLng(37.02, 127.02)), route.points)
        // 안내: 출발 → 경유지 → 목적지 (둘째 구간의 시작 안내는 빠짐)
        assertEquals(listOf(100, 1000, 101), route.guides.map { it.type })
        assertEquals(listOf(TurnKind.Start, TurnKind.Waypoint, TurnKind.Destination), route.guides.map { TurnKind.of(it) })
    }

    @Test
    fun aWaypointIsAnnouncedAsAManeuverAndReadAloud() {
        val g = DriveGuide("경유", "경유지", 500, DriveGuide.TYPE_WAYPOINT, 37.0, 127.0)
        assertTrue(TurnKind.Waypoint.isManeuver)
        assertEquals("경유지", TurnKind.text(g))
    }

    @Test
    fun viaIsDroppedOnlyOnceTheCarIsWithinRangeAndInOrder() {
        val a = LatLng(37.0, 127.0)
        val b = LatLng(37.1, 127.1)
        // 멀리 있으면 그대로
        assertEquals(listOf(a, b), ViaTracker.remaining(listOf(a, b), 37.05, 127.05))
        // 첫 경유지 가까이 오면 첫 것만 뺌
        assertEquals(listOf(b), ViaTracker.remaining(listOf(a, b), 37.0003, 127.0003))
        // 둘째 경유지 근처라도 첫 것을 지나기 전엔 빼지 않음 (순서대로 지남)
        assertEquals(listOf(a, b), ViaTracker.remaining(listOf(a, b), 37.1, 127.1))
        assertTrue(ViaTracker.remaining(emptyList(), 37.0, 127.0).isEmpty())
    }
}
