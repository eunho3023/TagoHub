package com.eunho.tagohub

import com.eunho.tagohub.data.Ferry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** 여객선: 앱 파일(기항지·운항노선) 해석과 운항 스케줄 → 출발·도착 항구 사이 운항편 */
class FerryTest {
    private fun asset(name: String) = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.exists() }.readText()

    private val schedule = """{"response":{"header":{"resultCode":"200"},"body":{"items":{"item":[
        {"rlvt_ymd":"20261119","sail_tm":"830","psnshp_nm":"코리아스타","oport_cd":"D000","oport_nm":"인천","dest_cd":"D017","dest_nm":"대이작","lcns_seawy_cd":"X01","lcns_seawy_nm":"인천-대이작","nvg_seawy_cd":"01","nvg_seawy_nm":"인천대이작","nvg_drc_cd":"1","nvg_se_nm":"정상","nvg_stts_nm":"출항전"},
        {"rlvt_ymd":"20261119","sail_tm":"1430","psnshp_nm":"코리아스타","oport_cd":"D017","oport_nm":"대이작","dest_cd":"D000","dest_nm":"인천","lcns_seawy_cd":"X01","lcns_seawy_nm":"인천-대이작","nvg_seawy_cd":"01","nvg_seawy_nm":"인천대이작","nvg_drc_cd":"2","nvg_se_nm":"통제","nvg_stts_nm":"출항전","cntrl_rsn_nm":"풍랑주의보"}
    ]},"totalCount":2}}}"""

    private val lines = Ferry.parseLines("""{"X01|01":{"n":"인천대이작","c":0,"f":100,"r":110,"s":[["D000","YY"],["D016","YN"],["D017","YY"]]}}""")

    @Test
    fun assetFilesParse() {
        val ports = Ferry.parsePorts(asset("ferry_ports.json"))
        assertTrue(ports.size > 300)
        assertTrue(ports.any { it.name == "제주" && it.lat != null })
        val all = Ferry.parseLines(asset("ferry_lines.json"))
        assertTrue(all.size > 1000)
        assertTrue(all.values.all { it.stops.isNotEmpty() })
    }

    @Test
    fun forwardAndReverseStops() {
        val s = Ferry.parseSailings(schedule)
        assertEquals(2, s.size)
        assertEquals("0830", s[0].time)
        assertEquals(listOf("D000", "D016", "D017"), Ferry.stopsOf(s[0], lines["X01|01"]))
        // 역방향은 거꾸로, 역방향에 안 서는 곳(YN)은 빠짐
        assertEquals(listOf("D017", "D000"), Ferry.stopsOf(s[1], lines["X01|01"]))
    }

    @Test
    fun tripsBetweenPorts() {
        val s = Ferry.parseSailings(schedule)
        val day = LocalDate.of(2026, 11, 19)
        val out = Ferry.trips(s, lines, emptyMap(), "D000", "D017", day)
        assertEquals(1, out.size)
        assertEquals(10, out[0].arr!!.hour)   // 08:30 + 100분
        assertTrue(out[0].kind.contains("경유 1곳"))
        // 중간 항구까지는 도착 시각을 모름
        assertNull(Ferry.trips(s, lines, emptyMap(), "D000", "D016", day).single().arr)
        val back = Ferry.trips(s, lines, emptyMap(), "D017", "D000", day).single()
        assertTrue(back.kind.contains("통제(풍랑주의보)"))
        assertEquals(16, back.arr!!.hour)   // 14:30 + 110분
        assertTrue(Ferry.trips(s, lines, emptyMap(), "D017", "D016", day).isEmpty())
    }

    @Test
    fun seaObsParseAndNearest() {
        val text = """#START7777
#  1. TP
B, 202611192300,    22101,               덕적도, 126.01880000,  37.23610000,   1.7, 355,  11.8,  15.9,  16.5,   4.2, 1018.5,  60.0, ,=
B, 202611192300,    22107,               마라도, 126.03330000,  33.08330000, -99.0, 316,  14.9,  20.1,  22.3,  12.5, 1020.1,  71.0, ,=
#7777END"""
        val all = com.eunho.tagohub.data.SeaObs.parse(text)
        assertEquals(2, all.size)
        assertNull(all[1].wave)
        val near = com.eunho.tagohub.data.SeaObs.nearest(all, 37.45, 126.6)!!   // 인천항
        assertEquals("22101", near.id)
        assertEquals("파고 1.7m · 바람 11m/s", com.eunho.tagohub.data.SeaObs.text(near))
        assertNull(com.eunho.tagohub.data.SeaObs.nearest(all, 35.1, 129.0))   // 부산: 60km 안에 없음
    }
}
