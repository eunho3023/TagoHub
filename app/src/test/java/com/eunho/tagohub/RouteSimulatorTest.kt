package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.NavEngine
import com.eunho.tagohub.data.NavRoute
import com.eunho.tagohub.data.RouteSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteSimulatorTest {
    private fun realRoute(): NavRoute {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        return NavRoute.from(KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!)
    }

    private class Result(val spoken: List<String>, val everOff: Boolean, val arrived: Boolean, val steps: Int)

    /** 서비스와 같은 방식: [tickMs] 마다 가상 위치를 만들어 엔진에 넣는다 */
    private fun run(route: NavRoute, factor: Int, tickMs: Long = 200L, deviateFromStep: Int = -1, deviateSteps: Int = 0): Result {
        val sim = RouteSimulator(route)
        val engine = NavEngine(route)
        val spoken = ArrayList<String>()
        var off = false
        var arrived = false
        var i = 0
        while (i < 200_000) {
            val offset = if (deviateFromStep >= 0 && i in deviateFromStep until deviateFromStep + deviateSteps) 300.0 else 0.0
            val fix = sim.step(tickMs, factor, offset)
            val st = engine.update(fix.lat, fix.lng, fix.speedMps, 5.0)
            spoken += st.speak
            if (st.offRoute) off = true
            if (st.arrived) arrived = true
            i++
            if (sim.finished && arrived) break
            if (sim.finished && i > 0 && st.progressMeters >= route.length - 1) { /* 도착 판정 한 번 더 */ }
        }
        return Result(spoken, off, arrived, i)
    }

    @Test
    fun pointAtFollowsTheRouteAndClampsAtTheEnds() {
        val r = realRoute()
        assertEquals(r.points.first(), r.pointAt(-50.0))
        assertEquals(r.points.last().lat, r.pointAt(r.length + 999.0).lat, 1e-9)
        val mid = r.pointAt(r.length / 2)
        // 중간 지점은 경로 위에 있어야 함 (경로까지 거리 ≈ 0)
        val seg = r.segmentAt(r.length / 2)
        assertTrue(r.project(mid.lat, mid.lng, seg).distance < 1.0)
    }

    @Test
    fun simulatorAdvancesBySpeedTimesFactorAndFinishesAtTheEnd() {
        val r = realRoute()
        val sim = RouteSimulator(r, speedMps = 20.0)
        sim.step(1000, 1)
        assertEquals(20.0, sim.distance, 1e-9)
        sim.step(1000, 5)
        assertEquals(120.0, sim.distance, 1e-9)
        assertFalse(sim.finished)
        val last = sim.step(Long.MAX_VALUE / 1_000_000, 1)
        assertTrue(sim.finished)
        assertEquals(r.length, sim.distance, 1e-6)
        assertEquals(0.0, last.speedMps, 0.0)
    }

    @Test
    fun everySpeedFactorAnnouncesTheTurnsAndArrivesWithoutFalseOffRoute() {
        val r = realRoute()
        for (factor in listOf(5, 15)) {
            val res = run(r, factor)
            assertFalse("배속 $factor 에서 이탈로 오판", res.everOff)
            assertTrue("배속 $factor 에서 도착 처리 안 됨", res.arrived)
            // 15배속은 한 번에 83m 씩 가서 일부 예고를 건너뛸 수 있어도, 회전 안내가 충분히 나와야 함
            val turns = res.spoken.count { "앞" in it }
            assertTrue("배속 $factor 안내 수 $turns", turns >= 12)
            assertEquals("목적지 부근 안내는 한 번", 1, res.spoken.count { "목적지 부근" in it })
        }
    }

    @Test
    fun deviationTestIsDetectedOnceAtAnySpeed() {
        val r = realRoute()
        // 5배속, 200ms 간격: 6초 = 30번 동안 북쪽으로 300m 벗어남
        val res = run(r, factor = 5, deviateFromStep = 2000, deviateSteps = 30)
        assertTrue("이탈 시험이 감지되지 않음", res.everOff)
        assertEquals(1, res.spoken.count { "벗어났" in it })
        assertTrue(res.arrived)
    }
}
