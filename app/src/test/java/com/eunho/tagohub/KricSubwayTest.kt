package com.eunho.tagohub

import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.data.KricSubwayApi
import com.eunho.tagohub.data.KricSubwayCodes
import com.eunho.tagohub.data.KricSubwayRepository
import com.eunho.tagohub.data.KricSubwayRegion
import com.eunho.tagohub.data.KricStationCodeIndex
import com.eunho.tagohub.data.Mappers
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationGroup
import com.eunho.tagohub.data.SubwayStation
import com.eunho.tagohub.ui.subway.DayType
import com.eunho.tagohub.ui.subway.matchTagoStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class KricSubwayTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8)

    @Test
    fun stationIdSplitsIntoKricRequestCodes() {
        assertEquals(
            KricSubwayCodes("KR", "K4", "P313"),
            KricSubwayCodes.candidates("MTRKRK4P313").first { it.lineCode == "K4" },
        )
        assertEquals(
            KricSubwayCodes("S1", "1", "150"),
            KricSubwayCodes.candidates("MTRS11150").first(),
        )
        assertEquals(emptyList<KricSubwayCodes>(), KricSubwayCodes.candidates("unknown"))
        assertNull(KricSubwayRegion.fromOperator("미등록 운영기관"))
    }

    @Test
    fun routeLookupResolvesVariableLengthCodesFromKricRouteData() = runBlocking {
        val api = object : KricSubwayApi {
            val requestedLines = mutableListOf<String>()

            override suspend fun routeInfo(regionCode: String, lineCode: String): String {
                requestedLines += lineCode
                return if (lineCode == "K4") fixture("kric_korail_route.xml") else
                    "<ROOT><header><resultCode>03</resultCode><resultMsg>NODATA</resultMsg></header><body></body></ROOT>"
            }

            override suspend fun timetable(
                operatorCode: String,
                dayCode: String,
                lineCode: String,
                stationCode: String,
            ) = fixture("kric_subway_timetable.xml")
        }
        val repository = KricSubwayRepository(api, "test-key")
        val result = repository.routeForStation(
            SubwayStation("MTRKRK4P313", "서울역", "경의중앙"),
            "01",
        )

        assertEquals(
            com.eunho.tagohub.core.TagoResult.Success(
                com.eunho.tagohub.data.KricStationRoute(
                    KricSubwayCodes("KR", "K4", "P313"),
                    TagoXml.parse(fixture("kric_korail_route.xml")).rows.map(Mappers::kricRouteStation),
                )
            ),
            result,
        )
        assertEquals(listOf("K", "K4"), api.requestedLines)
    }

    @Test
    fun stationCodeIndexResolvesTransfersByStationLineAndOperator() {
        val index = KricStationCodeIndex.parseTsv(fixture("kric_station_codes.tsv"))

        assertEquals(
            KricSubwayCodes("KR", "K4", "P313"),
            index.find("서울역", listOf("경의중앙선"), listOf("한국철도공사"), "01"),
        )
        assertEquals(
            KricSubwayCodes("S1", "1", "150"),
            index.find("서울역", listOf("1호선"), listOf("서울교통공사"), "01"),
        )
        assertEquals(
            KricSubwayCodes("S1", "2", "222"),
            index.find("강남역", listOf("서울 2호선"), listOf("서울교통공사"), "01"),
        )
        assertEquals(
            KricSubwayCodes("KR", "K4", "P313"),
            index.find("서울역", listOf("경의중앙선"), listOf("코레일"), "01"),
        )
        assertNull(index.find("서울역", listOf("경의중앙선"), listOf("한국철도공사"), "02"))
        assertNull(index.find("서울역", emptyList(), emptyList(), "01"))
    }

    @Test
    fun tagoStationMatchingDoesNotSelectSeoulCityHallForDaejeon() {
        val group = StationGroup(
            id = "daejeon-city-hall",
            name = "시청",
            lat = 36.350764,
            lng = 127.386517,
            stations = listOf(
                MetroStation("0111", "시청역", "1호선", 36.350764, 127.386517, "대전교통공사")
            ),
        )
        val index = KricStationCodeIndex.parseTsv(
            """
                operatorCode	operatorName	lineCode	lineName	stationCode	stationName
                S1	서울교통공사	1	1호선	151	시청
                S1	서울교통공사	2	2호선	201	시청
                DJ	대전교통공사	1	1호선	0011	시청
            """.trimIndent()
        )
        val candidates = listOf(
            SubwayStation("MTRS11151", "시청", "1호선"),
            SubwayStation("MTRS12201", "시청", "2호선"),
            SubwayStation("MTRDJ10011", "시청", "1호선"),
        )

        val matched = matchTagoStations(group, candidates) { metro ->
            index.find(
                stationName = metro.name,
                lineNames = listOf(metro.line, metro.displayLine),
                operatorNames = listOf(metro.operator),
                regionCode = KricSubwayRegion.fromOperator(metro.operator)!!,
            )
        }

        assertEquals(listOf("MTRDJ10011"), matched.map { it.id })
    }

    @Test
    fun routeLookupUsesProvidedOfficialStationCodes() = runBlocking {
        val requested = mutableListOf<String>()
        val api = object : KricSubwayApi {
            override suspend fun routeInfo(regionCode: String, lineCode: String): String {
                requested += lineCode
                return fixture("kric_subway_route.xml")
            }

            override suspend fun timetable(
                operatorCode: String,
                dayCode: String,
                lineCode: String,
                stationCode: String,
            ) = fixture("kric_subway_timetable.xml")
        }
        val result = KricSubwayRepository(api, "test-key").routeForStation(
            SubwayStation("unmapped", "서울역", "공항철도"),
            "01",
            KricSubwayCodes("AR", "A1", "A01"),
        )

        assertEquals(listOf("A1"), requested)
        assertEquals(
            KricSubwayCodes("AR", "A1", "A01"),
            (result as com.eunho.tagohub.core.TagoResult.Success).data.codes,
        )
    }

    @Test
    fun routeInfoMapsAndSortsStationsInTravelOrder() {
        val rows = TagoXml.parse(fixture("kric_subway_route.xml")).rows
            .map(Mappers::kricRouteStation)
            .sortedBy { it.stationOrder }

        assertEquals(listOf("서울역", "공덕"), rows.map { it.stationName })
        assertEquals(listOf("A01", "A02"), rows.map { it.stationCode })
        assertEquals("AR", rows.first().operatorCode)
    }

    @Test
    fun timetableDropsInvalidRowsDeduplicatesAndSortsByDeparture() {
        val rows = Mappers.cleanKricTimetable(TagoXml.parse(fixture("kric_subway_timetable.xml")).rows)

        assertEquals(2, rows.size)
        assertEquals(listOf("A101", "A102"), rows.map { it.trainNo })
        assertEquals(listOf("052030", "054959"), rows.map { it.departureTime })
        assertEquals(listOf("8", "7", "9"), DayType.entries.map { it.kricCode })
        assertEquals("01", KricSubwayRegion.fromOperator("서울교통공사"))
        assertEquals("01", KricSubwayRegion.fromOperator("코레일"))
        assertEquals("02", KricSubwayRegion.fromOperator("부산광역시 부산교통공사"))
        assertEquals(null, KricSubwayRegion.fromOperator("미등록 운영기관"))
    }
}
