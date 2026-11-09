package com.eunho.tagohub

import com.eunho.tagohub.data.CityTimetable
import com.eunho.tagohub.data.DayKind
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CityTimetableTest {
    private fun stop(ord: Int, name: String) = RouteStop("N$ord", name, null, ord, null, null, null)
    /** 정류장 하나 건너는 데 10분 */
    private val tenMin: (Int, Int) -> Double = { a, b -> (b - a) * 10.0 }

    @Test fun areaFromRouteId() {
        assertEquals("ulsan", CityTimetable.areaOf("USB388000131"))
        assertEquals("daegu", CityTimetable.areaOf("DGB4080006000"))
        assertEquals("gumi", CityTimetable.areaOf("GMB1010"))
        assertNull(CityTimetable.areaOf("JUB305001764"))   // 전주는 앱이 따로
        assertNull(CityTimetable.areaOf("SEB100100549"))
    }

    private fun deps(weekday: List<Int>, down: List<Int> = emptyList(), tables: List<CityTimetable.Table> = emptyList()) = CityTimetable.Deps(
        starts = DayKind.values().associateWith { weekday },
        downStarts = if (down.isEmpty()) emptyMap() else DayKind.values().associateWith { down },
        tables = tables, source = null,
    )

    @Test fun startsPlusTravelTime() {
        val stops = listOf(stop(1, "A"), stop(2, "B"), stop(3, "C"))
        assertEquals(listOf(380, 420), CityTimetable.stopTimes(deps(listOf(360, 400)), DayKind.Weekday, stops, listOf(3), null, tenMin))
    }

    @Test fun terminusDeparturesOnlyAfterTurn() {
        val stops = listOf(stop(1, "A"), stop(2, "회차점"), stop(3, "B"))
        val d = deps(listOf(360), down = listOf(370))
        // 회차점 뒤 정류장: 기점 출발 360+20, 종점 출발 370+10 → 둘 다 380
        assertEquals(listOf(380), CityTimetable.stopTimes(d, DayKind.Weekday, stops, listOf(3), "회차점", tenMin))
        // 회차점 앞 정류장(A)엔 종점 출발 버스를 더하지 않음
        assertEquals(listOf(360), CityTimetable.stopTimes(d, DayKind.Weekday, stops, listOf(1), "회차점", tenMin))
    }

    @Test fun checkpointTableInterpolatesAndSkipsReverse() {
        val wk = setOf(DayKind.Weekday)
        val d = deps(listOf(360), tables = listOf(
            CityTimetable.Table(wk, listOf("A역", "C정류소"), listOf(listOf(360, 380), listOf(null, 400))),
            CityTimetable.Table(wk, listOf("C", "A역"), listOf(listOf(500, 520))),
        ))
        val stops = listOf(stop(1, "A역(1번출구)"), stop(2, "B"), stop(3, "C"))
        // B 는 A(360)와 C(380) 사이 가운데 → 370. 반대 방향 표는 순서가 안 맞아 빠짐
        assertEquals(listOf(370), CityTimetable.stopTimes(d, DayKind.Weekday, stops, listOf(2), null, tenMin))
        assertEquals(listOf(380, 400), CityTimetable.stopTimes(d, DayKind.Weekday, stops, listOf(3), null, tenMin))
    }

    @Test fun nameMatching() {
        assertEquals("삼양초교", CityTimetable.normName("삼양초등학교"))
        assertEquals(true, CityTimetable.sameName(CityTimetable.normName("신사동(가상정류소)"), CityTimetable.normName("신사동종점")))
        assertEquals(false, CityTimetable.sameName("가", "나"))
    }
}
