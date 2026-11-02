package com.eunho.tagohub

import com.eunho.tagohub.data.CrashLog
import com.eunho.tagohub.data.DelayNote
import com.eunho.tagohub.data.RailFacilities
import com.eunho.tagohub.data.RailFacilities.Facility
import com.eunho.tagohub.data.RunHistory
import com.eunho.tagohub.data.RunStop
import com.eunho.tagohub.data.TrainTransfers
import com.eunho.tagohub.data.Trip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class RailFacilitiesAndCrashLogTest {
    @After
    fun reset() = RailFacilities.set(emptyMap())

    // ── 역 편의시설 ──

    @Test
    fun parsesFacilityLinesAndSummarisesOnlyWhatExists() {
        val map = RailFacilities.parse("서울\t17\t18\t1\tY\n가야\t0\t0\t0\tN\n깨진줄\n울산(통도사)\t2\t0\t0\tN\n")
        RailFacilities.set(map)
        assertEquals("엘리베이터 17 · 에스컬레이터 18 · 휠체어리프트 1 · 장애인경사로", RailFacilities.summary("서울"))
        assertNull(RailFacilities.summary("가야"))                 // 시설이 하나도 없으면 보여줄 것 없음
        assertNull(RailFacilities.summary("자료에없는역"))
        assertEquals("엘리베이터 2", RailFacilities.summary("울산"))   // 이름은 괄호·공백을 무시하고 맞춤
    }

    @Test
    fun theBundledFacilitiesFileHasTheBigStations() {
        val f = listOf("src/main/assets/rail_facilities.tsv", "app/src/main/assets/rail_facilities.tsv").map { File(it) }.first { it.exists() }
        RailFacilities.set(RailFacilities.parse(f.readText(Charsets.UTF_8)))
        assertEquals(Facility(10, 28, 0, false), RailFacilities.of("대전"))
        assertTrue(RailFacilities.summary("서울")!!.startsWith("엘리베이터"))
    }

    // ── 오류 기록 ──

    @Test
    fun redactRemovesServiceKeysAndAuthorizationValues() {
        val text = "java.io.IOException: https://apis.data.go.kr/x?serviceKey=AbC123%2Bxyz%3D&pageNo=1 및 Authorization: KakaoAK 0123456789abcdef"
        val out = CrashLog.redact(text)
        assertFalse(out.contains("AbC123"))
        assertFalse(out.contains("0123456789abcdef"))
        assertTrue(out.contains("pageNo=1"))                        // 다른 값은 남김
    }

    @Test
    fun formatKeepsTheStackAndCapsTheSize() {
        val error = IllegalStateException("앱이 멈춤 apiKey=SECRET99")
        val out = CrashLog.format("1.0", "main", error, 0L)
        assertTrue(out.contains("IllegalStateException") && out.contains("앱 버전: 1.0") && out.contains("스레드: main"))
        assertFalse(out.contains("SECRET99"))
        assertTrue(out.length <= 8000)
    }

    @Test
    fun crashReportGetsAKeylessCopyButKeepsTheStack() {
        val cause = java.io.IOException("https://apis.data.go.kr/x?serviceKey=SECRET77&pageNo=1")
        val error = IllegalStateException("불러오기 실패", cause)
        val out = CrashLog.scrubbed(error)
        val all = generateSequence(out) { it.cause }.joinToString("\n") { it.message.orEmpty() }
        assertFalse(all.contains("SECRET77"))
        assertTrue(all.contains("java.io.IOException") && all.contains("pageNo=1"))   // 원래 종류와 다른 값은 남김
        assertEquals(error.stackTrace.toList(), out.stackTrace.toList())
        assertEquals(cause.stackTrace.toList(), out.cause!!.stackTrace.toList())
    }

    @Test
    fun crashWithoutKeysIsPassedAsIs() {
        val error = NullPointerException("값이 없음")
        assertTrue(CrashLog.scrubbed(error) === error)
    }

    // ── 지난주 도착 지연 요약 ──

    private fun trip(no: String, arrH: Int, arrM: Int) =
        Trip("KTX", no, "서울", "부산", LocalDateTime.of(2026, 10, 23, 5, 0), LocalDateTime.of(2026, 10, 23, arrH, arrM), emptyList())

    @Test
    fun delayNotesAveragePerTrainFromTheArrivalStationRows() {
        fun row(day: Int, no: String, station: String, min: Int) = RunStop(LocalDate.of(2026, 10, day), station, min, null, no)
        val rows = listOf(
            row(20, "00001", "부산", 7 * 60 + 53), row(21, "00001", "부산", 7 * 60 + 50), row(22, "00001", "부산", 7 * 60 + 56),   // 계획 07:50 → +3, 0, +6
            row(22, "00003", "부산", 8 * 60 + 30),                                                                                    // 하루뿐 → 요약 안 만듦
            row(21, "00001", "대전", 6 * 60),                                                                                        // 다른 역은 무시
        )
        val notes = RunHistory.delayNotes(rows, listOf(trip("1", 7, 50), trip("3", 8, 16), trip("5", 9, 0)))
        assertEquals(DelayNote(3, 6, 3), notes["1"])
        assertNull(notes["3"])
        assertNull(notes["5"])
    }

    // ── 환승 대기시간 ──

    @Test
    fun aLongerMinimumWaitDropsConnectionsThatAreTooTight() {
        val a = Trip("KTX", "1", "서울", "대전", LocalDateTime.of(2026, 10, 23, 8, 0), LocalDateTime.of(2026, 10, 23, 9, 0), emptyList())
        val b = Trip("KTX", "2", "대전", "부산", LocalDateTime.of(2026, 10, 23, 9, 12), LocalDateTime.of(2026, 10, 23, 10, 30), emptyList())
        assertEquals(1, TrainTransfers.combine(listOf(a), listOf(b), "대전", 10).size)     // 12분 → 10분 기준은 가능
        assertEquals(0, TrainTransfers.combine(listOf(a), listOf(b), "대전", 15).size)     // 15분 기준은 불가
    }
}
