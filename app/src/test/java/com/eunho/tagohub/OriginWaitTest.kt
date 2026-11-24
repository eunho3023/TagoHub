package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.OriginWait
import com.eunho.tagohub.data.PassedBus
import com.eunho.tagohub.ui.bus.mergeStopRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기점 정류장: 도착정보가 "0정류장 전 · 곧 도착"으로 주는 버스는 출발을 기다리는 중 → "출발 대기" (웹 busdata.js atOrigin 과 같게) */
class OriginWaitTest {
    @Test fun originByName() {
        assertTrue(OriginWait.atOrigin("전주역", "전주역(종점)", "송천동"))
        assertFalse(OriginWait.atOrigin("전주역", "전주역", "전주역"))   // 회차 노선은 닿는 버스일 수도 있어 그대로
        assertFalse(OriginWait.atOrigin("한성여객", "전주역", "송천동"))
    }

    @Test fun stopRowsMarkWaitingBusAtOrigin() {
        val route = BusRoute("R1", "100", null, "전주역", "송천동", null, null, null, null, null)
        val list = listOf(BusArrival("R1", "100", null, null, 0, 30), BusArrival("R1", "100", null, null, 5, 600))
        val at = mergeStopRows(list, listOf(route), nowMin = 600, stopName = "전주역").single().arrivals
        assertEquals("출발 대기", ArrivalText.line(at[0]))
        assertFalse(PassedBus.passed(at[0], 300))
        assertEquals("10분 · 5정류장 전", ArrivalText.line(at[1]))
        // 기점이 아닌 정류장은 그대로 "정류장 도착"
        val other = mergeStopRows(list, listOf(route), nowMin = 600, stopName = "한성여객").single().arrivals
        assertEquals("정류장 도착", ArrivalText.line(other[0]))
    }

    /** 정류장 노선 줄: 도착·경유노선 자료에 종류가 없으면 앱 안 노선 자료 종류로 (있으면 그대로) */
    @Test fun stopRowsFillRouteType() {
        val routes = listOf(BusRoute("R1", "100", null, "A", "B", null, null, null, null, null),
            BusRoute("R2", "200", "마을버스", "A", "B", null, null, null, null, null))
        val rows = mergeStopRows(emptyList(), routes, nowMin = 600, typeOf = { if (it == "R1") "간선버스" else "지선버스" })
        assertEquals("간선버스", rows.first { it.routeId == "R1" }.routeType)
        assertEquals("마을버스", rows.first { it.routeId == "R2" }.routeType)
    }
}
