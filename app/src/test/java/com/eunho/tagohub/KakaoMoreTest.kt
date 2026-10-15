package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoLocal
import com.eunho.tagohub.data.KakaoWalk
import org.junit.Assert.assertEquals
import org.junit.Test

/** 카카오맵 REST API 문서 예제 형식: 주소 검색·행정구역·카테고리·자전거 */
class KakaoMoreTest {
    @Test
    fun address_region_category_bicycle() {
        val addr = """{"meta":{"total_count":1},"documents":[{"address_name":"전북 익산시 부송동 100","y":"35.97664845766847","x":"126.99597295767953","address_type":"REGION_ADDR",
          "address":{"address_name":"전북 익산시 부송동 100","x":"126.99597295767953","y":"35.97664845766847"},
          "road_address":{"address_name":"전북 익산시 망산길 11-17","building_name":"","zone_no":"54547","y":"35.976749396987046","x":"126.99599512792346"}}]}"""
        val a = KakaoLocal.parseAddress(addr)
        assertEquals("전북 익산시 망산길 11-17", a[0].name); assertEquals("전북 익산시 부송동 100", a[0].address); assertEquals(35.97664845766847, a[0].lat, 1e-9)

        val reg = """{"meta":{"total_count":2},"documents":[{"region_type":"B","address_name":"경기도 성남시 분당구 삼평동","code":"4113510900","x":127.104,"y":37.402},
          {"region_type":"H","address_name":"경기도 성남시 분당구 삼평동","code":"4113565500","x":127.10,"y":37.40}]}"""
        assertEquals("경기도 성남시 분당구 삼평동", KakaoLocal.regionName(reg))

        val cat = """{"meta":{"total_count":1},"documents":[{"place_name":"장생당약국","distance":"412","place_url":"http://place.map.kakao.com/16618597",
          "category_name":"의료,건강 > 약국","address_name":"서울 강남구 대치동 943-16","road_address_name":"서울 강남구 테헤란로84길 17","id":"16618597",
          "phone":"02-558-5476","category_group_code":"PM9","category_group_name":"약국","x":"127.05897078335246","y":"37.506051888130386"}]}"""
        val c = KakaoLocal.parse(cat)
        assertEquals("장생당약국", c[0].name); assertEquals("약국", c[0].category); assertEquals(412, c[0].distance); assertEquals("02-558-5476", c[0].phone)

        val bike = """{"route":{"properties":{"totalDistance":4155,"totalTime":1242},"legs":[{"properties":{"distance":4155,"time":1242},
          "steps":[{"properties":{"distance":76,"time":12},"path":{"points":[[127.11143401,37.39488363],[127.11145558,37.39495569],[127.11145156,37.39529448]]}}]}]},"status":"OK"}"""
        assertEquals(4155 to 1242, KakaoWalk.totals(bike))
        assertEquals(3, KakaoWalk.parse(bike).second.size)
    }
}
