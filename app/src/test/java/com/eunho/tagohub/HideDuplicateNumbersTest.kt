package com.eunho.tagohub

import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.ui.bus.StopRouteRow
import com.eunho.tagohub.ui.bus.hideDuplicateNumbers
import org.junit.Assert.assertEquals
import org.junit.Test

class HideDuplicateNumbersTest {
    private fun row(id: String, no: String, vararg a: BusArrival) = StopRouteRow(id, no, null, null, null, a.toList())
    private fun arr(estimated: Boolean) = BusArrival("r", "165", null, null, 3, 120, estimated = estimated)

    @Test fun 실시간_줄이_있으면_같은_번호의_추정_줄은_숨김() {
        val rows = listOf(row("a", "165", arr(false)), row("b", "165", arr(true)), row("c", "2"))
        assertEquals(listOf("a", "c"), hideDuplicateNumbers(rows).map { it.routeId })
    }

    /** 도착이 오기 전과 뒤에 줄 수가 같아야 함 (같은 번호의 운행종료·정보없음 줄이 사라지지 않게) */
    @Test fun 도착_없는_같은_번호_줄은_남김() {
        val rows = listOf(row("a", "165", arr(false)), row("b", "165"), row("c", "165").copy(ended = true))
        assertEquals(listOf("a", "b", "c"), hideDuplicateNumbers(rows).map { it.routeId })
    }

    @Test fun 실시간이_둘_다_있으면_두_줄_유지() {
        val rows = listOf(row("a", "165", arr(false)), row("b", "165", arr(false)))
        assertEquals(2, hideDuplicateNumbers(rows).size)
    }
}
