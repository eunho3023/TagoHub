package com.eunho.tagohub

import com.eunho.tagohub.data.TrainTimetable
import com.eunho.tagohub.data.Trip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** 한국철도공사 열차시간표 파일: 읽기, 열차별 정차역, 환승역 후보 */
class TrainTimetableTest {
    private val text = """
        1	KTX	매일	서울@313;광명@332;대전@374;동대구@418;경주@437;울산@449;부산@470
        1	KTX	금토	서울@600;대전@660;부산@760
        2	KTX	매일	부산@309;울산@332;동대구@357;대전@406;서울@473
        1141	ITX-새마을	매일	동대구@250;경산@256;밀양@282
        1195	무궁화	매일	대전@302;영동@326;김천@345;동대구@388;밀양@417
        9999	무궁화	매일	부산@1430;울산@1480;포항@1530
        bad line
        7	KTX	매일	서울@1
    """.trimIndent()

    private fun load() = TrainTimetable.set(TrainTimetable.parse(text))

    @After
    fun reset() = TrainTimetable.set(emptyList())

    private fun trip(no: String, from: String, to: String, h: Int, m: Int) =
        Trip("KTX", no, from, to, LocalDateTime.of(2026, 10, 23, h, m), LocalDateTime.of(2026, 10, 23, 23, 0), emptyList())

    @Test
    fun parsesTrainsAndSkipsBrokenOrTooShortLines() {
        val trains = TrainTimetable.parse(text)
        assertEquals(6, trains.size)               // bad line, 정차역이 하나뿐인 7번 제외
        assertEquals(listOf("서울", "광명", "대전", "동대구", "경주", "울산", "부산"), trains[0].stops.map { it.first })
        assertEquals(313, trains[0].stops[0].second)
    }

    @Test
    fun stopsBetweenTwoStationsWithTimesShiftedToTheTripsOwnDeparture() {
        load()
        // 서울 05:13 출발 1번 열차: 서울 → 부산 전부
        val all = TrainTimetable.stopsFor(trip("1", "서울", "부산", 5, 13))!!
        assertEquals(listOf("서울", "광명", "대전", "동대구", "경주", "울산", "부산"), all.map { it.name })
        assertEquals(LocalDateTime.of(2026, 10, 23, 5, 13), all[0].time)
        assertEquals(LocalDateTime.of(2026, 10, 23, 7, 50), all.last().time)          // 470분 = 07:50
        // 중간 구간만: 대전 → 동대구
        val part = TrainTimetable.stopsFor(trip("1", "대전", "동대구", 6, 14))!!
        assertEquals(listOf("대전", "동대구"), part.map { it.name })
    }

    @Test
    fun sameNumberOnDifferentDaysPicksTheOneWhoseDepartureMatches() {
        load()
        // 같은 1번이라도 금토 편(서울 10:00)은 서울-대전-부산만 선다
        val r = TrainTimetable.stopsFor(trip("1", "서울", "부산", 10, 0))!!
        assertEquals(listOf("서울", "대전", "부산"), r.map { it.name })
    }

    @Test
    fun theClosestDepartureWinsAndATooDifferentOneIsNotTheSameTrain() {
        load()
        // 계획 05:14 도 실제 기록 05:13 과 같은 열차 (1분 차이)
        assertEquals(7, TrainTimetable.stopsFor(trip("1", "서울", "부산", 5, 14))!!.size)
        // 새벽 3시 출발로 조회한 1번은 다른 열차: 5:13 편과 2시간 넘게 차이 → 정보 없음
        assertNull(TrainTimetable.stopsFor(trip("1", "서울", "부산", 3, 0)))
    }

    @Test
    fun unknownTrainOrWrongDirectionOrOvernightTimes() {
        load()
        assertNull(TrainTimetable.stopsFor(trip("555", "서울", "부산", 5, 13)))      // 시간표에 없는 열차
        assertNull(TrainTimetable.stopsFor(trip("1", "부산", "서울", 5, 13)))        // 반대 방향
        // 자정을 넘는 열차 (분 ≥ 1440): 23:50 출발 → 다음날 00:40, 01:30
        val night = TrainTimetable.stopsFor(trip("9999", "부산", "포항", 23, 50))!!
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 40), night[1].time)
        assertEquals(LocalDateTime.of(2026, 10, 24, 1, 30), night[2].time)
    }

    @Test
    fun namesIgnoreParenthesesAndKnownSpellingDifferences() {
        assertEquals(TrainTimetable.norm("울산(통도사)"), TrainTimetable.norm("울산"))
        assertEquals(TrainTimetable.norm("여수엑스포"), TrainTimetable.norm("여수EXPO"))
        assertEquals(TrainTimetable.norm("평택지제"), TrainTimetable.norm("지제"))
        assertEquals("대 전".let(TrainTimetable::norm), "대전")
    }

    @Test
    fun hubCandidatesAreStationsAfterDepartureAndBeforeArrivalRankedByConnections() {
        load()
        // 서울 → 밀양: 서울 다음에 서는 역(대전·동대구…) 중 밀양 앞에 서는 역
        val hubs = TrainTimetable.hubsBetween("서울", "밀양")!!
        assertTrue("동대구" in hubs && "대전" in hubs)
        assertTrue("서울" !in hubs && "밀양" !in hubs)
        // 이어지는 역이 없으면 빈 목록, 시간표에 없는 역이면 null
        assertEquals(emptyList<String>(), TrainTimetable.hubsBetween("포항", "서울"))
        assertNull(TrainTimetable.hubsBetween("서울", "모르는역"))
    }

    @Test
    fun theBundledTimetableAssetParsesAndHasTheBusyStations() {
        val f = listOf("src/main/assets/train_timetable.tsv", "app/src/main/assets/train_timetable.tsv").map { File(it) }.first { it.exists() }
        val trains = TrainTimetable.parse(f.readText(Charsets.UTF_8))
        assertTrue(trains.size > 900)
        TrainTimetable.set(trains)
        assertTrue(TrainTimetable.knows("서울") && TrainTimetable.knows("동대구") && TrainTimetable.knows("목포"))
        // 서울 → 부산 KTX 1번 열차: 대전·동대구를 지남
        val stops = TrainTimetable.stopsFor(trip("1", "서울", "부산", 5, 13))!!.map { it.name }
        assertTrue("대전" in stops && "동대구" in stops && stops.last() == "부산")
        // 운행기록 API 로 채운 ITX-청춘(2001번): 용산 → 춘천, 시간표(xlsx)에는 없던 열차
        val itx = TrainTimetable.stopsFor(trip("2001", "용산", "춘천", 5, 38))!!.map { it.name }
        assertEquals(listOf("용산", "청량리", "평내호평", "가평", "남춘천", "춘천"), itx)
        // 강릉 → 목포는 서울을 거치는 환승 후보
        assertTrue("서울" in TrainTimetable.hubsBetween("강릉", "목포", 30)!!)
    }
}
