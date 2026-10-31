package com.eunho.tagohub

import com.eunho.tagohub.data.SignalColor
import com.eunho.tagohub.data.UlsanSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 응답(2026-11) 모양 그대로 줄인 것 */
class UlsanSignalsTest {
    private val phaseJson = """{"header":{"resultCode":"K0"},"body":{"totalCount":2,"items":{"item":[
        {"crsrdId":"1","ntStsgSttsNm":"protected-Movement-Allowed","ntStsgRmndCs":"12000","ntLtsgSttsNm":"","ntLtsgRmndCs":"",
         "etLtsgSttsNm":"stop-And-Remain","etLtsgRmndCs":"30000","etStsgSttsNm":"stop-And-Remain","etStsgRmndCs":"30000",
         "stStsgSttsNm":"protected-clearance","stStsgRmndCs":"2000","wtPdsgSttsNm":"stop-Then-Processed","wtPdsgRmndCs":"",
         "nwBssgSttsNm":"stop-And-Remain","nwBssgRmndCs":"9000"},
        {"crsrdId":"2","neStsgSttsNm":"unavailable","neStsgRmndCs":""}]}}}"""

    private val mapJson = """{"header":{"resultCode":"K0"},"body":{"totalCount":2,"items":{"item":[
        {"crsrdId":"1","crsrdNm":"오지벌사거리","mapCtptIntLat":"35.5122390000","mapCtptIntLot":"129.4307520000"},
        {"crsrdId":"10","crsrdNm":"","mapCtptIntLat":"35.5382010000","mapCtptIntLot":"129.3314300000"},
        {"crsrdId":"11","crsrdNm":"좌표없음","mapCtptIntLat":"","mapCtptIntLot":""}]}}}"""

    @Test
    fun intersectionsKeepNameAndPositionAndSkipBrokenOnes() {
        val list = UlsanSignals.parseIntersections(mapJson)
        assertEquals(listOf("1", "10"), list.map { it.id })
        assertEquals("오지벌사거리", list[0].name)
        assertEquals("교차로", list[1].name)                    // 이름이 비면 기본 이름
        assertEquals(35.512239, list[0].lat, 1e-6)
    }

    @Test
    fun phasesAreOrderedByDirectionAndKindAndSkipEmptyOnes() {
        val p = UlsanSignals.parsePhases(phaseJson).getValue("1")
        // 북 직진 → 동 직진·좌회전(직진이 앞) → 남 직진 → 서 보행자 (빈 값과 뜻 모르는 Bs 종류는 뺌)
        assertEquals(listOf("nt St", "et St", "et Lt", "st St", "wt Pd"), p.map { "${it.direction} ${it.kind}" })
        assertEquals(SignalColor.Green, p[0].color)
        assertEquals(12000, p[0].remainingMs)
        assertEquals(SignalColor.Yellow, p[3].color)
        assertEquals(SignalColor.RedBlink, p[4].color)
        assertNull(p[4].remainingMs)
        assertEquals("북쪽 직진", "${p[0].directionLabel} ${p[0].kindLabel}")
    }

    @Test
    fun secondsRoundUpAndMissingOrZeroGivesNull() {
        assertEquals(12, UlsanSignals.seconds(12000))
        assertEquals(1, UlsanSignals.seconds(1))
        assertEquals(2, UlsanSignals.seconds(1001))
        assertNull(UlsanSignals.seconds(0))
        assertNull(UlsanSignals.seconds(null))
        assertNull(UlsanSignals.seconds(-500))
    }

    @Test
    fun statesBecomeColors() {
        assertEquals(SignalColor.Red, UlsanSignals.color("stop-And-Remain"))
        assertEquals(SignalColor.Green, UlsanSignals.color("permissive-Movement-Allowed"))
        assertEquals(SignalColor.YellowBlink, UlsanSignals.color("caution-Conflicting-Traffic"))
        assertEquals(SignalColor.Off, UlsanSignals.color("unavailable"))
        assertEquals(SignalColor.Off, UlsanSignals.color("뭔가 새 값"))
    }

    @Test
    fun brokenJsonGivesEmptyResults() {
        assertTrue(UlsanSignals.parsePhases("깨짐").isEmpty())
        assertTrue(UlsanSignals.parseIntersections("{").isEmpty())
    }
}
