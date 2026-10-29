package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatedArrivalTest {
    private fun arr(sec: Int?, prev: Int?, estimated: Boolean) = BusArrival("r", "309", null, null, prev, sec, estimated = estimated)

    @Test
    fun estimatedArrivalsShowRoundedMinutesInsteadOfExactSeconds() {
        assertEquals("약 10분 · 7정류장 전", ArrivalText.line(arr(573, 7, estimated = true)))
        assertEquals("약 1분 · 1정류장 전", ArrivalText.line(arr(40, 1, estimated = true)))
        assertEquals("정류장 도착", ArrivalText.line(arr(0, 0, estimated = true)))
    }

    @Test
    fun realArrivalsKeepExactSeconds() {
        assertEquals("9분 · 7정류장 전", ArrivalText.line(arr(573, 7, estimated = false)))
    }

    @Test
    fun countdownKeepsLoweringTheEstimate() {
        // 20초 지나면 573 → 553초: 약 9분
        assertEquals("약 9분 · 7정류장 전", ArrivalText.line(arr(573, 7, estimated = true), elapsedSec = 20))
        assertEquals("약 8분 · 7정류장 전", ArrivalText.line(arr(573, 7, estimated = true), elapsedSec = 90))
    }
}
