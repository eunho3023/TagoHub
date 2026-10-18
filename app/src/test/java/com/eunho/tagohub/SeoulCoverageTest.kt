package com.eunho.tagohub

import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.data.SeoulMappers
import com.eunho.tagohub.data.SubwayText
import com.eunho.tagohub.core.SeoulXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서울 실시간 API 가 아는 역(실시간도착_역정보 20260902, 696역)을 앱 역 데이터에서 모두 찾을 수 있는지 */
class SeoulCoverageTest {
    private val index = StationIndex.parseTsv(javaClass.getResource("/fixtures/subway_stations_asset.tsv")!!.readText(Charsets.UTF_8))
    private val realtime = javaClass.getResource("/fixtures/seoul_realtime_stations.tsv")!!.readText(Charsets.UTF_8)
        .lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }

    @Test
    fun everyRealtimeStation_isLocated() {
        val missing = realtime.filter { (line, _, name) -> index.locate(line, name) == null }.map { "${it[0]}:${it[2]}" }
        println("실시간 역 ${realtime.size}곳 중 못 찾음 ${missing.size}: $missing")
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun nationalStationCoordinatesStayWithinKorea() {
        assertTrue(index.stations.all { it.lat in 33.0..39.0 && it.lng in 124.0..132.0 })
    }

    /** 실제 응답(서울역, 2026-10-12 07:02): 도착 예정 초가 모두 0 → 메시지로 */
    @Test
    fun seoulStationArrival_realResponse() {
        val rows = SeoulXml.parse(javaClass.getResource("/fixtures/seoul_station_arrival.xml")!!.readText(Charsets.UTF_8))
        val a = rows.map(SeoulMappers::arrival)
        assertEquals(5, a.size)
        assertEquals("상행", a[0].upDown)
        assertEquals(true, a[1].express)                       // 급행
        assertEquals(listOf("출발", "출발", "도착", "도착", "4번째 전역"), a.map(SubwayText::main))
        assertEquals("효창공원앞", a[4].current)
        // 응답의 역(서울)은 노선마다 앱 역 데이터에서 찾아짐 (공항철도·4호선·1호선·경의중앙선)
        a.forEach { x -> assertEquals("서울", index.locate(x.lineId, "서울")?.norm) }
    }
}
