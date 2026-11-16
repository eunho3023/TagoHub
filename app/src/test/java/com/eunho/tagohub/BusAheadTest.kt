package com.eunho.tagohub

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.data.BusAhead
import com.eunho.tagohub.data.GpsTracker
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.SeoulBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실시간 버스를 늦은 만큼 앞으로 당겨 그리기 */
class BusAheadTest {
    private val o = LatLng(37.5, 127.0)
    private fun north(m: Double) = BusAhead.move(o, 0.0, m)

    @Test fun speedFromTwoFixes() {
        assertEquals(10.0, BusAhead.speed(o, north(140.0), 14_000), 0.1)
        assertEquals(0.0, BusAhead.speed(null, north(140.0), 14_000), 0.0)
        assertEquals(0.0, BusAhead.speed(o, north(5.0), 14_000), 0.0)       // 거의 그대로 = 멈춤
        assertEquals(0.0, BusAhead.speed(o, north(1000.0), 10_000), 0.0)     // 시속 360km = GPS 튐
    }

    @Test fun aheadGrowsWithAgeAndTimeButIsCapped() {
        // 늦은 시간의 절반만: 받을 때 20초 전 자료 → 20초 × 10m/s × 0.5 = 100m, 받은 뒤 5초 → 125m, 앞 정류장 80m 면 80m
        assertEquals(100.0, BusAhead.aheadM(10.0, 0, 20_000, 0), 1e-9)
        assertEquals(125.0, BusAhead.aheadM(10.0, 0, 20_000, 5_000), 1e-9)
        assertEquals(250.0, BusAhead.aheadM(18.0, 0, 30_000, 5_000), 1e-9)   // 상한 250m
        assertEquals(80.0, BusAhead.aheadM(10.0, 0, 20_000, 5_000, 80.0), 1e-9)
        assertEquals(0.0, BusAhead.aheadM(10.0, 0, 70_000, 0), 0.0)          // 너무 옛 자료
        assertEquals(0.0, BusAhead.aheadM(0.0, 0, 20_000, 0), 0.0)           // 멈춤
        assertEquals(BusAhead.UNKNOWN_AGE_MS, BusAhead.ageAt(1_000, null))
        assertEquals(28_000L, BusAhead.ageAt(30_000, 2_000))
    }

    @Test fun stopAheadOnlyInFrontAndNearTheLine() {
        val stops = listOf(north(5.0), north(120.0), BusAhead.move(north(60.0), 90.0, 80.0), north(-50.0), north(200.0))
        assertEquals(120.0, BusAhead.stopAhead(o, 0.0, stops)!!, 0.5)       // 옆 80m·뒤·바로 옆 5m 는 빼고
        assertNull(BusAhead.stopAhead(o, 180.0, listOf(north(120.0))))
        val p = BusAhead.move(o, 45.0, 100.0)
        assertEquals(100.0, Geo.meters(o.lat, o.lng, p.lat, p.lng), 0.5)
    }

    @Test fun trackerKnowsSpeedAndWhenTheBusStopped() {
        val t0 = 1_000_000L
        var s = GpsTracker.next(null, o, null, null, t0, dataAt = t0 - 20_000)
        assertEquals(20_000L, s.track.ageMs)
        s = GpsTracker.next(s, north(140.0), null, null, t0 + 10_000, dataAt = t0 - 6_000)   // 자료 시각 14초 차에 140m
        assertEquals(10.0, s.track.speedMps, 0.1)
        assertEquals(16_000L, s.track.ageMs)
        assertFalse(s.still)
        val same = GpsTracker.next(s, north(141.0), null, null, t0 + 20_000, dataAt = t0 - 6_000)  // 자료 시각 그대로 = 아직 안 바뀜
        assertFalse(same.still)
        val stopped = GpsTracker.next(s, north(141.0), null, null, t0 + 20_000, dataAt = t0 + 8_000) // 새 시각인데 그대로 = 멈춤
        assertTrue(stopped.still)
        assertFalse(GpsTracker.next(stopped, north(300.0), null, null, t0 + 30_000, dataAt = t0 + 22_000).still)   // 다시 달림
        // 자료 시각을 모르면(TAGO) 그대로 와도 멈춤으로 보지 않음 (자료가 23~90초마다 바뀜) — 대신 40초까지만 당김
        var u = GpsTracker.next(null, o, null, null, t0)
        u = GpsTracker.next(u, north(100.0), null, null, t0 + 10_000)
        repeat(4) { k -> u = GpsTracker.next(u, north(101.0), null, null, t0 + 20_000 + k * 10_000L) }
        assertFalse(u.still)
        assertEquals(100.0, BusAhead.aheadM(5.0, 0, BusAhead.UNKNOWN_AGE_MS, 90_000), 1e-9)   // 15초 + 받은 뒤 90초여도 40초치만
    }

    @Test fun seoulDataTime() {
        assertEquals(java.time.Instant.parse("2026-11-16T11:15:30Z").toEpochMilli(), SeoulBus.dataTimeMs("20261116201530"))
        assertNull(SeoulBus.dataTimeMs("2026-11-16"))
        assertNull(SeoulBus.dataTimeMs(null))
    }
}
