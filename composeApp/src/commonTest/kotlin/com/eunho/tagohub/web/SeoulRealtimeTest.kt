package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeoulRealtimeTest {
    @Test fun lineIds() {
        assertEquals("1001", SeoulRealtime.lineIdOf(SubwayStationW("MTRS11150", "서울역", "1호선")))
        assertEquals("1063", SeoulRealtime.lineIdOf(SubwayStationW("MTRKRK4P313", "서울역", "경의중앙")))
        assertEquals("1065", SeoulRealtime.lineIdOf(SubwayStationW("MTRARA1A01", "서울역", "공항")))
        assertNull(SeoulRealtime.lineIdOf(SubwayStationW("MTRBS10119", "서면", "1호선")))      // 부산
        assertNull(SeoulRealtime.lineIdOf(SubwayStationW("MTRICI1124", "인천시청", "인천1호선")))
        assertEquals(listOf("총신대입구(이수)", "총신대입구"), SeoulRealtime.queryNames("총신대입구(이수)"))
        assertEquals(listOf("서울"), SeoulRealtime.queryNames("서울역"))
    }

    @Test fun parsesArrivals() {
        val body = """{"errorMessage":{"code":"INFO-000"},"realtimeArrivalList":[
            {"subwayId":"1001","updnLine":"상행","trainLineNm":"소요산행 - 시청방면","barvlDt":"0","arvlMsg2":"[4]번째 전역 (남영)","arvlCd":"99","bstatnNm":"소요산","btrainSttus":"급행","lstcarAt":"0","ordkey":"01004소요산0"},
            {"subwayId":"1001","updnLine":"하행","barvlDt":"240","arvlMsg2":"서울 진입","arvlCd":"0","bstatnNm":"인천","btrainSttus":"일반","lstcarAt":"1"}
        ]}"""
        val l = SeoulRealtime.parse(body)
        assertEquals("4번째 전역", l[0].text)
        assertNull(l[0].seconds)
        assertEquals(true, l[0].express)
        assertEquals("진입", l[1].text)
        assertEquals(240, l[1].seconds)
        assertEquals(true, l[1].last)
        assertEquals(emptyList(), SeoulRealtime.parse("""{"code":"INFO-200","message":"없음"}"""))
    }
}
