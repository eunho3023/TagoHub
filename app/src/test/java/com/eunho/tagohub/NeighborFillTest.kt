package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighborFillTest {
    private fun st(code: String, name: String, line: String, lat: Double, lng: Double) = MetroStation(code, name, line, lat, lng, "x")

    private val index = StationIndex(listOf(
        st("0151", "시청", "1호선", 37.5657, 126.9771),
        st("0150", "서울역", "1호선", 37.5562, 126.9721),
        st("134", "남영(민주화운동기념관)", "1호선", 37.5406, 126.9712),
        st("135", "용산", "1호선", 37.5299, 126.9648),
        st("0426", "서울역", "4호선", 37.5532, 126.9728),
    ))
    private val seoul = index.groups.first { it.name == "서울" || it.name.startsWith("서울") }

    @Test
    fun missingNextStationIsFilledFromTheSameLineOnTheOppositeSide() {
        // KRIC 노선 목록은 서울교통공사 구간이라 서울역에서 끊김: 이전 시청, 다음 없음
        assertEquals("시청" to "남영", index.completeNeighbor(seoul, "1호선", "시청", null))
    }

    @Test
    fun missingPreviousStationIsFilledToo() {
        assertEquals("시청" to "남영", index.completeNeighbor(seoul, "1호선", null, "남영"))
    }

    @Test
    fun trueTerminusAndCompleteStripsStayAsTheyAre() {
        // 시청 쪽 끝(시청 다음이 없는 쪽)에는 반대편 역이 있어도 서울역 방향만 있으니, 알려진 이웃의 반대쪽이 비면 그대로
        val cityHall = index.groups.first { it.name == "시청" }
        assertEquals(null to "서울", index.completeNeighbor(cityHall, "1호선", null, "서울"))
        assertEquals("시청" to "남영", index.completeNeighbor(seoul, "1호선", "시청", "남영"))
        assertEquals(null to null, index.completeNeighbor(seoul, "1호선", null, null))
    }
}
