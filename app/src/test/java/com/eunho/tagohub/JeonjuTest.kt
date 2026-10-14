package com.eunho.tagohub

import com.eunho.tagohub.core.JeonjuXml
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.Jeonju
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.TripPlanner
import com.eunho.tagohub.data.JeonjuTimetable
import com.eunho.tagohub.data.DayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사용자가 받은 전주시 API 실제 응답(PDF)으로 검증 */
class JeonjuTest {
    private fun rows(name: String) = JeonjuXml.parse(javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8))

    @Test
    fun arrivals_lowFloor() {
        val a = rows("jeonju_arrivals.xml").map(JeonjuMappers::arrival)
        assertEquals(5, a.size)
        assertEquals("JUB305001764", a[0].routeId)
        assertEquals("6001", a[0].routeNo)
        assertEquals(19, a[0].prevStops)
        assertEquals(1410, a[0].arrSeconds)
        assertEquals("저상버스", a[0].vehicleType)
        assertEquals("비전대", a[0].towards)
        assertEquals("비전대학교입구.데시앙아이린", a[0].atStop)
    }

    @Test
    fun positions_areWholeRouteStopList_withBuses() {
        val r = rows("jeonju_positions.xml")
        val stops = JeonjuMappers.routeStops(r) { null }
        assertEquals(56, stops.size)
        assertEquals("JUB305100650", stops.first().nodeId)
        assertEquals("전주대학교", stops.first().name)
        assertEquals((1..56).toList(), stops.map { it.ord })
        val buses = JeonjuMappers.buses(r) { null }
        assertEquals(listOf("1398", "1333", "1394"), buses.map { it.vehicleNo })
        assertEquals(listOf(false, false, false), buses.map { it.lowFloor }) // busLow 0 = 일반 차량
        assertEquals(15, buses[0].ord)
        assertEquals("전북대학교.농협앞", buses[0].nodeName)
    }

    @Test
    fun drawing_isWgs84() {
        val pts = JeonjuMappers.drawing(rows("jeonju_drawing.xml"))
        assertEquals(633, pts.size)
        assertEquals(LatLng(35.821342, 127.086257), pts.first())
        assertTrue(pts.all { it.lat in 35.6..36.0 && it.lng in 126.9..127.3 })
    }

    @Test
    fun transfers_dedupedAndPlanned() {
        val t = JeonjuMappers.transfers(rows("jeonju_transfer.xml"))
        // 74→3001, 74→3002, 75→3002, 75→3001 처럼 환승 정류장이 같아도 버스 조합이 달라 모두 다른 경로
        assertEquals(11, t.size)
        assertEquals(4, t.count { it.chgStopId == "305100916" })
        val first = t.first()
        assertEquals("309", first.startNo)
        assertEquals("200", first.finNo)
        assertEquals("이마트", first.chgName)
        val o = BusStop("35010", "JUB305100351", "출발", null, 35.8, 127.1)
        val d = BusStop("35010", "JUB305100713", "도착", null, 35.81, 127.11)
        val plans = TripPlanner.rank(TripPlanner.fromTransfers(o, d, 100.0, 200.0, t) { null })
        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.legs.size == 2 })
        val p = plans.first { it.legs[0].routeNo == "309" && it.legs[1].routeNo == "200" }
        assertEquals("JUB305100883", p.legs[0].alightStopId)
        assertEquals(p.legs[0].alightStopId, p.legs[1].boardStopId)
        assertEquals(25, p.stops)
    }

    @Test
    fun directs_foundFromRouteStopLists() {
        fun st(id: String, ord: Int) = RouteStop(id, id, null, ord, null, 35.8, 127.1)
        val routes = mapOf(
            "JUB1" to ("100" to listOf(st("A", 1), st("B", 2), st("C", 3), st("D", 4))),
            "JUB2" to ("200" to listOf(st("D", 1), st("C", 2), st("B", 3), st("A", 4))), // 반대 방향
        )
        val a = BusStop("35010", "A", "A", null, 0.0, 0.0)
        val c = BusStop("35010", "C", "C", null, 0.0, 0.0)
        val plans = TripPlanner.directs(listOf(a to 50.0), listOf(c to 80.0), routes)
        assertEquals(1, plans.size)
        assertEquals("100", plans[0].legs[0].routeNo)
        assertEquals(2, plans[0].stops)
        // 직통이 환승보다 앞
        val transfer = plans[0].copy(legs = plans[0].legs + plans[0].legs)
        assertEquals(1, TripPlanner.rank(listOf(transfer, plans[0])).first().legs.size)
    }

    @Test
    fun ids() {
        assertEquals("305032403", Jeonju.std("JUB305032403"))
        assertTrue(Jeonju.isJeonju("JUB305032403"))
    }

    /** 실제 노선(305001271, 56정류장)으로 직통 찾기: 전주대학교 → 전북대학교.농협앞 */
    @Test
    fun directs_onRealRoute() {
        val stops = JeonjuMappers.routeStops(rows("jeonju_positions.xml")) { null }
        val routes = mapOf("JUB305001271" to ("3-1" to stops))
        val from = BusStop("35010", "JUB305100650", "전주대학교", null, 0.0, 0.0)
        val to = BusStop("35010", "JUB306101044", "전북대학교.농협앞", null, 0.0, 0.0)
        val plans = TripPlanner.directs(listOf(from to 100.0), listOf(to to 150.0), routes)
        assertEquals(1, plans.size)
        assertEquals(14, plans[0].stops)
        assertEquals(1, plans[0].legs[0].fromSeq)
        assertEquals(15, plans[0].legs[0].toSeq)
        // 반대로 가면(전북대 → 전주대) 같은 노선 뒤쪽 순번에 전주대학교가 다시 나오지 않으면 직통 없음
        val back = TripPlanner.directs(listOf(to to 0.0), listOf(from to 0.0), routes)
        assertTrue(back.isEmpty() || back[0].legs[0].fromSeq < back[0].legs[0].toSeq)
    }

    /**
     * 가까운 환승을 골라야 함.
     * 출발 O(0,0) → 도착 D(0,0.02 동쪽 약 1.8km).
     * A노선: O 에서 동쪽으로 가다 중간 X(0.01) 를 지나 계속 동쪽.
     * B노선: X 에서 D 로 (가까운 환승).
     * C노선: A 가 멀리 북쪽으로 돌아간 끝 Y 에서 D 로 (먼 환승) → 선택되면 안 됨.
     */
    @Test
    fun nearestTransfer_beatsFarDetour() {
        fun rs(id: String, ord: Int, lat: Double, lng: Double) = RouteStop(id, id, null, ord, null, 35.80 + lat, 127.10 + lng)
        val a = TripPlanner.Line("A", "10", listOf(
            rs("O", 1, 0.0, 0.0), rs("a1", 2, 0.0, 0.005), rs("X", 3, 0.0, 0.010),
            rs("a3", 4, 0.01, 0.010), rs("a4", 5, 0.02, 0.010), rs("Y", 6, 0.03, 0.010),
        ))
        val b = TripPlanner.Line("B", "20", listOf(rs("X", 1, 0.0, 0.010), rs("b1", 2, 0.0, 0.015), rs("D", 3, 0.0, 0.020)))
        val c = TripPlanner.Line("C", "30", listOf(rs("Y", 1, 0.03, 0.010), rs("c1", 2, 0.02, 0.020), rs("c2", 3, 0.01, 0.020), rs("D", 4, 0.0, 0.020)))
        val o = BusStop("35010", "O", "O", null, 35.80, 127.10)
        val d = BusStop("35010", "D", "D", null, 35.80, 127.12)
        val plans = TripPlanner.rank(TripPlanner.nearestTransfers(listOf(o to 50.0), listOf(d to 50.0), listOf(a), listOf(b, c)))
        assertEquals("10>20", plans.first().legs.joinToString(">") { it.routeNo })
        assertEquals("X", plans.first().legs[0].alightStopId)
        assertTrue(plans.first().minutes < plans.last().minutes || plans.size == 1)
        // 먼 환승(C)은 크게 돌아가므로 걸러지거나 뒤로
        assertTrue(plans.none { it.legs[1].routeNo == "30" } || plans.indexOfFirst { it.legs[1].routeNo == "30" } > 0)
    }

    /** 다른 정류장(길 건너편 등)으로 걸어서 갈아타기 */
    @Test
    fun transfer_withShortWalk() {
        fun rs(id: String, ord: Int, lat: Double, lng: Double) = RouteStop(id, id, null, ord, null, 35.80 + lat, 127.10 + lng)
        val a = TripPlanner.Line("A", "10", listOf(rs("O", 1, 0.0, 0.0), rs("X1", 2, 0.0, 0.010)))
        val b = TripPlanner.Line("B", "20", listOf(rs("X2", 1, 0.0005, 0.010), rs("D", 2, 0.0005, 0.020))) // 약 55m 떨어진 정류장
        val o = BusStop("35010", "O", "O", null, 35.80, 127.10)
        val d = BusStop("35010", "D", "D", null, 35.8005, 127.12)
        val p = TripPlanner.nearestTransfers(listOf(o to 0.0), listOf(d to 0.0), listOf(a), listOf(b)).single()
        assertEquals("X1", p.legs[0].alightStopId)
        assertEquals("X2", p.legs[1].boardStopId)
        assertTrue(p.transferWalkM in 40.0..80.0)
    }

    /** 시간표: 실제 노선(305001271) 평일 53회 + 주말 목록 */
    @Test
    fun timetable_fromCourses() {
        val courses = JeonjuTimetable.courses(rows("jeonju_courses.xml"))
        assertEquals(53, courses.size)
        assertEquals(360, courses.first().minute)              // 06:00
        assertEquals(21 * 60 + 45, courses.last().minute)       // 21:45 = 노선 정보의 막차와 같음
        assertTrue(courses.any { it.startSeq == 8 })            // 중간 정류장에서 시작하는 운행
        val sch = JeonjuTimetable.schedule(rows("jeonju_routeinfo.xml").single())
        assertEquals(360, sch.firstMinute)
        assertEquals(21 * 60 + 45, sch.lastMinute)
        assertEquals(15, sch.saturday.size)
        assertEquals(6 * 60 + 15, sch.saturday.first())
        // 전북대학교.농협앞 = 순번 15. 정류장당 1분으로 가정하면 06:00 출발 → 06:14
        val stops = JeonjuMappers.routeStops(rows("jeonju_positions.xml")) { null }
        val seqs = stops.filter { it.nodeId == "JUB306101044" }.map { it.ord }
        val times = JeonjuTimetable.stopTimes(courses, seqs) { a, b -> (b - a).toDouble() }
        assertEquals("06:14", JeonjuTimetable.clock(times.first()))
        // 순번 8 에서 시작하는 운행은 7정류장만 더함
        val sat = JeonjuTimetable.stopTimes(JeonjuTimetable.startsFor(DayKind.Saturday, courses, sch), seqs) { a, b -> (b - a).toDouble() }
        assertEquals("06:29", JeonjuTimetable.clock(sat.first()))
    }
}
