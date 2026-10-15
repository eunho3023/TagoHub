package com.eunho.tagohub

import com.eunho.tagohub.data.CctvParse
import org.junit.Assert.assertEquals
import org.junit.Test

/** 브이월드 2D 데이터 API 응답 형식 (featureCollection) — 속성 이름은 실제 응답을 받으면 확인 */
class CctvTest {
    @Test
    fun parse_featureCollection() {
        val body = """{"response":{"status":"OK","result":{"featureCollection":{"type":"FeatureCollection","features":[
          {"type":"Feature","id":"LT_P_UTISCCTV.1","geometry":{"type":"Point","coordinates":[127.1295,35.8467]},
           "properties":{"CCTVNAME":"전주 백제대로","CCTVURL":"http://cctvsec.ktict.co.kr/1/abc","X":"127.1295"}},
          {"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[126.98,37.57]]},"properties":{"locate":"서울 세종대로"}}]}}}}"""
        val c = CctvParse.parse(body)
        assertEquals(2, c.size)
        assertEquals("전주 백제대로", c[0].name); assertEquals(35.8467, c[0].lat, 1e-9); assertEquals("http://cctvsec.ktict.co.kr/1/abc", c[0].url)
        assertEquals("서울 세종대로", c[1].name); assertEquals(37.57, c[1].lat, 1e-9); assertEquals(null, c[1].url)
        assertEquals(0, CctvParse.parse("""{"response":{"status":"NOT_FOUND"}}""").size)
    }
}
