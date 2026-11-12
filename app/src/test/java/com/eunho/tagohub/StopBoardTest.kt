package com.eunho.tagohub

import com.eunho.tagohub.ui.map.BoardTime
import com.eunho.tagohub.ui.map.StopBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class StopBoardTest {

    private fun recorded(minute: Int, no: String, days: Int = 3) = BoardTime(
        minute = minute, routeId = "R$no", routeNo = no, routeType = null,
        source = BoardTime.Source.Recorded, early = minute, late = minute, days = days,
    )

    private fun planned(minute: Int, no: String, source: BoardTime.Source = BoardTime.Source.Planned) = BoardTime(
        minute = minute, routeId = "E$no", routeNo = no, routeType = null, source = source,
    )

    @Test
    fun `기록이 있는 노선은 예상 시각을 섞지 않는다`() {
        val merged = StopBoard.merge(
            recorded = listOf(recorded(600, "10")),
            planned = listOf(planned(610, "10"), planned(620, "20")),
        )
        assertEquals(listOf(600, 620), merged.map { it.minute })
        assertEquals(listOf("10", "20"), merged.map { it.routeNo })
    }

    @Test
    fun `예상 시각만 물결 표시 대상이다`() {
        assertFalse(recorded(600, "10").estimated)
        assertTrue(planned(600, "10").estimated)
        assertTrue(planned(600, "10", BoardTime.Source.Official).estimated)
    }

    @Test
    fun `시간대별로 묶고 그 안에서 시각 순으로 정렬한다`() {
        val groups = StopBoard.byHour(
            listOf(recorded(8 * 60 + 20, "10"), recorded(8 * 60 + 5, "20"), recorded(9 * 60, "30")),
        )
        assertEquals(listOf(8, 9), groups.map { it.first })
        assertEquals(listOf(8 * 60 + 5, 8 * 60 + 20), groups.first().second.map { it.minute })
    }

    @Test
    fun `같은 시각이면 노선 번호를 숫자로 비교한다`() {
        val merged = StopBoard.merge(
            recorded = listOf(recorded(600, "100"), recorded(600, "2"), recorded(600, "10")),
            planned = emptyList(),
        )
        assertEquals(listOf("2", "10", "100"), merged.map { it.routeNo })
    }

    @Test
    fun `요일을 앱의 세 구분으로 옮긴다`() {
        assertEquals(com.eunho.tagohub.data.DayKind.Weekday, StopBoard.dayKind(DayOfWeek.MONDAY))
        assertEquals(com.eunho.tagohub.data.DayKind.Saturday, StopBoard.dayKind(DayOfWeek.SATURDAY))
        assertEquals(com.eunho.tagohub.data.DayKind.Holiday, StopBoard.dayKind(DayOfWeek.SUNDAY))
    }

    @Test
    fun `자정을 넘긴 시각도 시계로 표시한다`() {
        assertEquals("00:10", StopBoard.clock(1440 + 10))
        assertEquals("23:50", StopBoard.clock(23 * 60 + 50))
    }
}
