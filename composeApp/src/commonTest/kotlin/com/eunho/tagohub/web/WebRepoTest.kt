package com.eunho.tagohub.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebRepoTest {
    private val seen = ArrayList<String>()

    private fun repo(routes: Map<String, String>) = WebRepo("https://p.example.workers.dev", HttpClient(MockEngine { req ->
        val url = req.url.toString()
        seen += url
        val hit = routes.entries.firstOrNull { url.contains(it.key) }
        if (hit == null) respond("", HttpStatusCode.NotFound) else respond(hit.value, HttpStatusCode.OK)
    }))

    // "전" = U+C804
    private val names = """[["JJB306000104","전주역","35.8235","127.1478","306000104","35010",["JJB1"]],
        ["DJB8001","대전 전민동","36.39","127.40",null,"25",[]],
        ["SEB1","전농동","37.57","127.05","05123","11",[]]]"""

    @Test fun searchUsesNameShardAndRanksPrefixFirst() = runTest {
        val r = repo(mapOf("data/names/c804.json" to names))
        val got = r.search("전")
        assertEquals(listOf("전농동", "전주역", "대전 전민동"), got.map { it.name })
        assertEquals("https://p.example.workers.dev/data/names/c804.json", seen.single())
        r.search("전주")   // 같은 조각은 다시 받지 않음
        assertEquals(1, seen.size)
        assertEquals(null, got[2].no)
        assertEquals(listOf("JJB1"), got[1].routes)
    }

    @Test fun arrivalsGoThroughWebPathSorted() = runTest {
        val xml = """<response><header><resultCode>00</resultCode></header><body><items>
            <item><routeid>R2</routeid><routeno>165</routeno><arrtime>600</arrtime><arrprevstationcnt>5</arrprevstationcnt></item>
            <item><routeid>R1</routeid><routeno>104</routeno><arrtime>90</arrtime><arrprevstationcnt>1</arrprevstationcnt></item>
            </items></body></response>"""
        val r = repo(mapOf("w/dg/1613000/ArvlInfoInqireService" to xml))
        val stop = WebRepo.parseStops(names)[0]
        val a = r.arrivals(stop, 1000L) as Arrivals.Ok
        assertEquals(listOf("104", "165"), a.list.map { it.routeNo })
        assertEquals(
            "https://p.example.workers.dev/w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList?cityCode=35010&nodeId=JJB306000104",
            seen.single { "/w/" in it },
        )
    }

    @Test fun nationalFileStopsAskAliasAndNearbyCities() = runTest {
        val stop = WebStop("TSB306000038", "전주역", 35.8487, 127.1606, "3060038", "TS306", emptyList())
        val tile = """[["TSB306000099","옆","35.85","127.161","1","35010",[]],["TSB1","먼","35.90","127.20","2","35020",[]],["X1","다른","35.849","127.16","3","99999",[]]]"""
        val r = repo(mapOf("data/cities.json" to """{"names":{},"alias":{"TS306":"35011"}}""", "data/tiles/" to tile))
        assertEquals(listOf("35011", "35010", "35020"), r.cityCandidates(stop))
    }

    @Test fun sameSpotStopsAreAskedFirst() = runTest {
        val stop = WebStop("TSB306000038", "전주역", 35.8487, 127.1606, "3060038", "TS306", emptyList())
        // 10m 옆 전주시 정류장(같은 곳), 200m 떨어진 정류장(다른 곳)
        val tile = """[["JJB306000038","전주역","35.84879","127.1606","1","35010",[]],["JJB9","먼 정류장","35.8505","127.1606","2","35010",[]]]"""
        val r = repo(mapOf("data/cities.json" to """{"names":{},"alias":{"TS306":"35010"}}""", "data/tiles/" to tile))
        assertEquals(listOf("JJB306000038"), r.sameSpot(stop).map { it.id })
        assertEquals(listOf("35010" to "JJB306000038", "35010" to "TSB306000038"), r.targets(stop))
    }

    @Test fun otherSourcesAreUnsupportedAndErrorsReported() = runTest {
        val stops = WebRepo.parseStops(names)
        val r = repo(emptyMap())
        assertEquals(Arrivals.Unsupported, r.arrivals(stops[2], 0L))
        assertTrue(seen.none { "/w/" in it })   // 실시간은 묻지 않음 (같은 자리 정류장 찾기로 칸 자료만 받음)
        assertTrue(r.arrivals(stops[1], 0L) is Arrivals.Failed)
    }
}

class StopRowsTest {
    private fun sum(id: String, no: String) = RouteSum(id, no, "기점", "종점", "간선", "05:30", "23:00", 12, 15, 20)
    private fun arr(id: String, no: String, sec: Int) = com.eunho.tagohub.data.BusArrival(id, no, "간선", null, 3, sec)

    @Test fun liveFirstThenTimetableByNumber() {
        val rows = mergeRows(
            listOf(sum("R100", "100"), sum("R2", "2"), sum("R10", "10"), sum("R5", "5")),
            listOf(arr("R10", "10", 300), arr("R5", "5", 60), arr("R5", "5", 900), arr("RNEW", "7", 400)),
        )
        assertEquals(listOf("5", "10", "7", "2", "100"), rows.map { it.routeNo })
        assertEquals(2, rows[0].arrivals.size)
        assertEquals(null, rows[2].sum)          // 실시간에만 있는 새 노선
        assertEquals(15, rows[3].sum!!.intervalOn(6))
        assertEquals(20, rows[3].sum!!.intervalOn(7))
    }

    @Test fun parsesSumsAndMergesSameSpotStops() {
        val m = WebRepo.parseSums("""{"R1":["104","송천동","평화동","일반","06:00","23:00",15,null,null],"R2":["5",null,null,null,null,null,null,null,null]}""")
        assertEquals("평화동", m["R1"]!!.end)
        assertEquals(15, m["R1"]!!.intervalOn(7))
        assertEquals(null, m["R2"]!!.first)
        val a = WebStop("DJB1", "대전역", 36.3320, 127.4340, "12370", "25", listOf("R1", "R2"))
        val b = WebStop("SJB1", "대전역", 36.33201, 127.43401, "12370", "12", emptyList())
        val far = WebStop("DJB2", "대전역", 36.3340, 127.4340, "12371", "25", listOf("R3"))
        assertEquals(listOf("DJB1", "DJB2"), WebRepo.mergeSameSpot(listOf(b, a, far)).map { it.id })
    }
}
