package com.eunho.tagohub

import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.StaticCache
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class StaticCacheTest {
    @Test
    fun savesAndReads_keptForever_clearedOnAppUpdate() {
        val root = Files.createTempDirectory("tc").toFile()
        StaticCache.init(root, appVersion = 10)
        var now = 1_000_000L
        StaticCache.clock = { now }
        StaticCache.putShape("R1", listOf(LatLng(35.8, 127.1), LatLng(35.81, 127.11)))
        StaticCache.putRouteStops("R1", listOf(RouteStop("S1", "탭\t있는 이름", null, 1, "0", 35.8, null)))
        val route = BusRoute("R1", "100", "간선", "기점", null, "05:30", "23:00", 15, null, 30, "35010")
        StaticCache.putRoute("R1", route)
        StaticCache.putPairs("cities", listOf("35010" to "전주시"))

        assertEquals(2, StaticCache.shape("R1")!!.size)
        val st = StaticCache.routeStops("R1")!!.single()
        assertEquals("탭 있는 이름", st.name)   // 탭은 공백으로
        assertEquals(null, st.nodeNo)          // null 그대로
        assertEquals(null, st.lng)
        assertEquals(route, StaticCache.route("R1"))
        assertEquals("전주시", StaticCache.pairs("cities")!!.single().second)

        now += 365L * 24 * 60 * 60 * 1000       // 1년이 지나도 그대로 (한 번 받으면 계속)
        assertEquals(route, StaticCache.route("R1"))

        StaticCache.init(root, appVersion = 10)  // 앱을 다시 켜도(같은 버전) 그대로
        assertEquals(2, StaticCache.shape("R1")!!.size)

        StaticCache.init(root, appVersion = 11)  // 앱 업데이트 → 비우고 새로 받음
        assertEquals(null, StaticCache.shape("R1"))
        assertEquals(null, StaticCache.route("R1"))
        StaticCache.clock = { System.currentTimeMillis() }
    }

    /** 지하철 역 ID·시간표·출구: 한 번 받으면 저장 (한글 역명끼리 파일이 겹치지 않게) */
    @Test
    fun subway_stationsScheduleExits() {
        val root = kotlin.io.path.createTempDirectory("sc_subway").toFile()
        StaticCache.init(root, appVersion = 1)
        val seoul = listOf(com.eunho.tagohub.data.SubwayStation("MTRS11133", "서울역", "서울 1호선"))
        val sinchon = listOf(com.eunho.tagohub.data.SubwayStation("MTRS12240", "신촌", "서울 2호선"))
        StaticCache.putSubwayStations("서울역", seoul)
        StaticCache.putSubwayStations("신촌", sinchon)
        org.junit.Assert.assertEquals(seoul, StaticCache.subwayStations("서울역"))
        org.junit.Assert.assertEquals(sinchon, StaticCache.subwayStations("신촌"))   // 한글이 _ 로 바뀌어 겹치지 않음
        org.junit.Assert.assertEquals(null, StaticCache.subwayStations("시청"))

        val dep = listOf(com.eunho.tagohub.data.SubwayDeparture(5 * 60 + 32, "MTRS11100", "소요산", false),
            com.eunho.tagohub.data.SubwayDeparture(24 * 60 + 41, "MTRS11133", "서울역", true))
        StaticCache.putSchedule("MTRS11133", "01", "U", dep)
        org.junit.Assert.assertEquals(dep, StaticCache.schedule("MTRS11133", "01", "U"))
        org.junit.Assert.assertEquals(null, StaticCache.schedule("MTRS11133", "02", "U"))   // 요일 따로

        val ex = listOf(com.eunho.tagohub.data.ExitInfo("1", listOf("103", "150", "N15"), listOf("서울역 버스환승센터")),
            com.eunho.tagohub.data.ExitInfo("2", emptyList(), listOf("롯데마트", "KTX 매표소")))
        StaticCache.putExits("MTRS11133", ex)
        org.junit.Assert.assertEquals(ex, StaticCache.exits("MTRS11133"))
        StaticCache.putExits("X", emptyList())                                               // 빈 결과는 저장 안 함
        org.junit.Assert.assertEquals(null, StaticCache.exits("X"))
    }
}
