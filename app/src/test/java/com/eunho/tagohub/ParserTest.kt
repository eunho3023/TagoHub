package com.eunho.tagohub

import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.data.Mappers
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.PlaceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** PDF 로 받은 실제 응답을 픽스처로 사용 */
class ParserTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8)

    @Test
    fun subwaySchedule_dropsZeroTimes_dedupes_sorts() {
        val page = TagoXml.parse(fixture("subway_schedule.xml"))
        assertEquals(624, page.totalCount)
        val list = Mappers.cleanSchedule(page.rows)
        // 000000 2건 제거, 052000 중복 제거 → 05:20, 05:40, 05:49 세 건
        assertEquals(3, list.size)
        assertEquals(listOf("05:20", "05:40", "05:49"), list.map { TagoTime.clockText(it.serviceMinute % 1440) })
        assertEquals("인천공항2터미널", list.first().endStationName)
    }

    @Test
    fun trainTrip_zeroChargeBecomesNoPrice() {
        val trips = TagoXml.parse(fixture("train_trips.xml")).rows.map(Mappers::trainTrip)
        assertEquals("1151", trips[0].number)
        assertEquals(LocalDateTime.of(2026, 10, 9, 6, 37), trips[0].dep)
        assertEquals("1시간 14분", Fmt.duration(trips[0].dep, trips[0].arr))
        assertEquals(listOf("어른" to 9300), trips[0].prices)
        assertTrue(trips[1].prices.isEmpty())
    }

    @Test
    fun expArrival_parsesRemainAndSorts() {
        val list = TagoXml.parse(fixture("exp_arrival.xml")).rows.map(Mappers::expArrival)
            .sortedWith(Mappers.expArrivalOrder)
        assertEquals(listOf(16, 70, null), list.map { it.remainMinutes })
        assertTrue(list.last().arrived)
        assertEquals(LocalDateTime.of(2026, 10, 9, 17, 15), list.first().arrPredict)
        assertEquals(12 * 60 + 50, list.first().depMinute)
    }

    @Test
    fun flight_12digitTime_and_vihicleIdTypo() {
        val f = Mappers.flight(TagoXml.parse(fixture("flights.xml")).rows.single())
        assertEquals("OZ8141", f.number)
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 0), f.dep)
        assertEquals(listOf("일반석" to 49800), f.prices)
    }

    @Test
    fun suburb_14digitTime() {
        val t = Mappers.coachTrip(TagoXml.parse(fixture("suburb_trips.xml")).rows.single())
        assertEquals(LocalDateTime.of(2026, 10, 9, 14, 31), t.arr)
        assertEquals("2시간 41분", Fmt.duration(t.dep, t.arr))
    }

    @Test
    fun codeLists_withoutTotalCount_and_alphaIds() {
        val page = TagoXml.parse(fixture("train_kinds.xml"))
        assertEquals(3, page.totalCount)
        assertEquals("0A", Mappers.trainKind(page.rows[1]).id)
    }

    @Test
    fun subwayStation_nameFieldVariant() {
        val s = Mappers.subwayStation(TagoXml.parse(fixture("subway_search.xml")).rows.single())
        assertEquals("서울역", s.name)
        assertEquals("GTX-A", s.routeName)
        assertEquals("", s.routeId)
    }

    @Test
    fun exits_mergedAndNaturallySorted() {
        val exits = Mappers.exits(
            TagoXml.parse(fixture("exit_bus.xml")).rows,
            TagoXml.parse(fixture("exit_facility.xml")).rows,
        )
        assertEquals(listOf("1", "2", "10"), exits.map { it.exitNo })
        assertEquals(listOf("461", "5413", "8541(맞춤버스)"), exits[0].buses)
        assertEquals(listOf("낙성대지구대"), exits[0].facilities)
    }

    @Test
    fun busRouteInfo_times() {
        val r = Mappers.busRoute(TagoXml.parse(fixture("bus_route_info.xml")).rows.single())
        assertEquals("06:00", r.firstTime)
        assertEquals("22:35", r.lastTime)
        assertEquals(40, r.intervalSun)
    }

    @Test
    fun gatewayError_isFatal() {
        try {
            TagoXml.parse(fixture("gateway_error.xml"))
            throw AssertionError("should throw")
        } catch (e: TagoApiException) {
            assertEquals("30", e.code)
            assertTrue(e.isFatal)
        }
    }

    @Test
    fun noData_isEmptyNotError() {
        val page = TagoXml.parse(fixture("nodata.xml"))
        assertEquals(0, page.rows.size)
    }

    @Test
    fun misc() {
        assertEquals("6분 43초", Fmt.arrival(403))
        assertEquals("45초", Fmt.arrival(45))
        assertNull(TagoTime.remainMinutes("도착완료"))
        assertEquals(25 * 60 + 30, TagoTime.minutesOfDay("2530"))
        val places = listOf(Place("1", "센트럴시티(서울)"), Place("2", "서울경부"), Place("3", "동서울"))
        assertEquals(listOf("서울경부", "동서울", "센트럴시티(서울)"), PlaceFilter.filter(places, "서울").map { it.name })
    }

    // ── JSON 폴백 ──

    @Test
    fun json_trainTrips_numbersAsRawText() {
        val page = TagoXml.parse(fixture("train_trips.json"))
        assertEquals(13, page.totalCount)
        val trips = page.rows.map(Mappers::trainTrip)
        assertEquals("1151", trips[0].number)
        assertEquals(LocalDateTime.of(2026, 10, 9, 6, 37), trips[0].dep)
        assertTrue(trips[1].prices.isEmpty())
    }

    @Test
    fun json_singleItemObject() {
        val page = TagoXml.parse(fixture("subway_search_single.json"))
        assertEquals("서울역", Mappers.subwayStation(page.rows.single()).name)
    }

    @Test
    fun json_emptyItemsString() {
        assertEquals(0, TagoXml.parse(fixture("empty.json")).rows.size)
    }

    @Test
    fun json_intTimeWithoutLeadingZero() {
        val r = Mappers.busRoute(TagoXml.parse(fixture("bus_route_info.json")).rows.single())
        assertEquals("06:00", r.firstTime)
        assertEquals("5", r.routeNo)
    }

    @Test
    fun json_errorCode() {
        try {
            TagoXml.parse(fixture("error.json"))
            throw AssertionError("should throw")
        } catch (e: TagoApiException) {
            assertEquals("10", e.code)
        }
    }
}
