package com.eunho.tagohub

import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.PlaceGroups
import com.eunho.tagohub.data.TrainStops
import com.eunho.tagohub.data.TrainTransfers
import com.eunho.tagohub.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 기차 탭: 환승 짝짓기, 열차별 정차역 찾기, 역 목록 묶기 */
class TrainExtrasTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 23, h, m)

    private fun trip(no: String, dep: LocalDateTime, arr: LocalDateTime, fare: Int? = 1000, from: String = "A", to: String = "B") =
        Trip("KTX", no, from, to, dep, arr, if (fare == null) emptyList() else listOf("어른" to fare), depId = from, arrId = to)

    // ── 환승 ──

    @Test
    fun transferKeepsTheMinimumWaitAndTakesTheEarliestArrival() {
        val first = listOf(trip("1", at(8, 0), at(9, 0)))
        val second = listOf(
            trip("2", at(9, 5), at(10, 0)),    // 5분 뒤 → 못 탐
            trip("3", at(9, 10), at(11, 0)),   // 10분 뒤 → 탐
            trip("4", at(9, 30), at(10, 30)),  // 더 늦게 출발해도 더 빨리 도착
        )
        val r = TrainTransfers.combine(first, second, "대전")
        assertEquals(1, r.size)
        assertEquals("4", r[0].legs[1].number)
        assertEquals(30, r[0].waitMinutes)
        assertEquals(2000, r[0].fare)
        assertEquals(at(8, 0), r[0].dep)
        assertEquals(at(10, 30), r[0].arr)
    }

    @Test
    fun noConnectionMeansNoTransfer() {
        assertTrue(TrainTransfers.combine(listOf(trip("1", at(8, 0), at(12, 0))), listOf(trip("2", at(9, 0), at(10, 0))), "대전").isEmpty())
    }

    @Test
    fun fareIsUnknownIfEitherTrainHasNoPrice() {
        val r = TrainTransfers.combine(listOf(trip("1", at(8, 0), at(9, 0), fare = null)), listOf(trip("2", at(10, 0), at(11, 0))), "대전")
        assertNull(r.single().fare)
    }

    @Test
    fun selectKeepsOnePerFirstTrainAndDropsMuchLaterArrivals() {
        val a = trip("1", at(8, 0), at(9, 0))
        val viaSeoul = TrainTransfers.combine(listOf(a), listOf(trip("2", at(10, 0), at(12, 0))), "서울")
        val viaDaejeon = TrainTransfers.combine(listOf(a), listOf(trip("3", at(9, 30), at(11, 0))), "대전")      // 더 빨리 도착
        val tooLate = TrainTransfers.combine(listOf(trip("9", at(15, 0), at(16, 0))), listOf(trip("8", at(17, 0), at(19, 30))), "익산") // 가장 빠른 11:00 보다 2시간 넘게 늦음
        val r = TrainTransfers.select(viaSeoul + viaDaejeon + tooLate)
        assertEquals(1, r.size)
        assertEquals("대전", r[0].hub)
    }

    @Test
    fun selectSpreadsAcrossTheDayWhenThereAreTooMany() {
        val all = (0 until 40).flatMap { i -> TrainTransfers.combine(listOf(trip("a$i", at(6, 0).plusMinutes(i * 10L), at(7, 0).plusMinutes(i * 10L))), listOf(trip("b$i", at(7, 30).plusMinutes(i * 10L), at(9, 0).plusMinutes(i * 10L))), "서울") }
        val r = TrainTransfers.select(all, limit = 10)
        assertEquals(10, r.size)
        assertTrue(r.zipWithNext().all { (x, y) -> !x.dep!!.isAfter(y.dep!!) })
    }

    // ── 정차역 ──

    @Test
    fun stopsAreTheStationsTheSameTrainReachesBeforeTheDestinationInTimeOrder() {
        val t = trip("101", at(8, 0), at(10, 0), from = "서울", to = "부산")
        val scan = mapOf(
            "101" to listOf(at(9, 30) to "동대구", at(8, 45) to "대전", at(10, 0) to "부산", at(11, 0) to "마산", at(8, 45) to "대전"),
            "202" to listOf(at(8, 50) to "수원"),
        )
        val r = TrainStops.between(scan, t)
        assertEquals(listOf("서울", "대전", "동대구", "부산"), r.map { it.name })   // 부산 이후(마산)·다른 열차(수원)·중복 제외
        assertEquals(at(8, 45), r[1].time)
    }

    @Test
    fun unknownTrainStillShowsDepartureAndArrival() {
        val r = TrainStops.between(emptyMap(), trip("5", at(8, 0), at(10, 0), from = "서울", to = "부산"))
        assertEquals(listOf("서울", "부산"), r.map { it.name })
    }

    // ── 역 목록 묶기 ──

    @Test
    fun initialsMergeDoubleConsonantsAndPutNonHangulLast() {
        assertEquals('ㄱ', PlaceGroups.initial("가남"))
        assertEquals('ㄱ', PlaceGroups.initial("까치산"))
        assertEquals('ㅅ', PlaceGroups.initial("쌍용"))
        assertEquals('ㅎ', PlaceGroups.initial("홍성"))
        assertEquals('#', PlaceGroups.initial("A역"))
        val g = PlaceGroups.byInitial(listOf(Place("1", "A역"), Place("2", "서울"), Place("3", "가남"), Place("4", "광명")))
        assertEquals(listOf("ㄱ", "ㅅ", "#"), g.map { it.first })
        assertEquals(listOf("가남", "광명"), g[0].second.map { it.name })
    }

    @Test
    fun chungcheongGyeongsangAndJeollaAreSplitIntoNorthAndSouthShortNames() {
        listOf(
            "충청북도" to "충북", "충청남도" to "충남", "경상북도" to "경북", "경상남도" to "경남",
            "전북특별자치도" to "전북", "전라북도" to "전북", "전라남도" to "전남",
            "서울특별시" to "서울특별시", "경기도" to "경기도",
        ).forEach { (long, short) -> assertEquals(long, short, PlaceGroups.shortRegion(long)) }
        val g = PlaceGroups.byRegion(listOf(Place("1", "청주", "충청북도"), Place("2", "천안", "충청남도"), Place("3", "대구", "경상북도"), Place("4", "부산", "경상남도"), Place("5", "전주", "전북특별자치도"), Place("6", "목포", "전라남도")))
        assertEquals(listOf("충북", "충남", "전북", "전남", "경북", "경남"), g.map { it.first })
    }

    @Test
    fun regionsComeInTheUsualOrderAndUnknownLast() {
        val places = listOf(
            Place("1", "대전", "대전광역시"), Place("2", "서울", "서울특별시"), Place("3", "수원", "경기도"),
            Place("4", "전주", "전북특별자치도"), Place("5", "광주송정", "광주광역시"), Place("6", "어딘가", null), Place("7", "강릉", "강원특별자치도"),
        )
        assertEquals(
            listOf("서울특별시", "광주광역시", "대전광역시", "경기도", "강원특별자치도", "전북", "기타"),
            PlaceGroups.byRegion(places).map { it.first },
        )
    }
}
