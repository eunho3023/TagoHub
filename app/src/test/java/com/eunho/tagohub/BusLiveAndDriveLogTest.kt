package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalLog
import com.eunho.tagohub.data.ArrivedHold
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusCompany
import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.BusSeen
import com.eunho.tagohub.data.DriveLog
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.ZoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class BusLiveAndDriveLogTest {
    private val stops = (1..5).map { RouteStop("N$it", "정류장$it", null, it, null, 35.8 + it * 0.003, 127.1) }
    private fun bus(ord: Int, no: String = "1916", lat: Double? = null, lng: Double? = null) = BusLocation("N$ord", null, ord, no, lat, lng)

    // ── GPS 없는 버스: 머문 시간으로 출발 어림 ──

    @Test
    fun aBusWithoutGpsLeavesItsStopAfterTheDwellTime() {
        assertNull(RouteLive.timedAlong(0L, 20_000L, 60.0))               // 20초: 아직 정류장
        assertEquals(0.5, RouteLive.timedAlong(0L, 55_000L, 60.0)!!, 1e-9)  // 25초 정차 + 30초/60초
        assertEquals(0.9, RouteLive.timedAlong(0L, 600_000L, 60.0)!!, 1e-9) // 다음 정류장 바로 앞에서 멈춤
        assertNull(RouteLive.timedAlong(null, 600_000L, 60.0))            // 언제 왔는지 모름
    }

    @Test
    fun placementPutsATimedBusBetweenStopsButNotAtTheTerminus() {
        val timed: (BusLocation, Int) -> Double? = { _, _ -> 0.5 }
        val p = RouteLive.placement(listOf(bus(1, "a"), bus(3, "b")), stops, timed)
        assertEquals(listOf(0), p.at.keys.toList())          // 기점(첫 정류장)은 출발 대기라 그대로
        assertEquals(listOf(2), p.between.keys.toList())     // 셋째 정류장을 떠나 넷째로 가는 중
        val pos = RouteLive.positions(listOf(bus(3)), stops, timed)
        assertEquals(listOf(2), pos.left)
        // 떠난 버스는 그 정류장에 "도착"으로 세지 않음
        assertNull(RouteLive.stopsAway(2, pos.at, pos.left))
        assertEquals(1, RouteLive.stopsAway(3, pos.at, pos.left))
    }

    @Test
    fun aBusWithRealGpsNearTheStopIsNotMovedByTime() {
        val s = stops[2]
        // 정류장에서 30m 떨어진 실제 GPS (60m 안이라 정차 중) → 시간으로 옮기지 않음
        val onStop = bus(3, lat = s.lat!! + 0.00027, lng = s.lng)
        val p = RouteLive.placement(listOf(onStop), stops) { _, _ -> 0.5 }
        assertEquals(listOf(2), p.at.keys.toList())
        // 좌표가 정류장 좌표 그대로면 GPS 가 아님 → 시간으로 어림
        assertTrue(RouteLive.noGps(bus(3, lat = s.lat, lng = s.lng), s))
    }

    @Test
    fun busSeenRemembersWhenAVehicleFirstShowedUpAtAStop() {
        BusSeen.reset()
        BusSeen.update("R", listOf(bus(2)), 1_000)
        BusSeen.update("R", listOf(bus(2)), 16_000)
        assertEquals(1_000L, BusSeen.since("R", "1916", 2))
        BusSeen.update("R", listOf(bus(3)), 31_000)
        assertEquals(31_000L, BusSeen.since("R", "1916", 3))
        assertNull(BusSeen.since("R", "1916", 2))
    }

    // ── 고른 정류장의 도착정보 ──

    @Test
    fun focusUsesTheApiOnlyWhenItAgreesWithPositions() {
        assertTrue(RouteLive.useApiAtFocus(4, emptyList()))   // 위치를 모르면 도착정보
        assertTrue(RouteLive.useApiAtFocus(3, listOf(2, 9)))  // 1정류장 차이는 같은 버스
        assertFalse(RouteLive.useApiAtFocus(4, listOf(2)))    // 전주 354: API 4정류장 · 29분, 위치 2정류장 → 위치
        assertFalse(RouteLive.useApiAtFocus(1, listOf(0)))    // 이미 와 있음
    }

    // ── "정류장 도착"이 계속 남지 않게 ──

    @Test
    fun arrivedBusIsDroppedAfterTheHoldTime() {
        val since = HashMap<String, Long>()
        val arrived = BusArrival("R", "354", null, null, 0, 0)
        val next = BusArrival("R", "354", null, null, 6, 900)
        assertEquals(2, ArrivedHold.filter("R", listOf(arrived, next), since, 0).size)
        assertEquals(2, ArrivedHold.filter("R", listOf(arrived, next), since, 60_000).size)
        assertEquals(listOf(next), ArrivedHold.filter("R", listOf(arrived, next), since, 91_000))
        // 다음 버스로 바뀌면 기억을 지움
        assertEquals(listOf(next), ArrivedHold.filter("R", listOf(next), since, 120_000))
        assertTrue(since.isEmpty())
    }

    // ── 요일별 예상 시간표 ──

    @Test
    fun hitsSpreadTheArrivalTimesOverTheStopsPassed() {
        val hits = ArrivalLog.hits(mapOf("1916" to 1), 0L, listOf(bus(3)), 60_000L, stops)
        assertEquals(listOf("N2" to 30L, "N3" to 60L), hits.map { it.nodeId to it.epochSec })
        // 너무 오래 못 받았거나 뒤로 간 경우는 기록하지 않음
        assertTrue(ArrivalLog.hits(mapOf("1916" to 1), 0L, listOf(bus(3)), 10 * 60_000L, stops).isEmpty())
        assertTrue(ArrivalLog.hits(mapOf("1916" to 3), 0L, listOf(bus(2)), 60_000L, stops).isEmpty())
    }

    @Test
    fun tableGroupsTheSameRunAcrossWeeks() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toEpochSecond()
        // 10월 19·26일 = 월요일, 20일 = 화요일
        val hits = listOf(
            ArrivalLog.Hit("S", at(19, 6, 9)), ArrivalLog.Hit("S", at(26, 6, 7)), ArrivalLog.Hit("S", at(12, 6, 10)),
            ArrivalLog.Hit("S", at(19, 6, 21)), ArrivalLog.Hit("S", at(26, 6, 22)),
            ArrivalLog.Hit("S", at(20, 6, 30)),                 // 화요일
            ArrivalLog.Hit("X", at(19, 6, 9)),                  // 다른 정류장
        )
        val t = ArrivalLog.table(hits, "S", DayOfWeek.MONDAY, zone)
        assertEquals(3, t.days)
        assertEquals(2, t.slots.size)
        val first = t.slots[0]
        assertEquals(6 * 60 + 9, first.expected)
        assertEquals(6 * 60 + 7, first.earliest)
        assertEquals(6 * 60 + 10, first.latest)
        assertEquals(1, ArrivalLog.table(hits, "S", DayOfWeek.TUESDAY, zone).slots.size)
    }

    // ── 운수회사 ──

    @Test
    fun companyNameLosesThePhoneNumber() {
        assertEquals("동아운수", BusCompany.clean("동아운수 02-123-4567"))
        assertEquals("(주)경기버스", BusCompany.clean("(주)경기버스"))
        assertNull(BusCompany.clean("  "))
    }

    // ── 주행 기록 ──

    @Test
    fun driveLogRecordsCameraPassesAndSpeeding() {
        val cam = SafetyZone(ZoneKind.SpeedCamera, "시험 카메라", 37.5005, 127.0, 50)
        DriveLog.last.value = null
        DriveLog.start("자유주행", listOf(cam), 0)
        // 북쪽으로 약 11m 마다, 카메라 앞에서 58km/h
        for (i in 0..100) DriveLog.add(37.4950 + i * 0.0001, 127.0, if (i in 48..52) 58 else 40, i * 1_000L)
        DriveLog.finish(101_000L)
        val s = assertNotNullAndGet(DriveLog.last.value)
        assertEquals(1, s.cameras.size)
        assertEquals(58, s.cameras[0].speedKmh)
        assertEquals(8, s.cameras[0].overBy)
        assertEquals(1, s.speedingCount)
        assertEquals(58, s.maxKmh)
        assertTrue(s.meters > 1_000)
    }

    @Test
    fun shortDrivesLeaveNoSummary() {
        DriveLog.last.value = null
        DriveLog.start("경로 안내", emptyList(), 0)
        DriveLog.add(37.5, 127.0, 10, 0)
        DriveLog.add(37.5001, 127.0, 10, 5_000)
        DriveLog.finish(6_000)
        assertNull(DriveLog.last.value)
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
