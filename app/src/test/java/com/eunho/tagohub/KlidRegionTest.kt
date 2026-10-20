package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoLocal
import com.eunho.tagohub.data.KlidBusJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** 초정밀 버스: 지역 코드(stdgCd)는 카카오 법정동 코드에서, K3 는 '데이터 없음'(오류 아님) */
class KlidRegionTest {
    @Test
    fun legalCode_fromKakao() {
        val body = """{"meta":{"total_count":2},"documents":[{"region_type":"B","address_name":"전북특별자치도 전주시 덕진구 금암동","code":"5211311200","x":127.13,"y":35.84},
          {"region_type":"H","address_name":"전북특별자치도 전주시 덕진구 금암1동","code":"5211364000"}]}"""
        val code = KakaoLocal.legalCode(body)!!
        assertEquals("5211311200", code)
        assertEquals("5211300000", code.take(5) + "00000")      // 시군구
        assertEquals("5200000000", code.take(2) + "00000000")   // 시도
    }

    @Test
    fun k3_isEmpty_notError() {
        val k3 = """{"header":{"resultCode":"K3","resultMsg":"NODATA_ERROR"},"body":{"totalCount":0,"items":""}}"""
        val p = KlidBusJson.parsePage(k3, "rteId")
        assertEquals(0, p.totalCount); assertEquals(0, p.rows.size)
    }
}
