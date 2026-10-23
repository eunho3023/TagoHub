package com.eunho.tagohub

import com.eunho.tagohub.data.KacDom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 한국공항공사 국내선 운항 스케줄 (김포→제주, 2026-10-23 실제 응답 모양) */
class KacDomTest {
    private val json = """{"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."}, "body": {"numOfRows": 3, "pageNo": 1, "totalCount": 3, "items": {"item": [
        {"airlinehomepageUrl": null, "airlineKorean": "에어서울", "airlineEnglish": "AIR SEOUL", "arrivalcity": "제주", "arrivalcityCode": "CJU",
         "domesticArrivalTime": "0715", "domesticEddate": "2026-10-28T00:00:00", "domesticNum": "RS6347", "domesticStartTime": "0600",
         "domesticStdate": "2026-10-25T00:00:00", "startcity": "서울/김포", "startcityCode": "GMP", "domesticSun": "Y", "domesticMon": "N",
         "domesticTue": "N", "domesticWed": "N", "domesticThu": "N", "domesticFri": "N", "domesticSat": "N", "flightPurpose": "여객기"},
        {"airlineKorean": "대한항공", "arrivalcity": "제주", "arrivalcityCode": "CJU", "domesticArrivalTime": "0030", "domesticEddate": "2027-03-27T00:00:00",
         "domesticNum": "KE1399", "domesticStartTime": "2330", "domesticStdate": "2026-03-29T00:00:00", "startcity": "서울/김포", "startcityCode": "GMP",
         "domesticSun": "Y", "domesticMon": "Y", "domesticTue": "Y", "domesticWed": "Y", "domesticThu": "Y", "domesticFri": "Y", "domesticSat": "Y", "flightPurpose": "여객기"},
        {"airlineKorean": "대한항공", "arrivalcity": "제주", "arrivalcityCode": "CJU", "domesticArrivalTime": "0900", "domesticEddate": "2027-03-27T00:00:00",
         "domesticNum": "KE9999", "domesticStartTime": "0800", "domesticStdate": "2026-03-29T00:00:00", "startcity": "서울/김포", "startcityCode": "GMP",
         "domesticSun": "Y", "domesticMon": "Y", "domesticTue": "Y", "domesticWed": "Y", "domesticThu": "Y", "domesticFri": "Y", "domesticSat": "Y", "flightPurpose": "화물기"}
    ]}}}}"""
    private val all = KacDom.parse(json)

    @Test
    fun parsesRows() {
        assertEquals(3, all.size)
        assertEquals(3, KacDom.totalCount(json))
        val f = all[0]
        assertEquals("RS6347", f.num)
        assertEquals("GMP", f.depCode); assertEquals("CJU", f.arrCode)
        assertEquals("06:00", f.dep.toString()); assertEquals("07:15", f.arr.toString())
        assertEquals(1, f.days.size)
    }

    @Test
    fun forDate_periodWeekdayAndPassengerOnly() {
        // 2026-10-25 는 일요일: 에어서울(일요일만, 10/25~10/28)과 매일 대한항공 여객편. 화물기는 뺌
        val sun = KacDom.forDate(all, LocalDate.of(2026, 10, 25))
        assertEquals(listOf("RS6347", "KE1399"), sun.map { it.num })
        // 2026-10-26 월요일: 에어서울은 요일이 아니고 대한항공만
        assertEquals(listOf("KE1399"), KacDom.forDate(all, LocalDate.of(2026, 10, 26)).map { it.num })
        // 에어서울 기간(10/28) 이후 일요일(11/1)에는 없음
        assertEquals(listOf("KE1399"), KacDom.forDate(all, LocalDate.of(2026, 11, 1)).map { it.num })
    }

    @Test
    fun toTrips_overnightArrivalIsNextDay() {
        val trips = KacDom.toTrips(KacDom.forDate(all, LocalDate.of(2026, 10, 26)), LocalDate.of(2026, 10, 26))
        assertEquals("2026-10-26T23:30", trips[0].dep.toString())
        assertEquals("2026-10-27T00:30", trips[0].arr.toString())
        assertEquals("서울/김포", trips[0].depName); assertEquals("제주", trips[0].arrName)
    }

    @Test
    fun airportNamesMapToCodes() {
        assertEquals("GMP", KacDom.codeOf("김포국제공항"))
        assertEquals("CJU", KacDom.codeOf("제주국제공항"))
        assertEquals("KPO", KacDom.codeOf("포항경주공항"))
        assertNull(KacDom.codeOf("나리타"))
    }
}
