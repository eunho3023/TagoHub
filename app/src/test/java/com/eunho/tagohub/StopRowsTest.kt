package com.eunho.tagohub

import com.eunho.tagohub.core.JeonjuXml
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.StopMerge
import com.eunho.tagohub.ui.bus.mergeStopRows
import com.eunho.tagohub.ui.bus.outsideService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRowsTest {
    private fun rows(name: String) = JeonjuXml.parse(javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8))

    /** 실제 전주 정류장 경유노선 응답: 같은 번호도 기점·종점별로 */
    @Test
    fun jeonjuStopRoutes_fromRealResponse() {
        val r = rows("jeonju_stoproutes.xml").map(JeonjuMappers::stopRoute)
        assertEquals(5, r.size)
        val first = r.first()
        assertEquals("JUB305001349", first.routeId)
        assertEquals("21", first.routeNo)
        assertEquals("이서회차지", first.startName)
        assertEquals("신일해피트리", first.endName)
        assertEquals("22:10", first.firstTime)
        assertEquals("07:00", r[1].firstTime)
        assertEquals("12:40", r[1].lastTime)
    }

    @Test
    fun jeonjuRouteInfo_splitsArrowNames() {
        val info = JeonjuMappers.routeInfo(rows("jeonju_routeinfo.xml").single())
        assertEquals("JUB305001271", info.routeId)
        assertEquals("3", info.routeNo)
        assertEquals("전주대학교", info.startName)
        assertEquals("전주대학교", info.endName)
        assertEquals("06:00", info.firstTime)
        assertEquals("21:45", info.lastTime)
        assertEquals("순환", info.routeType)
    }

    @Test
    fun ended_whenNoArrivalOutsideServiceHours() {
        assertTrue(outsideService("06:00", "21:45", 23 * 60 + 40))     // 23:40 → 운행종료
        assertTrue(!outsideService("06:00", "21:45", 12 * 60))         // 낮 → 운행 중
        assertTrue(!outsideService("05:30", "00:30", 23 * 60 + 50))    // 막차가 자정 넘김
        assertTrue(!outsideService(null, null, 3 * 60))                // 시간 모르면 운행종료라고 안 함
        val routes = rows("jeonju_stoproutes.xml").map(JeonjuMappers::stopRoute)
        val at15 = mergeStopRows(emptyList(), routes, nowMin = 15 * 60)
        // 20번(07:00~12:40)은 15시에 도착 정보가 없으면 운행종료
        assertTrue(at15.first { it.routeNo == "20" }.ended)
        // 도착 정보가 있으면 운행종료가 아님
        val withArr = mergeStopRows(listOf(BusArrival(routes[1].routeId, "20", null, null, 3, 300)), routes, nowMin = 15 * 60)
        assertTrue(!withArr.first { it.routeNo == "20" }.ended)
    }

    /** 파일에 없는 다른 시군 정류장도 주변 정류장 목록에서 찾기 (시군마다 가장 가까운 하나, 30m 안) */
    @Test
    fun companionsFromNearbyList() {
        val jeonju = BusStop("35010", "JUB305100703", "기린대로 병무청", "30703", 35.82096, 127.15228)
        val nearby = listOf(
            jeonju,
            BusStop("35010", "JUB305100704", "기린대로 병무청", "30704", 35.82136, 127.152248),
            BusStop("35370", "TSB305000070", "병무청", "3050070", 35.82098, 127.15230),
            BusStop("35370", "TSB305000071", "병무청건너", "3050071", 35.82130, 127.15226),
            BusStop("35060", "TSB311009999", "동부시장", "3119999", 35.8226, 127.1520),
        )
        assertEquals(listOf("TSB305000070"), StopMerge.companions(jeonju, nearby).map { it.nodeId })
    }
}
