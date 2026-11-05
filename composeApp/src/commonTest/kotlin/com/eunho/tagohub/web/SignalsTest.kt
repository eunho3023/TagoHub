package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalsTest {
    @Test fun seoulPhases() {
        val body = """[{"itstId":"1","trsmUtcTime":"10","ntStsgStatNm":"stop-and-remain","ntStsgRmdrCs":"200"},
                       {"itstId":"1","trsmUtcTime":"20","ntStsgStatNm":"protected-movement-allowed","ntStsgRmdrCs":"153","etPdsgStatNm":"stop-and-remain","etPdsgRmdrCs":"36001"}]"""
        val l = SignalsRepo.parseSeoul(body)
        assertEquals(listOf("nt" to "St", "et" to "Pd"), l.map { it.direction to it.kind })
        assertEquals("green", l[0].color)
        assertEquals(15300, l[0].remainMs)       // 1/10초 → 밀리초
        assertNull(l[1].remainMs)                // 카운트다운 없는 신호
    }

    @Test fun ulsanPhasesAndSpots() {
        val body = """{"body":{"items":{"item":[{"crsrdId":"A1","crsrdNm":"시청","mapCtptIntLat":"35.5","mapCtptIntLot":"129.3","stLtsgSttsNm":"permissive-clearance","stLtsgRmndCs":"4000"}]}}}"""
        assertEquals("yellow", SignalsRepo.parseUlsan(body)["A1"]!![0].color)
        assertEquals(4000, SignalsRepo.parseUlsan(body)["A1"]!![0].remainMs)
        assertEquals("ulsan:A1", SignalsRepo.parseUlsanSpots(body)[0].id)
        assertEquals("seoul:5", SignalsRepo.parseSeoulSpots("5\t석관중\t37.6\t127.06\nbad")[0].id)
    }
}
