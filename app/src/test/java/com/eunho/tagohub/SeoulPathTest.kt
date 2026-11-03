package com.eunho.tagohub

import com.eunho.tagohub.data.SeoulPathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서울특별시 대중교통환승경로 조회 서비스 활용가이드(2021-11-16)의 응답 구조·예시값 */
class SeoulPathTest {
    private val sample = """<ServiceResult><comMsgHeader/><msgHeader><headerCd>0</headerCd><headerMsg/><itemCount>0</itemCount></msgHeader><msgBody>
        <itemList><distance>15689</distance><time>41</time>
          <pathList><fid>115000542</fid><fname>대일고등학교</fname><fx>126.8635</fx><fy>37.5390</fy><routeId>100100087</routeId><routeNm>602</routeNm>
            <tid>118000489</tid><tname>양화대교전망카페</tname><tx>126.9011</tx><ty>37.5397</ty></pathList>
          <pathList><fid>0234</fid><fname>합정</fname><fx>126.9139</fx><fy>37.5495</fy><routeId>1002</routeId><routeNm>2호선</routeNm>
            <tid>0222</tid><tname>강남</tname><tx>127.0276</tx><ty>37.4979</ty></pathList>
        </itemList>
        <itemList><distance>16000</distance><time>55</time>
          <pathList><fid>115000542</fid><fname>대일고등학교</fname><fx>126.8635</fx><fy>37.5390</fy><routeId>100100087</routeId><routeNm>602</routeNm>
            <tid>121000001</tid><tname>강남역</tname><tx>127.0279</tx><ty>37.4981</ty></pathList>
        </itemList></msgBody></ServiceResult>"""

    @Test
    fun parse_busAndSubwayLegs() {
        val paths = SeoulPathParser.parse(sample)
        assertEquals(2, paths.size)
        assertEquals(41, paths[0].minutes)
        assertEquals(listOf(false, true), paths[0].legs.map { it.subway })
        assertEquals("양화대교전망카페", paths[0].legs[0].tname)
    }

    @Test
    fun toPlan_idsTimesAndWalks() {
        val p = SeoulPathParser.toPlan(SeoulPathParser.parse(sample)[0], 37.5391, 126.8636, 37.4980, 127.0277)!!
        assertEquals("SEB100100087", p.legs[0].routeId)      // 서울 버스 노선 ID → 지도·실시간
        assertEquals("SEB115000542", p.legs[0].boardStopId)
        assertTrue(p.legs[1].subway)
        assertEquals("2호선", p.legs[1].routeNo)
        assertEquals(41, p.minutes)                          // API 소요시간 그대로 (대기 정보 없음)
        assertTrue(p.transferWalkM in 1000.0..1700.0)        // 양화대교전망카페 → 합정 걸어서 갈아타기
        assertTrue(p.walkStartM < 30)
        assertEquals("서울시 경로검색", p.source)
    }

    @Test
    fun noRoute_isEmpty_andAreaCheck() {
        val none = """<ServiceResult><msgHeader><headerCd>7</headerCd><headerMsg>경로 검색 결과가 존재하지 않습니다.</headerMsg></msgHeader><msgBody/></ServiceResult>"""
        assertEquals(0, SeoulPathParser.parse(none).size)
        assertTrue(SeoulPathParser.covers(37.5665, 126.9780))   // 서울시청
        assertTrue(!SeoulPathParser.covers(35.8242, 127.1480))  // 전주
    }

    /** 실제 응답 (목동 → 서초, 2026-10-12 16:08): 20개 경로, 버스 → 지하철 → 버스. 지하철 구간에는 routeId 가 없고 railLinkList 로 역 수 */
    @Test
    fun realResponse_mokdongToSeocho() {
        val paths = SeoulPathParser.parse(javaClass.getResource("/fixtures/seoul_path_real.xml")!!.readText(Charsets.UTF_8))
        assertEquals(20, paths.size)
        val first = paths[0]
        assertEquals(69, first.minutes)
        assertEquals(19694, first.distance)
        assertEquals(listOf("6003", "2호선", "서초21"), first.legs.map { it.routeNm })
        assertEquals(listOf(false, true, false), first.legs.map { it.subway })
        assertEquals(null, first.legs[1].routeId)            // 지하철 구간: 노선 ID 없음
        assertEquals(9, first.legs[1].stations)              // 대림 → 서초 9개 구간
        assertTrue(paths.all { p -> p.legs.any { it.subway } })
        assertEquals("9호선", paths.last().legs[1].routeNm)

        val plan = SeoulPathParser.toPlan(first, 37.53, 126.86, 37.49, 127.02) { _, _, _ -> 12 }!!
        assertEquals("SEB100100414", plan.legs[0].routeId)
        assertEquals("SEB115000543", plan.legs[0].boardStopId)
        assertEquals(12, plan.legs[0].stops)                 // 버스: 앱 안 노선 정류장 순서로 센 값
        assertEquals("SUBWAY:2호선", plan.legs[1].routeId)
        assertEquals(9, plan.legs[1].stops)                  // 지하철: railLinkList 개수
        assertEquals("서초역3번출구", plan.legs[2].boardName)
        assertEquals(69, plan.minutes)
        assertEquals(2, plan.legs.size - 1)                  // 환승 2회
    }
}
