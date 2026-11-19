package com.eunho.tagohub

import com.eunho.tagohub.data.ParkingLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 실시간 빈자리 주차장: 앱 파일과 API 응답 해석, 지도 점 글 */
class ParkingLiveTest {
    @Test
    fun assetLotsParse() {
        // 위치 파일은 주간 갱신이 조금씩 모음 (서버가 느려 한 번에 다 못 받음): 아직 없으면 건너뜀
        val file = listOf(File("src/main/assets/parking_live.json"), File("app/src/main/assets/parking_live.json")).firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue(file != null)
        val text = file!!.readText()
        val lots = ParkingLive.parseLots(text)
        assertTrue(lots.size > 300)
        assertTrue(lots.all { it.lat in 33.0..39.0 && it.lng in 124.0..132.0 })
    }

    @Test
    fun countsAndFeatureText() {
        val c = ParkingLive.parseCounts("""{"PrkRealtimeInfo":[{"pkfc_Available_ParkingLots_total":"12","pkfc_ParkingLots_total":"49","prk_center_id":"A"},{"pkfc_Available_ParkingLots_total":"0","pkfc_ParkingLots_total":"68","prk_center_id":"B"},{"pkfc_Available_ParkingLots_total":"3","pkfc_ParkingLots_total":"0","prk_center_id":"C"}],"resultCode":"0"}""")
        assertEquals(setOf("A", "B"), c.keys)
        val lot = ParkingLive.Lot("A", "아카데미주차장", 37.6, 127.0, 71, "공영", "유료")
        assertEquals("빈자리 12면 / 49면 · 공영 · 유료 · 실시간", ParkingLive.feature(lot, c["A"]).details)
        assertEquals("parking:live:12:A", ParkingLive.feature(lot, c["A"]).id)
        assertTrue(ParkingLive.feature(lot.copy(id = "B"), c["B"]).details.startsWith("만차"))
        assertEquals("71면 · 공영 · 유료", ParkingLive.feature(lot, null).details)
    }
}

/** 지하철역 승강기 가동 현황 (서버 /w/topis/elevator 응답) */
class SeoulLiftTest {
    @Test
    fun parseAndMatchStation() {
        val m = com.eunho.tagohub.data.SeoulTopis.parseLifts("""{"강남":[["EV","1번 출입구","B1-1F",1,"2"],["ES","2번 출입구","B1-1F",0,"2"]],"서울":[["WL","","",1,"1"]]}""")
        assertEquals(2, m["강남"]!!.size)
        assertTrue(!m["강남"]!![1].ok)
        assertEquals("에스컬레이터", m["강남"]!![1].kindName)
        assertEquals("서울", com.eunho.tagohub.data.SeoulTopis.liftKey("서울역"))
        assertEquals("강남", com.eunho.tagohub.data.SeoulTopis.liftKey("강남(2)"))
    }
}

/** 빠른하차 앱 파일 */
class FastExitTest {
    @Test
    fun fileParsesAndGroups() {
        val text = listOf(File("src/main/assets/subway_fast_exit.json"), File("app/src/main/assets/subway_fast_exit.json")).first { it.exists() }.readText()
        val t = com.eunho.tagohub.data.FastExit.parse(text)
        assertTrue(t.size > 200)
        val g = com.eunho.tagohub.data.FastExit.groups(listOf(
            com.eunho.tagohub.data.FastExit.Row("2호선", "역삼", "2-4", "계단"),
            com.eunho.tagohub.data.FastExit.Row("2호선", "역삼", "8-1", "엘리베이터"),
            com.eunho.tagohub.data.FastExit.Row("2호선", "역삼", "4-3", "계단"),
        ))
        assertEquals(listOf(com.eunho.tagohub.data.FastExit.Group("2호선 역삼 방면", "엘리베이터 8-1 · 계단 2-4, 4-3")), g)
    }
}

/** 전기차 충전소 (한전) 응답 해석·충전소별 묶기 */
class EvChargerTest {
    @Test
    fun parseGroupAndText() {
        val body = """{"data":[
            {"addr":"서울특별시 서초구 효령로 72길 60","chargeTp":"1","cpId":"107","cpNm":"완속02","cpStat":"1","csId":"102","csNm":"서초지사","lat":"37.4856","longi":"127.0280"},
            {"addr":"서울특별시 서초구 효령로 72길 60","chargeTp":"2","cpId":"108","cpNm":"급속01","cpStat":"2","csId":"102","csNm":"서초지사","lat":"37.4856","longi":"127.0280"},
            {"addr":"서울특별시 서초구 효령로 72길 60","chargeTp":"2","cpId":"109","cpNm":"급속02","cpStat":"3","csId":"102","csNm":"서초지사","lat":"37.4856","longi":"127.0280"},
            {"addr":"x","chargeTp":"1","cpId":"1","cpStat":"1","csId":"9","csNm":"잘못된 좌표","lat":"0","longi":"0"}
        ]}"""
        val ch = com.eunho.tagohub.data.EvCharger.parse(body)
        assertEquals(3, ch.size)
        val st = com.eunho.tagohub.data.EvCharger.stations(ch).single()
        assertEquals(1, st.free)
        val f = com.eunho.tagohub.data.EvCharger.feature(st)
        assertEquals("ev:1:102", f.id)
        assertEquals("충전 가능 1/3대 · 급속 2대 · 점검 중 1대 · 한전 · 서울특별시 서초구 효령로 72길 60", f.details)
    }
}

/** 홈 날씨·미세먼지 계산 */
class HomeWeatherTest {
    private val w = com.eunho.tagohub.data.HomeWeather

    @Test
    fun gridMatchesKmaTable() {
        assertEquals(60 to 127, w.grid(37.5665, 126.9780))   // 서울 중구 (기상청 격자표 60,127)
        assertEquals(98 to 76, w.grid(35.1796, 129.0756))    // 부산 (98,76)
    }

    @Test
    fun parseNowAirAndLines() {
        val (t, rain) = w.parseNow("""{"response":{"body":{"items":{"item":[{"category":"PTY","obsrValue":"1"},{"category":"T1H","obsrValue":"7.4"}]}}}}""")
        assertEquals(7.4, t!!, 1e-9)
        assertEquals("비", rain)
        assertEquals("보통", w.parseAir("""{"response":{"body":{"items":[{"pm10Value":"19","pm25Value":"20"},{"pm10Value":"-","pm25Value":"8"},{"pm10Value":"40","pm25Value":"30"}]}}}"""))
        assertEquals("서울", w.sidoOf(37.56, 126.97))
        assertEquals("경기", w.sidoOf(37.27, 127.01))
        assertEquals("7° 비" to "미세먼지 좋음", w.lines(com.eunho.tagohub.data.HomeWeather.Now(7.4, "비", "좋음")))
    }
}
