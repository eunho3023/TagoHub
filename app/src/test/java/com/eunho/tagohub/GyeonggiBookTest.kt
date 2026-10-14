package com.eunho.tagohub

import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.GbisParser
import com.eunho.tagohub.data.GyeonggiMappers
import com.eunho.tagohub.data.GyeonggiRouteBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyeonggiBookTest {
    private fun r(id: String, no: String) = BusRoute("GGB$id", no, null, null, null, null, null, null, null, null, "31")

    @Test
    fun merge_andSearch() {
        // 실제 "12" 검색 결과 + 겹치는 다른 숫자 검색 결과
        val real = GbisParser.rows(javaClass.getResource("/fixtures/gbis_getBusRouteListv2.xml")!!.readText(), "routeId").map(GyeonggiMappers::route)
        val book = GyeonggiRouteBook.merge(listOf(real, listOf(r("216000070", "1200"), r("200000085", "98"))))
        assertEquals(47, book.size)                                   // 46 + 98번, 1200 은 겹쳐서 한 번
        val hits = GyeonggiRouteBook.search(book, "12")
        assertEquals("12", hits.first().routeNo)                      // 같은 번호가 맨 앞
        assertTrue(hits.any { it.routeNo == "H120A" } && hits.any { it.routeNo == "땡큐12" })
        assertEquals(listOf("98"), GyeonggiRouteBook.search(book, "98").map { it.routeNo })
    }

    @Test
    fun staleFileCheck_byStartStop() {
        // 실제: 앱 안 98번 첫 정류장 '이목동차고지.이목동입구' ↔ 2026 노선 정보 기점 '이목동차고지.이목동입구'
        assertTrue(GyeonggiRouteBook.sameStop("이목동차고지.이목동입구", "이목동차고지.이목동입구"))
        assertTrue(GyeonggiRouteBook.sameStop("수원역 10번출구", "수원역10번출구.역전시장"))
        assertTrue(!GyeonggiRouteBook.sameStop("성우스타우스", "이목동차고지"))   // 개편된 노선 → 경기 API
        assertTrue(GyeonggiRouteBook.sameStop("이목동차고지", null))           // 노선 정보 모르면 앱 안 데이터
    }
}
