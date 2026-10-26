package com.eunho.tagohub

import com.eunho.tagohub.data.AirSchedule
import com.eunho.tagohub.data.AirportInfo
import com.eunho.tagohub.data.SubwayCongestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AirAndCongestionTest {
    private val tsv = listOf(
        "D\tJJA\t7C101\tGMP\tCJU\t06:30\t07:45\t월화수목금토일\t2026-08-01\t2026-10-24",
        "D\tKAL\tKE1201\tGMP\tCJU\t23:50\t01:05\t금\t2026-10-01\t2026-10-24",
        "D\tKAL\tKE9\tCJU\tGMP\t08:00\t09:10\t월\t2026-03-29\t2026-06-30",
        "I\tAAR\tOZ1085\tGMP\tHND\t08:40\t10:45\t월화수목금토일\t2026-03-29\t2026-10-24",
    ).joinToString("\n")

    private fun load() = AirSchedule.set(
        AirSchedule.parse(tsv),
        mapOf("JJA" to "제주항공", "KAL" to "대한항공", "AAR" to "아시아나항공"),
        mapOf("GMP" to "김포", "CJU" to "제주", "HND" to "도쿄/하네다"),
    )

    @Test
    fun scheduleMatchesDateAndWeekday() {
        load()
        val fri = LocalDate.of(2026, 10, 23)
        val r = AirSchedule.trips("GMP", "CJU", fri)
        assertFalse(r.projected)
        assertEquals(listOf("7C101", "KE1201"), r.trips.map { it.number })
        assertEquals("제주항공", r.trips[0].kind)
        assertEquals("김포", r.trips[0].depName)
        // 자정을 넘기는 편은 다음 날 도착
        assertEquals(fri.plusDays(1), r.trips[1].arr!!.toLocalDate())
        // 토요일에는 금요일 편이 없다
        assertEquals(listOf("7C101"), AirSchedule.trips("GMP", "CJU", LocalDate.of(2026, 10, 24)).trips.map { it.number })
        // 방향이 반대인 편(제주→김포)은 이 기간엔 없음
        assertTrue(AirSchedule.trips("CJU", "GMP", fri).trips.isEmpty())
    }

    @Test
    fun datesAfterTheFileAreProjectedFromTheLastWeekAndLabelled() {
        load()
        val mon = LocalDate.of(2026, 10, 26)    // 파일의 마지막 날(10-24) 다음
        val r = AirSchedule.trips("GMP", "CJU", mon)
        assertTrue(r.projected)
        assertEquals(listOf("7C101"), r.trips.map { it.number })     // 금요일 전용 편은 월요일 패턴에 없음
        assertTrue(r.trips[0].kind.endsWith("예상"))
        // 두 시각이 모두 있는 국제선
        val intl = AirSchedule.trips("GMP", "HND", mon).trips.single()
        assertEquals(LocalTime.of(8, 40), intl.dep!!.toLocalTime())
        assertEquals(LocalTime.of(10, 45), intl.arr!!.toLocalTime())
        // 파일보다 한참 앞선 날짜(이미 끝난 구간)는 비어 있어야 함
        assertTrue(AirSchedule.trips("CJU", "GMP", LocalDate.of(2026, 10, 26)).trips.isEmpty())
    }

    @Test
    fun congestionSlotsFollowHalfHours() {
        assertEquals(0, SubwayCongestion.slotOf(LocalTime.of(5, 30)))
        assertEquals(0, SubwayCongestion.slotOf(LocalTime.of(5, 0)))     // 영업 전
        assertEquals(6, SubwayCongestion.slotOf(LocalTime.of(8, 45)))
        assertEquals(38, SubwayCongestion.slotOf(LocalTime.of(0, 40)))
        assertEquals("08:30", SubwayCongestion.slotLabel(6))
        assertEquals("00:30", SubwayCongestion.slotLabel(38))
        assertEquals("여유", SubwayCongestion.describe(30))
        assertEquals("매우 혼잡", SubwayCongestion.describe(160))
    }

    @Test
    fun congestionLookupNormalizesStationNames() {
        val values = IntArray(39) { 50 }
        SubwayCongestion.set(SubwayCongestion.parse("W\t1호선\t서울역\t상선\t" + values.joinToString(",") + "\nW\t1호선\t서울역\t하선\t" + values.joinToString(",")))
        assertEquals(2, SubwayCongestion.forStation(SubwayCongestion.Day.Weekday, "1호선", "서울").size)
        assertTrue(SubwayCongestion.forStation(SubwayCongestion.Day.Sunday, "1호선", "서울").isEmpty())
        assertEquals(listOf("1호선"), SubwayCongestion.linesOf("서울역", listOf("4호선", "1호선")))
    }

    @Test
    fun airportInfoParsesTheBundledShape() {
        val json = """{"airports":{"김포":{"code":"GMP","name":"김포국제공항","address":"서울 강서구","lat":37.55,"lng":126.79,"phone":"1661-2626",
            "facility":{"비고":"국토교통부"},"counters":{"국내":{"카운터":"89"}},"delay":{"국내":{"지연율(퍼센트)":"17"}},
            "lounges":[{"name":"라운지","place":"3F","hours":"09:00","phone":"-","services":"음료"}],
            "shops":[{"terminal":"국내선","floor":"1층","type":"식음료","name":"공차"}]},"제주":{"code":"CJU","name":"제주국제공항"}}}"""
        val list = AirportInfo.parse(json)
        assertEquals(listOf("김포", "제주"), list.map { it.short })
        assertEquals("89", list[0].counters["국내"]!!["카운터"])
        assertEquals("공차", list[0].shops.single().name)
        assertEquals("김포", AirportInfo.match(list, "김포국제공항")!!.short)
        assertEquals("제주", AirportInfo.match(list, "제주")!!.short)
    }
}
