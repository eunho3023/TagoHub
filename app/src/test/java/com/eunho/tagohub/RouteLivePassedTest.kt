package com.eunho.tagohub

import com.eunho.tagohub.data.RouteLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLivePassedTest {
    @Test
    fun aBusBeyondTheStopCountsAsPassed() {
        // 5번 정류장을 보는데 버스가 7번(서 있는) · 6번(떠나는 중)에 있으면 이미 지나간 것
        assertTrue(RouteLive.passed(5, at = listOf(7), left = listOf(6)))
        assertTrue(RouteLive.passed(5, at = emptyList(), left = listOf(5)))
        // 버스가 아직 앞(3번)에 있으면 지나간 것이 아님
        assertFalse(RouteLive.passed(5, at = listOf(3), left = emptyList()))
        // 5번에 서 있으면 지나간 것이 아니라 도착
        assertFalse(RouteLive.passed(5, at = listOf(5), left = emptyList()))
    }

    @Test
    fun stopsAwayIsNullWhenEveryBusHasPassed() {
        // 지나간 버스는 allAway 에서 빠지므로 "N정류장 전"이 없고, 이때 passed 로 "지나감"을 보여 준다
        assertEquals(null, RouteLive.stopsAway(5, at = listOf(7), left = emptyList()))
        assertTrue(RouteLive.passed(5, at = listOf(7), left = emptyList()))
    }
}
