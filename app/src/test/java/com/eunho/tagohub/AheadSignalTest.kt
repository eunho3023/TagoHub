package com.eunho.tagohub

import com.eunho.tagohub.data.AheadSignal
import com.eunho.tagohub.data.SignalColor
import com.eunho.tagohub.data.SignalIntersection
import com.eunho.tagohub.data.SignalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AheadSignalTest {
    // 북쪽으로 약 111m(0.001도)·222m·555m 떨어진 교차로와, 동쪽으로 약 90m 떨어진 교차로
    private val north1 = SignalIntersection("a", "북1", 37.501, 127.0)
    private val north2 = SignalIntersection("b", "북2", 37.502, 127.0)
    private val farNorth = SignalIntersection("c", "먼북", 37.505, 127.0)
    private val east = SignalIntersection("d", "동", 37.5, 127.001)
    private val all = listOf(north2, farNorth, east, north1)

    @Test
    fun picksTheNearestIntersectionAheadInTheDrivingDirection() {
        val got = AheadSignal.pick(37.5, 127.0, 0.0, all)
        assertEquals("a", got?.intersection?.id)
        assertEquals(111.0, got!!.distanceM, 2.0)
        assertEquals("d", AheadSignal.pick(37.5, 127.0, 90.0, all)?.intersection?.id)   // 동쪽으로 가면 동쪽 교차로
    }

    @Test
    fun nothingWhenBehindTooFarJustPassedOrHeadingUnknown() {
        assertNull(AheadSignal.pick(37.5, 127.0, 180.0, all))                     // 남쪽으로 가면 앞에 없음
        assertNull(AheadSignal.pick(37.5, 127.0, 0.0, listOf(farNorth)))         // 555m 는 너무 멂
        assertNull(AheadSignal.pick(37.501, 127.00005, 0.0, listOf(north1)))     // 바로 위(약 4m)는 이미 지난 것
        assertNull(AheadSignal.pick(37.5, 127.0, null, all))
    }

    @Test
    fun approachIsTheSideIComeFrom() {
        // 북쪽 교차로로 올라가는 중 → 남쪽에서 들어감
        assertEquals("st", AheadSignal.approach(north1, 37.5, 127.0))
        // 동쪽 교차로로 가는 중 → 서쪽에서 들어감
        assertEquals("wt", AheadSignal.approach(east, 37.5, 127.0))
    }

    @Test
    fun compassCodesAndAngles() {
        assertEquals("nt", AheadSignal.code(350.0))
        assertEquals("ne", AheadSignal.code(45.0))
        assertEquals("se", AheadSignal.code(135.0))
        assertEquals("nw", AheadSignal.code(-45.0))
        assertEquals(20.0, AheadSignal.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, AheadSignal.angleDiff(0.0, 180.0), 1e-9)
    }

    @Test
    fun onlyDrivingSignalsOfMyApproachInStraightLeftUturnOrder() {
        fun p(dir: String, kind: String) = SignalPhase(dir, kind, SignalColor.Red, 1000, "stop-And-Remain")
        val phases = listOf(p("st", "Lt"), p("st", "Pd"), p("nt", "St"), p("st", "St"), p("st", "Bs"), p("st", "Ut"))
        assertEquals(listOf("St", "Lt", "Ut"), AheadSignal.forApproach(phases, "st").map { it.kind })
        assertEquals(emptyList<SignalPhase>(), AheadSignal.forApproach(phases, "et"))
    }
}
