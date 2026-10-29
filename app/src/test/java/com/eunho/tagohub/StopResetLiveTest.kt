package com.eunho.tagohub

import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.ui.bus.BusDest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopResetLiveTest {
    private fun stop(id: String) = BusStop("25", id, id, null, null, null)

    @Test
    fun resetLiveForgetsLiveStateButKeepsTheRoutesAndPreset() {
        val preset = listOf(stop("B"))
        val d = BusDest.Stop(stop("A"), preset)
        d.lastArrivals = listOf(BusArrival("r1", "1", null, null, 1, 60))
        d.emptyStreak = 3
        d.compArrivals["k"] = emptyList()
        d.entries = listOf(stop("C"))
        d.companions = emptyList()
        d.lastRoutes = emptyList()

        d.resetLive()

        assertNull(d.lastArrivals)
        assertEquals(0, d.emptyStreak)
        assertEquals(true, d.compArrivals.isEmpty())
        assertNull(d.entries)
        assertEquals(preset, d.companions)
        assertEquals(emptyList<Any>(), d.lastRoutes)   // 경유노선은 안 바뀌어서 그대로
    }
}
