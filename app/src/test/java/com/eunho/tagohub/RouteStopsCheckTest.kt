package com.eunho.tagohub

import com.eunho.tagohub.data.RouteStopsCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStopsCheckTest {
    @Test
    fun iksan65FromOldDataIsIncomplete() {
        // 2026-11-01 데이터의 익산 65번: 53곳 중 19곳 (3, 4, 6, 7, 9, 12 …)
        assertTrue(RouteStopsCheck.incomplete(listOf(3, 4, 6, 7, 9, 12, 20, 21, 25, 26, 30, 31, 35, 36, 37, 38, 39, 40, 41)))
    }

    @Test
    fun normalNumberingIsComplete() {
        assertFalse(RouteStopsCheck.incomplete((1..53).toList()))
        assertFalse(RouteStopsCheck.incomplete((0..72).toList()))                         // 0부터 매기는 노선
        assertFalse(RouteStopsCheck.incomplete((1001..1046).toList() + (2001..2046)))      // 대구: 방향마다 1001·2001
        assertFalse(RouteStopsCheck.incomplete((1..60).toList() - 30))                     // 한 곳만 빠짐
    }
}
