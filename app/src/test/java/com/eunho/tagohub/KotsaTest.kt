package com.eunho.tagohub

import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.StopMerge
import com.eunho.tagohub.ui.bus.shortRegion
import org.junit.Assert.assertEquals
import org.junit.Test

/** 한국교통안전공단 정류장(TSB…) 반영 확인 — 정류장 DB 의 실제 좌표 */
class KotsaTest {
    @Test
    fun regionLabels() {
        assertEquals("전주", shortRegion("전주시완산구"))
        assertEquals("김제", shortRegion("김제시"))
        assertEquals("임실", shortRegion("임실군"))
        assertEquals("서귀포", shortRegion("서귀포시"))
        assertEquals("중구", shortRegion("중구"))
    }

    /** 기린대로 병무청: 전주 30703 ↔ 교통안전공단 3050070, 길 건너편 30704 ↔ 3050104 가 각각 한 정류장으로 */
    @Test
    fun girinMilitaryOffice_mergedBothSides() {
        val j703 = BusStop("35010", "JUB305100703", "기린대로 병무청", "30703", 35.82096, 127.15228)
        val j704 = BusStop("35010", "JUB305100704", "기린대로 병무청", "30704", 35.82136, 127.152248)
        val t070 = BusStop("TS305", "TSB305000070", "기린대로 병무청", "3050070", 35.821063, 127.152117)
        val t104 = BusStop("TS305", "TSB305000104", "기린대로 병무청", "3050104", 35.821363, 127.152292)
        val merged = StopMerge.merge(listOf(j703, j704, t070, t104), preferCity = "35010")
        assertEquals(2, merged.size)
        assertEquals("30703, 3050070", merged.first { it.main.nodeId == "JUB305100703" }.all.mapNotNull { it.nodeNo }.joinToString(", "))
        assertEquals(listOf("TSB305000104"), merged.first { it.main.nodeId == "JUB305100704" }.others.map { it.nodeId })
    }

    @Test
    fun jeonjuRouteTypes_likeKakao() {
        assertEquals("간선", JeonjuMappers.routeType("0", "4000"))
        assertEquals("간선", JeonjuMappers.routeType("0", "6002"))
        assertEquals("일반", JeonjuMappers.routeType("0", "119"))
        assertEquals("순환", JeonjuMappers.routeType("1", "3-2"))
    }
}
