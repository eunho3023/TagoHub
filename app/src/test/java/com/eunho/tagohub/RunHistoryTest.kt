package com.eunho.tagohub

import com.eunho.tagohub.data.RunHistory
import com.eunho.tagohub.data.RunStop
import com.eunho.tagohub.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 지난 7일 운행(지연): 운행정보 API 응답 읽기, 계획과 실제의 차 */
class RunHistoryTest {
    // 실제 응답 모양 (서울 05:13 → 부산 07:50 KTX 1번, 이틀치)
    private val body = """
    {"response":{"header":{"resultCode":"0","resultMsg":"정상"},"body":{"dataType":"JSON","numOfRows":500,"pageNo":1,"totalCount":4,
     "items":{"item":[
      {"run_ymd":"20261021","stn_cd":"3900023","stn_nm":"서울","stop_se_nm":"시발","trn_arvl_dt":null,"trn_dptre_dt":"2026-10-21 05:14:00.0","trn_no":"00001","trn_run_sn":"1"},
      {"run_ymd":"20261021","stn_cd":"3900114","stn_nm":"부산","stop_se_nm":"종착","trn_arvl_dt":"2026-10-21 07:53:00.0","trn_dptre_dt":null,"trn_no":"00001","trn_run_sn":"7"},
      {"run_ymd":"20261022","stn_cd":"3900023","stn_nm":"서울","stop_se_nm":"시발","trn_arvl_dt":null,"trn_dptre_dt":"2026-10-22 05:13:00.0","trn_no":"00001","trn_run_sn":"1"},
      {"run_ymd":"20261022","stn_cd":"3900114","stn_nm":"부산","stop_se_nm":"종착","trn_arvl_dt":"2026-10-22 07:49:00.0","trn_dptre_dt":null,"trn_no":"00001","trn_run_sn":"7"}
     ]}}}}
    """.trimIndent()

    private fun trip(depName: String, arrName: String, dep: LocalDateTime, arr: LocalDateTime) =
        Trip("KTX", "1", depName, arrName, dep, arr, emptyList())

    private val ktx1 = trip("서울", "부산", LocalDateTime.of(2026, 10, 23, 5, 13), LocalDateTime.of(2026, 10, 23, 7, 50))

    @Test
    fun parsesTheRealResponseShape() {
        val rows = RunHistory.parse(body)
        assertEquals(4, rows.size)
        assertEquals(RunStop(LocalDate.of(2026, 10, 21), "서울", null, 5 * 60 + 14), rows[0])
        assertEquals(RunStop(LocalDate.of(2026, 10, 21), "부산", 7 * 60 + 53, null), rows[1])
    }

    @Test
    fun emptyOrBrokenResponsesGiveNoRows() {
        assertTrue(RunHistory.parse("").isEmpty())
        assertTrue(RunHistory.parse("<html>").isEmpty())
        assertTrue(RunHistory.parse("""{"response":{"body":{"items":"","totalCount":0}}}""").isEmpty())
        assertTrue(RunHistory.parse("""{"response":{"body":{"items":{"item":[]}}}}""").isEmpty())
    }

    @Test
    fun delaysAreActualMinusPlannedPerDayAndStationInDateOrder() {
        val s = RunHistory.summarize(RunHistory.parse(body), ktx1)
        assertEquals(listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22)), s.days.map { it.day })
        assertEquals(1, s.days[0].departure)       // 05:14 - 05:13
        assertEquals(3, s.days[0].arrival)         // 07:53 - 07:50
        assertEquals(0, s.days[1].departure)
        assertEquals(-1, s.days[1].arrival)        // 07:49 - 07:50 (일찍 도착)
        assertEquals(1, s.average)                 // (3 + -1) / 2 = 1
        assertEquals(3, s.worst)
        assertEquals(0, s.lateDays)
    }

    @Test
    fun stationsAreMatchedByNormalizedNameAndMissingDaysAreSkipped() {
        val rows = listOf(
            RunStop(LocalDate.of(2026, 10, 20), "울산", 8 * 60 + 5, 8 * 60 + 7),
            RunStop(LocalDate.of(2026, 10, 21), "대전", 7 * 60, 7 * 60 + 2),   // 이 날은 두 역 모두 없음 → 빠짐
        )
        val t = trip("서울", "울산(통도사)", LocalDateTime.of(2026, 10, 23, 5, 13), LocalDateTime.of(2026, 10, 23, 8, 0))
        val s = RunHistory.summarize(rows, t)
        assertEquals(1, s.days.size)
        assertNull(s.days[0].departure)            // 출발역 기록 없음
        assertEquals(5, s.days[0].arrival)
        assertEquals(1, s.lateDays)                // 5분 이상 늦은 날
    }

    @Test
    fun delayWrapsAcrossMidnight() {
        // 계획 23:58, 실제 00:03 → +5분 (하루 차이로 -1435분이 되지 않게)
        assertEquals(5, RunHistory.delay(3, 23 * 60 + 58))
        // 계획 00:02, 실제 23:59(전날) → -3분
        assertEquals(-3, RunHistory.delay(23 * 60 + 59, 2))
        assertNull(RunHistory.delay(null, 100))
    }
}
