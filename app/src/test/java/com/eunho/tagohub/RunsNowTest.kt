package com.eunho.tagohub

import com.eunho.tagohub.ui.bus.runsNow
import org.junit.Assert.assertEquals
import org.junit.Test

class RunsNowTest {
    private fun at(h: Int, m: Int) = h * 60 + m

    @Test
    fun aOneTripVariantIsOverOnceItsTripTimeHasPassed() {
        // 전주 165 "효자로전북도청 → 전주동물원": 첫차=막차=06:00, 한 번 도는 데 약 45분
        assertEquals(true, runsNow("600", "600", at(6, 30), 45))
        assertEquals(false, runsNow("600", "600", at(7, 3), 45))     // 07:03 → 이미 끝난 노선인데 다른 변형의 버스가 잡히던 때
        assertEquals(false, runsNow("2240", "2240", at(7, 3), 50))   // 22:40 한 번뿐인 변형
    }

    @Test
    fun anOrdinaryAllDayRouteRunsUntilTheLastBusFinishesItsTrip() {
        assertEquals(true, runsNow("06:00", "22:20", at(7, 3), 60))
        assertEquals(true, runsNow("06:00", "22:20", at(23, 20), 60))   // 막차(22:20 출발)가 아직 달리는 중
        assertEquals(false, runsNow("06:00", "22:20", at(23, 45), 60))
        assertEquals(false, runsNow("06:00", "22:20", at(5, 0), 60))
        assertEquals(true, runsNow(null, null, at(3, 0), 60))           // 시각을 모르면 막지 않음
    }
}

class VariantGraceTest {
    @Test
    fun otherRegionsGetAGenerousWindowSoRealBusesAreNotHidden() {
        // 전주(JUB…)는 빠듯하게, 다른 지역은 막차 + 한 바퀴 + 70분까지
        assertEquals(10, com.eunho.tagohub.ui.bus.variantGraceMin("JUB305001140"))
        assertEquals(70, com.eunho.tagohub.ui.bus.variantGraceMin("DJB30300002"))
        assertEquals(false, runsNow("600", "600", 7 * 60 + 3, 45, com.eunho.tagohub.ui.bus.variantGraceMin("JUB305001520")))
        assertEquals(true, runsNow("600", "600", 7 * 60 + 3, 45, com.eunho.tagohub.ui.bus.variantGraceMin("DJB30300002")))
    }
}
