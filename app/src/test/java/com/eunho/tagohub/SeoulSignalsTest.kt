package com.eunho.tagohub

import com.eunho.tagohub.data.SeoulSignals
import com.eunho.tagohub.data.SignalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서울 T Data 명세서의 샘플 응답 모양 그대로 */
class SeoulSignalsTest {
    private val sample = """[{"dataId":"SPAT-CIB1132038600-1790554725-86949","trsmDy":28,"trsmUtcTime":1.790571447594E12,"trsmYear":"2026","trsmMt":"09",
        "itstId":"20386","eqmnId":"CIB1132038600","nePdsgRmdrCs":414.0,"seStsgRmdrCs":354.0,"nwStsgRmdrCs":354.0,
        "nePdsgStatNm":"stop-And-Remain","seStsgStatNm":"protected-Movement-Allowed","nwStsgStatNm":"protected-Movement-Allowed",
        "ntLtsgStatNm":null,"ntLtsgRmdrCs":null,"rgtrId":"v2x","regDt":"2026-09-28T04:57:27.000+00:00"}]"""

    @Test
    fun phasesUseTenthsOfSecondsAndAreOrderedByDirection() {
        val p = SeoulSignals.parsePhases(sample)
        // 북동 보행자 → 남동 직진 → 북서 직진 (방향 순서: 북·북동·동·남동·남·남서·서·북서)
        assertEquals(listOf("ne Pd", "se St", "nw St"), p.map { "${it.direction} ${it.kind}" })
        assertEquals(41400, p[0].remainingMs)                    // 414 × 1/10초 = 41.4초
        assertEquals(SignalColor.Red, p[0].color)
        assertEquals(SignalColor.Green, p[1].color)
        assertEquals("북동쪽 보행자", "${p[0].directionLabel} ${p[0].kindLabel}")
    }

    @Test
    fun emptyAndNullFieldsAreSkipped() {
        val p = SeoulSignals.parsePhases(sample)
        assertTrue(p.none { it.direction == "nt" })
    }

    @Test
    fun theNewestRowWinsWhenSeveralComeBack() {
        val two = """[{"trsmUtcTime":100.0,"ntStsgStatNm":"stop-And-Remain","ntStsgRmdrCs":10.0},
                       {"trsmUtcTime":200.0,"ntStsgStatNm":"protected-Movement-Allowed","ntStsgRmdrCs":50.0}]"""
        val p = SeoulSignals.parsePhases(two).single()
        assertEquals(SignalColor.Green, p.color)
        assertEquals(5000, p.remainingMs)
    }

    @Test
    fun wrappedOrSingleObjectResponsesAreAccepted() {
        assertEquals(1, SeoulSignals.parsePhases("""{"data":[{"itstId":"1","ntStsgStatNm":"stop-And-Remain","ntStsgRmdrCs":5.0}]}""").size)
        assertEquals(1, SeoulSignals.parsePhases("""{"itstId":"1","stStsgStatNm":"protected-clearance","stStsgRmdrCs":20.0}""").size)
    }

    @Test
    fun busAndBicycleSignalsGetNames() {
        val p = SeoulSignals.parsePhases("""[{"itstId":"1","wtBssgStatNm":"stop-And-Remain","wtBssgRmdrCs":9.0,"wtBcsgStatNm":"stop-And-Remain","wtBcsgRmdrCs":9.0}]""")
        assertEquals(listOf("버스", "자전거"), p.map { it.kindLabel })
    }

    @Test
    fun brokenOrEmptyResponsesGiveNothing() {
        assertTrue(SeoulSignals.parsePhases("깨짐").isEmpty())
        assertTrue(SeoulSignals.parsePhases("[]").isEmpty())
        assertTrue(SeoulSignals.parsePhases("""{"responseCode":403,"message":"관리자에게 문의 바랍니다."}""").isEmpty())
    }

    @Test
    fun intersectionTableGetsAPrefixAndSkipsBrokenLines() {
        val list = SeoulSignals.parseIntersections("1\t이촌역앞\t37.523197\t126.971325\n깨짐\n2\t\t37.6\t127.0\n3\t좌표없음\t\t\n")
        assertEquals(listOf("seoul:1", "seoul:2"), list.map { it.id })
        assertEquals("교차로", list[1].name)
        assertEquals("1", SeoulSignals.apiId(list[0].id))
        assertTrue(SeoulSignals.isSeoulId(list[0].id))
        assertTrue(!SeoulSignals.isSeoulId("123"))
    }
}
