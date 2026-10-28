package com.eunho.tagohub

import com.eunho.tagohub.data.KricRouteStation
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighborFillTest {
    private fun st(code: String, name: String, line: String, lat: Double, lng: Double, op: String = "x") = MetroStation(code, name, line, lat, lng, op)
    private fun kr(order: Int, code: String, name: String, op: String = "KR", line: String = "K4") =
        KricRouteStation("01", "1", "x", order, op, line, code, name)

    // ── 1호선 서울역: KRIC 서울교통공사(S1) 목록은 서울역에서 끝나서 다음 역이 비어 있음 ──
    private val metro = StationIndex(listOf(
        st("0152", "종각", "1호선", 37.5703, 126.9831),
        st("0151", "시청", "1호선", 37.5657, 126.9771),
        st("0150", "서울역", "1호선", 37.5562, 126.9721),
        st("134", "남영(민주화운동기념관)", "1호선", 37.5406, 126.9712),
        st("135", "용산", "1호선", 37.5299, 126.9648),
        // 다른 도시의 같은 이름 호선·역: 이웃으로 잘못 잡히면 안 됨
        st("b1", "시청", "부산1호선", 35.18, 129.07),
        st("0426", "서울역", "4호선", 37.5532, 126.9728),
    ))
    private val seoul1 = metro.groups.first { it.name.startsWith("서울") }
    private val s1 = listOf(kr(35, "152", "종각", "S1", "1"), kr(36, "151", "시청", "S1", "1"), kr(37, "150", "서울역", "S1", "1"))

    @Test
    fun lineOneSeoulStationHasCityHallBeforeAndNamyeongAfter() {
        val a = metro.neighborsOf(seoul1, "1호선", s1, "150")!!
        assertEquals(StationIndex.Around("시청", "서울역", "남영"), a)
    }

    @Test
    fun otherCitiesSameNamedLineDoNotLeakIn() {
        // 알려진 이웃 "시청"이 부산 1호선의 시청으로 잡히면 반대쪽 후보가 종각으로 틀어졌음
        assertEquals("시청" to "남영", metro.completeNeighbor(seoul1, "1호선", "시청", null))
    }

    // ── 경의중앙선 서울역: KRIC 번호가 지선(서울역·신촌)과 본선(수색·디지털미디어시티)에서 겹침 ──
    private val gyeongui = StationIndex(listOf(
        st("K317", "수색", "경의중앙선", 37.5826, 126.8956, "KR"),
        st("K316", "디지털미디어시티", "경의중앙선", 37.5770, 126.9006, "KR"),
        st("K315", "가좌", "경의중앙선", 37.5685, 126.9157, "KR"),
        st("P314", "신촌", "경의중앙선", 37.5598, 126.9429, "KR"),
        st("P313", "서울역", "경의중앙선", 37.5560, 126.9713, "KR"),
        st("K314", "홍대입구", "경의중앙선", 37.5576, 126.9238, "KR"),
        st("K312", "공덕", "경의중앙선", 37.5440, 126.9516, "KR"),
        st("K311", "용산", "경의중앙선", 37.5299, 126.9648, "KR"),
    ))
    private val seoulK4 = gyeongui.groups.first { it.name.startsWith("서울") }
    private val k4 = listOf(
        kr(20, "K318", "능곡"), kr(21, "P314", "신촌"), kr(21, "K317", "수색"),
        kr(22, "P313", "서울역"), kr(22, "K316", "디지털미디어시티"), kr(23, "K315", "가좌"),
        kr(24, "K314", "홍대입구"), kr(26, "K312", "공덕"), kr(27, "K311", "용산"),
    )

    @Test
    fun branchTerminusShowsOnlyItsOwnNeighbourAfterAndNothingBefore() {
        val a = gyeongui.neighborsOf(seoulK4, "경의중앙선", k4, "P313")!!
        // 번호순으로만 줄 세우면 이전 수색·다음 디지털미디어시티가 됐음
        assertEquals(null, a.prev)
        assertEquals("신촌", a.next)
    }

    @Test
    fun aMainLineStationKeepsBothNeighboursFromTheRightBranch() {
        val gajwa = gyeongui.groups.first { it.name.startsWith("가좌") }
        val a = gyeongui.neighborsOf(gajwa, "경의중앙선", k4, "K315")!!
        // 22번에 서울역(지선)과 디지털미디어시티(본선)가 같이 있어도 가까운 디지털미디어시티가 이전 역
        assertEquals(StationIndex.Around("디지털미디어시티", "가좌", "홍대입구"), a)
    }
}
