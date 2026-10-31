package com.eunho.tagohub

import com.eunho.tagohub.data.HistoryLogic
import com.eunho.tagohub.data.RouteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLogicTest {
    @Test
    fun searchesAreNewestFirstDeduplicatedAndCapped() {
        var list = emptyList<String>()
        list = HistoryLogic.addSearch(list, "강남역")
        list = HistoryLogic.addSearch(list, " 서울  역 ")
        list = HistoryLogic.addSearch(list, "강남역")
        assertEquals(listOf("강남역", "서울 역"), list)
        assertEquals(list, HistoryLogic.addSearch(list, " "))       // 빈 말은 안 넣음
        assertEquals(list, HistoryLogic.addSearch(list, "a"))        // 한 글자는 안 넣음
        val many = (1..40).fold(emptyList<String>()) { acc, i -> HistoryLogic.addSearch(acc, "검색어$i") }
        assertEquals(HistoryLogic.MAX_SEARCHES, many.size)
        assertEquals("검색어40", many.first())
    }

    @Test
    fun searchesRoundTripThroughJson() {
        val list = listOf("강남역", "따옴표\"와 쉼표,")
        assertEquals(list, HistoryLogic.decodeSearches(HistoryLogic.encodeSearches(list)))
        assertTrue(HistoryLogic.decodeSearches("깨진 내용").isEmpty())
        assertTrue(HistoryLogic.decodeSearches(null).isEmpty())
    }

    @Test
    fun sameRouteMovesToTheFrontAndKeepsTheGuidedMark() {
        val a = RouteRecord("내 위치", "강남역", 37.5, 127.0, mode = "대중교통", atMs = 1)
        val b = RouteRecord("내 위치", "서울역", 37.55, 126.97, atMs = 2)
        var list = HistoryLogic.addRoute(HistoryLogic.addRoute(emptyList(), a.copy(guided = true)), b)
        list = HistoryLogic.addRoute(list, a.copy(atMs = 3))
        assertEquals(listOf("강남역", "서울역"), list.map { it.toName })
        assertTrue(list.first().guided)                   // 이전에 안내했던 표시가 남음
        assertEquals(3L, list.first().atMs)
    }

    @Test
    fun routeWithSameStartAndEndOrNoDestinationIsIgnored() {
        assertTrue(HistoryLogic.addRoute(emptyList(), RouteRecord("강남역", "강남역", 1.0, 2.0)).isEmpty())
        assertTrue(HistoryLogic.addRoute(emptyList(), RouteRecord("내 위치", " ", 1.0, 2.0)).isEmpty())
    }

    @Test
    fun routesRoundTripThroughJson() {
        val list = listOf(
            RouteRecord("내 위치", "강남역", 37.5, 127.0, null, null, "자동차", true, 5),
            RouteRecord("서울역", "수원역", 37.26, 127.0, 37.55, 126.97, "대중교통", false, 6),
        )
        assertEquals(list, HistoryLogic.decodeRoutes(HistoryLogic.encodeRoutes(list)))
        assertFalse(HistoryLogic.decodeRoutes("{").isNotEmpty())
    }
}
