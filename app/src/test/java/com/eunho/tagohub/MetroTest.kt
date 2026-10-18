package com.eunho.tagohub

import com.eunho.tagohub.core.SeoulXml
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.data.SeoulMappers
import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.data.StationName
import com.eunho.tagohub.ui.theme.subwayLineColor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

class MetroTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8)

    private val index by lazy { StationIndex.parseTsv(fixture("subway_stations.tsv")) }
    private val nationalIndex by lazy { StationIndex.parseTsv(fixture("subway_stations_asset.tsv")) }

    @Test
    fun stationFile_loadsAll() {
        assertEquals(1098, index.stations.size) // 7호선 이수 중복 1건 제거
    }

    @Test
    fun nameNormalization() {
        assertEquals("청량리", StationName.norm("청량리(서울시립대입구)"))
        assertEquals("서울", StationName.norm("서울역"))
        assertEquals("서울", StationName.norm("서울역(경의선)"))
        assertEquals("역삼", StationName.norm("역삼"))
        assertEquals("북구청", StationName.norm("북구청 (대구iM뱅크파크)"))
    }

    @Test
    fun line1Positions_allLocated() {
        val trains = SeoulXml.parse(fixture("seoul_position_line1.xml")).map(SeoulMappers::train)
        assertEquals(5, trains.size)
        trains.forEach { t ->
            val s = index.locate(t.lineId, t.stationName)
            assertTrue("위치를 못 찾음: ${t.stationName}", s != null)
        }
        // 종착역(경원선·경부선·경인선 구간)도 1호선으로 찾아져야 함
        listOf("소요산", "광운대", "서동탄", "인천").forEach { n ->
            assertTrue("행선지 못 찾음: $n", index.locate("1001", n) != null)
        }
        assertEquals("출발", SeoulMappers.statusText(trains[0].status))
        assertEquals(1, trains[2].upDown)
    }

    @Test
    fun seoulStation_groupsTransferLines() {
        val seoul = index.groups.first { g -> g.stations.any { it.line == "1호선" && it.norm == "서울" } }
        assertTrue(seoul.displayLines.containsAll(listOf("1호선", "4호선", "경의중앙선", "공항철도")))
        assertTrue(seoul.seoulLineIds.containsAll(listOf("1001", "1004", "1063", "1065")))
    }

    @Test
    fun daejeonLineOneUsesRegionalIdentityAndColor() {
        val station = nationalIndex.stations.first { it.operator.contains("대전") && it.line == "1호선" }

        assertEquals(null, station.seoulLine)
        assertEquals("대전 1호선", station.displayLine)
        assertEquals(Color(0xFF007448), subwayLineColor(station.displayLine))
    }

    @Test
    fun regionalSubwayLinesKeepTheirOwnColorsAndSeoulExtensionsStayMapped() {
        val regionalColors = mapOf(
            ("부산교통공사" to "1호선") to ("부산 1호선" to Color(0xFFF06A00)),
            ("부산교통공사" to "2호선") to ("부산 2호선" to Color(0xFF81BF48)),
            ("부산교통공사" to "3호선") to ("부산 3호선" to Color(0xFFBB8C00)),
            ("부산교통공사" to "4호선") to ("부산 4호선" to Color(0xFF217DCB)),
            ("대구교통공사" to "1호선") to ("대구 1호선" to Color(0xFFD93F5C)),
            ("대구교통공사" to "2호선") to ("대구 2호선" to Color(0xFF00AA80)),
            ("대구교통공사" to "3호선") to ("대구 3호선" to Color(0xFFFFB100)),
            ("광주교통공사" to "1호선") to ("광주 1호선" to Color(0xFF009088)),
        )
        regionalColors.forEach { (operatorLine, expected) ->
            val station = nationalIndex.stations.first {
                it.operator.contains(operatorLine.first) && it.line == operatorLine.second
            }
            assertEquals("${operatorLine.first} ${operatorLine.second}", expected.first, station.displayLine)
            assertEquals("${operatorLine.first} ${operatorLine.second}", null, station.seoulLine)
            assertEquals("${operatorLine.first} ${operatorLine.second}", expected.second, subwayLineColor(station.displayLine))
        }

        val incheon1 = nationalIndex.stations.first { it.operator.contains("인천교통공사") && it.line == "인천1호선" }
        val incheon2 = nationalIndex.stations.first { it.operator.contains("인천교통공사") && it.line == "인천2호선" }
        assertEquals(Color(0xFF7CA8D5), subwayLineColor(incheon1.displayLine))
        assertEquals(Color(0xFFED8B00), subwayLineColor(incheon2.displayLine))

        val incheon7 = com.eunho.tagohub.data.MetroStation(
            code = "3757",
            name = "부천시청",
            line = "7호선",
            lat = 37.504611,
            lng = 126.763561,
            operator = "인천교통공사",
        )
        val guui8 = nationalIndex.stations.first { it.operator.contains("구리도시공사") && it.line == "8호선" }
        val namyangju8 = nationalIndex.stations.first { it.operator.contains("남양주도시공사") && it.line == "8호선" }
        assertEquals("1007", incheon7.seoulLine?.id)
        assertEquals("1008", guui8.seoulLine?.id)
        assertEquals("1008", namyangju8.seoulLine?.id)

        val seoul1 = nationalIndex.stations.first { it.operator.contains("서울교통공사") && it.line == "1호선" }
        val korail1 = nationalIndex.stations.first {
            (it.operator.contains("코레일") || it.operator.contains("한국철도")) && it.line == "1호선"
        }
        assertEquals("1001", seoul1.seoulLine?.id)
        assertEquals("1001", korail1.seoulLine?.id)
        assertEquals(Color(0xFF0052A4), subwayLineColor(seoul1.displayLine))
    }

    @Test
    fun sameNameFarApart_staysSeparate() {
        // 양평: 5호선(서울 영등포구) ↔ 경의중앙선(경기 양평군)
        val yp = index.groups.filter { it.name == "양평" }
        assertTrue(yp.size >= 2)
    }

    @Test
    fun linesInViewport() {
        // 서울 도심 대략 범위
        val lines = index.seoulLinesIn(37.55, 126.96, 37.58, 127.01)
        assertTrue(lines.containsAll(listOf("1001", "1002", "1004")))
    }

    @Test
    fun seoulNoData_isEmpty() {
        assertEquals(0, SeoulXml.parse(fixture("seoul_nodata.xml")).size)
    }

    @Test
    fun seoulError_throws() {
        try {
            SeoulXml.parse(fixture("seoul_keyerror.xml"))
            throw AssertionError("should throw")
        } catch (e: TagoApiException) {
            assertEquals("ERROR-337", e.code)
        }
    }

    @Test
    fun tagoLineNames_matchDisplayLines() {
        assertTrue(StationName.sameLine("경의중앙", "경의중앙선"))
        assertTrue(StationName.sameLine("공항", "공항철도"))
        assertTrue(StationName.sameLine("인천국제공항선", "공항철도"))
        assertTrue(StationName.sameLine("1호선", "1호선"))
        assertTrue(StationName.sameLine("수인분당", "수인분당선"))
        assertTrue(!StationName.sameLine("1호선", "4호선"))
        assertTrue(!StationName.sameLine("2호선", "경의중앙선"))
    }

    /** TagoTransit 에서 브라우저로 서울이 뜨는 걸 확인한 URL 과 같은 규칙인지 */

    // ── 원본 데이터 교정 확인 ──

    @Test
    fun ichon_isNotOnLine1_andIsOnGyeongui() {
        assertTrue("이촌이 1호선으로 잡히면 안 됨", index.locate("1001", "이촌") == null)
        assertTrue(index.locate("1063", "이촌") != null)
        assertTrue(index.locate("1004", "이촌") != null)
        // 1호선 용산은 그대로 (경부선)
        assertTrue(index.locate("1001", "용산") != null)
    }

    @Test
    fun ichon4_isNotOnTopOfSinyongsan() {
        val ichon = index.locate("1004", "이촌")!!
        val sinyongsan = index.locate("1004", "신용산")!!
        assertTrue(Geo.meters(ichon.lat, ichon.lng, sinyongsan.lat, sinyongsan.lng) > 500)
        // 이촌 4호선과 경의중앙선은 한 마커로 묶임
        val g = index.groups.first { it.stations.contains(ichon) }
        assertTrue(g.displayLines.containsAll(listOf("4호선", "경의중앙선")))
    }

    @Test
    fun magok_isNotOnTopOfBalsan() {
        val m = index.locate("1005", "마곡")!!
        val b = index.locate("1005", "발산")!!
        assertTrue(Geo.meters(m.lat, m.lng, b.lat, b.lng) > 800)
    }

    @Test
    fun line1_pathSeoulToGwangwoon() {
        val path = index.pathToward("1001", index.locate("1001", "서울")!!, index.locate("1001", "광운대"))
        val names = path.map { it.norm }
        assertEquals("서울", names.first())
        assertEquals("광운대", names.last())
        assertTrue(names.containsAll(listOf("시청", "종각", "청량리", "회기", "석계")))
        assertTrue("경의중앙 구간(이촌 등)이 섞이면 안 됨", "이촌" !in names && "옥수" !in names)
    }
}
