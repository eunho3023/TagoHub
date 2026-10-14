package com.eunho.tagohub

import com.eunho.tagohub.data.KakaoLocal
import org.junit.Assert.assertEquals
import org.junit.Test

/** 카카오 로컬 키워드 검색 응답 형식 (documents[] 의 place_name·주소·x·y 문자열) */
class KakaoLocalTest {
    private val body = """{"documents":[
      {"address_name":"전북 전주시 덕진구 덕진동1가 664-1","category_group_code":"","id":"8160983","phone":"063-270-2114",
       "place_name":"전북대학교 전주캠퍼스","road_address_name":"전북 전주시 덕진구 백제대로 567","x":"127.129436","y":"35.846833"},
      {"address_name":"전북 전주시 완산구 서노송동 568","id":"1","place_name":"전주시청","road_address_name":"","x":"127.148","y":"35.8242"},
      {"place_name":"좌표없음","x":"","y":""}],
      "meta":{"is_end":true,"pageable_count":2,"total_count":2}}"""

    @Test
    fun parse_keywordResult() {
        val p = KakaoLocal.parse(body)
        assertEquals(2, p.size)                                   // 좌표 없는 건 건너뜀
        assertEquals("전북대학교 전주캠퍼스", p[0].name)
        assertEquals("전북 전주시 덕진구 백제대로 567", p[0].address) // 도로명 먼저
        assertEquals(35.846833, p[0].lat, 1e-9)                   // y = 위도
        assertEquals(127.129436, p[0].lng, 1e-9)                  // x = 경도
        assertEquals("전북 전주시 완산구 서노송동 568", p[1].address) // 도로명 없으면 지번
    }

    @Test
    fun errorBody() {
        // 카카오맵 사용 설정이 꺼진 앱 (카카오 데브톡에 올라온 실제 문구)
        val e = KakaoLocal.error("""{"errorType":"NotAuthorizedError","message":"App(TagoHub) disabled OPEN_MAP_AND_LOCAL service."}""")
        assertEquals("NotAuthorizedError: App(TagoHub) disabled OPEN_MAP_AND_LOCAL service.", e)
        assertEquals(0, KakaoLocal.parse("not json").size)
    }
}
