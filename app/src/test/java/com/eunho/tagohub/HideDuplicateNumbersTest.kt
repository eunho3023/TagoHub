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

    @Test fun 실시간이_둘_다_있으면_두_줄_유지() {
        val rows = listOf(row("a", "165", arr(false)), row("b", "165", arr(false)))
        assertEquals(2, hideDuplicateNumbers(rows).size)
    }
}
