package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.GpsTracker
import com.eunho.tagohub.data.StaleGuard
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteGeom
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrackingTest {
    // 동쪽으로 곧게 뻗은 노선 (꼭짓점 약 9m 간격), 정류장 5개
    private val shape = (0..400).map { LatLng(35.80, 127.10 + it * 0.0001) }
    private val stops = (0..4).map { k -> RouteStop("S$k", "S$k", null, k + 1, null, 35.80, 127.10 + k * 0.01) }
    private val geom = RouteGeom(shape, stops)
    private fun at(m: Double, offN: Double = 0.0) = LatLng(35.80 + offN / 111_000.0, 127.10 + m / 90_600.0)

    @Test
    fun gpsOffTheRoad_isSnappedOntoLine() {
        // 도로에서 25m 북쪽으로 벗어난 GPS → 선 위(위도 35.80)로
        val s = GpsTracker.next(null, at(300.0, offN = 25.0), 1, geom, 0)
        assertEquals(35.80, s.track.to.lat, 1e-7)
        assertTrue(abs(s.track.progress!! - 300.0) < 10)
        // 이동 경로의 모든 점이 선 위
        val s2 = GpsTracker.next(s, at(420.0, offN = -18.0), 1, geom, 10_000)
        assertTrue(GpsTracker.path(s2.track, geom.line).all { abs(it.lat - 35.80) < 1e-7 })
    }

    @Test
    fun liveGps_detectedOnlyWhenMovingWithinSameStop() {
        // 정류장 좌표만 주는 지역: 정류장이 바뀔 때만 좌표가 바뀜 → live 아님
        var s = GpsTracker.next(null, at(0.0), 1, geom, 0)
        s = GpsTracker.next(s, at(0.0), 1, geom, 10_000)
        s = GpsTracker.next(s, at(900.0), 2, geom, 60_000)
        assertEquals(false, s.live)
        // 진짜 GPS: 같은 정류장 순번에서 좌표가 움직임 → live
        var g = GpsTracker.next(null, at(100.0), 1, geom, 0)
        g = GpsTracker.next(g, at(180.0), 1, geom, 10_000)
        assertEquals(true, g.live)
    }

    @Test
    fun staleBackwardUpdate_isIgnored() {
        var s = GpsTracker.next(null, at(500.0), 1, geom, 0)
        s = GpsTracker.next(s, at(700.0), 1, geom, 10_000)
        val ahead = s.track
        s = GpsTracker.next(s, at(520.0), 1, geom, 20_000)
        assertEquals(ahead, s.track)
        assertEquals(1, s.backStreak)
        s = GpsTracker.next(s, at(760.0), 1, geom, 30_000)
        assertTrue(s.track.progress!! > ahead.progress!!)
    }

    @Test
    fun persistentBackward_jumpsWithoutSliding() {
        var s = GpsTracker.next(null, at(800.0), 1, geom, 0)
        repeat(2) { s = GpsTracker.next(s, at(300.0), 1, geom, 10_000L * (it + 1)) }
        assertTrue(s.track.to.lng > at(700.0).lng)
        s = GpsTracker.next(s, at(300.0), 1, geom, 30_000)
        assertEquals(null, s.track.from)
    }

    @Test
    fun smallJitter_staysStill() {
        var s = GpsTracker.next(null, at(500.0), 1, geom, 0)
        val first = s.track
        s = GpsTracker.next(s, at(505.0), 1, geom, 10_000)
        assertEquals(first, s.track)
    }

    @Test
    fun newTrip_fromRouteStart_jumps() {
        var s = GpsTracker.next(null, at(3600.0), 5, geom, 0)
        s = GpsTracker.next(s, at(50.0), 1, geom, 10_000)
        assertEquals(null, s.track.from)
    }

    @Test
    fun noShape_usesStopsLine() {
        val g = RouteGeom(null, stops)
        val s = GpsTracker.next(null, at(450.0, offN = 30.0), 1, g, 0)
        assertEquals(35.80, s.track.to.lat, 1e-7) // 정류장을 이은 선 위
    }

    /** GPS 오차·시차로 조금 뒤로 온 위치는 따라가지 않고 그 자리 */
    @Test
    fun smallBackward_holdsInPlace() {
        var s = GpsTracker.next(null, at(500.0), 1, geom, 0)
        s = GpsTracker.next(s, at(600.0), 1, geom, 10_000)
        val ahead = s.track
        s = GpsTracker.next(s, at(590.0), 1, geom, 20_000) // 10m 뒤 (오차)
        assertEquals(ahead, s.track)
        assertEquals(0, s.backStreak)
    }

    /** 정류장에서 멀리 떨어진 좌표는 처음부터 실시간 GPS 로 (추정 → GPS 전환 때 뒤로 튀지 않게) */
    @Test
    fun farFromStop_isLiveImmediately() {
        assertEquals(true, GpsTracker.next(null, at(450.0), 1, geom, 0).live)   // 정류장 S0(0m)에서 450m
        assertEquals(false, GpsTracker.next(null, at(5.0), 1, geom, 0).live)    // 정류장 위 = 아직 모름
    }

    /** 정류장 순번이 줄어든 옛 응답은 직전 위치 유지, 3번 연속이면 받아들임 */
    @Test
    fun staleGuard_keepsPreviousOnOrdDrop() {
        fun bus(ord: Int) = BusLocation("n$ord", null, ord, "1234", null, null)
        val streaks = HashMap<String, Int>()
        var shown = listOf(bus(10))
        shown = StaleGuard.filter(shown, listOf(bus(11)), streaks, 60)
        assertEquals(11, shown.single().ord)                // 앞으로는 그대로
        shown = StaleGuard.filter(shown, listOf(bus(10)), streaks, 60)
        assertEquals(11, shown.single().ord)                // 옛 응답 → 유지
        shown = StaleGuard.filter(shown, listOf(bus(12)), streaks, 60)
        assertEquals(12, shown.single().ord)
        repeat(2) { shown = StaleGuard.filter(shown, listOf(bus(9)), streaks, 60) }
        assertEquals(12, shown.single().ord)
        shown = StaleGuard.filter(shown, listOf(bus(9)), streaks, 60)
        assertEquals(9, shown.single().ord)                 // 3번 연속 → 받아들임
        shown = StaleGuard.filter(listOf(bus(58)), listOf(bus(1)), streaks, 60)
        assertEquals(1, shown.single().ord)                 // 새 운행은 바로
    }
}
