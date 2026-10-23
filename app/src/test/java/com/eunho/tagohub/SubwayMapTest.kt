package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.SubwayMap
import com.eunho.tagohub.data.SubwayRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubwayMapTest {
    private fun st(name: String, lat: Double, lng: Double, line: String = "5호선") = MetroStation(name, name, line, lat, lng, "서울교통공사")

    @Test
    fun regionsByLocation() {
        assertEquals(SubwayRegion.Capital, SubwayRegion.of(37.55, 126.97))     // 서울
        assertEquals(SubwayRegion.Busan, SubwayRegion.of(35.16, 129.06))
        assertEquals(SubwayRegion.Daegu, SubwayRegion.of(35.87, 128.60))
        assertEquals(SubwayRegion.Gwangju, SubwayRegion.of(35.16, 126.85))
        assertEquals(SubwayRegion.Daejeon, SubwayRegion.of(36.35, 127.38))
        assertNull(SubwayRegion.of(33.5, 126.5))                              // 제주
    }

    @Test
    fun straightLineIsConnectedInOrder() {
        // 위도 순으로 놓인 네 역을 섞어 넣어도 이웃끼리만 이어져야 함
        val a = st("A", 37.50, 127.0); val b = st("B", 37.52, 127.0); val c = st("C", 37.54, 127.0); val d = st("D", 37.56, 127.0)
        val edges = SubwayMap.tree(listOf(c, a, d, b))
        assertEquals(3, edges.size)
        val pairs = edges.map { setOf(it.first.name, it.second.name) }.toSet()
        assertEquals(setOf(setOf("A", "B"), setOf("B", "C"), setOf("C", "D")), pairs)
    }

    @Test
    fun farApartSegmentsAreNotLinked() {
        val a = st("A", 37.0, 127.0); val b = st("B", 37.01, 127.0)
        val far = st("F", 35.0, 129.0); val far2 = st("G", 35.01, 129.0)
        val edges = SubwayMap.tree(listOf(a, b, far, far2))
        assertEquals(2, edges.size)   // A-B, F-G 만. 서울-부산 사이는 이어지지 않음
    }

    @Test
    fun linesAreGroupedPerRegion() {
        val seoul = listOf(st("가", 37.50, 127.0), st("나", 37.52, 127.0))
        val busan = listOf(st("다", 35.10, 129.0, "1호선"), st("라", 35.12, 129.0, "1호선"))
        val lines = SubwayMap.lines(seoul + busan, SubwayRegion.Capital)
        assertEquals(1, lines.size)
        assertEquals(2, lines[0].stations.size)
        assertEquals(1, lines[0].edges.size)
    }
}
