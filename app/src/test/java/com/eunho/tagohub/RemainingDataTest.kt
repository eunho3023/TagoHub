package com.eunho.tagohub

import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Polyline
import com.eunho.tagohub.data.RouteDirections
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.SeoulBusXml
import com.eunho.tagohub.data.SeoulMappers2
import com.eunho.tagohub.data.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemainingDataTest {
    @Test
    fun polyline_precision5_googleExample() {
        // Google 공식 예제 (정밀도 5): (38.5,-120.2) (40.7,-120.95) (43.252,-126.453)
        val p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-9); assertEquals(-120.2, p[0].lng, 1e-9)
        assertEquals(43.252, p[2].lat, 1e-9); assertEquals(-126.453, p[2].lng, 1e-9)
    }

    @Test
    fun fitsStops_detectsChangedRoute() {
        val line = listOf(LatLng(35.80, 127.10), LatLng(35.80, 127.12))
        val on = (0..9).map { LatLng(35.8001, 127.10 + it * 0.002) }
        assertTrue(Shapes.fitsStops(line, on))
        val changed = on.take(7) + (0..2).map { LatLng(35.81, 127.11 + it * 0.002) }   // 30% 가 1km 떨어진 새 길
        assertTrue(!Shapes.fitsStops(line, changed))
    }

    private fun st(id: String) = RouteStop(id, id, null, 0, null, 35.8, 127.1)

    @Test
    fun seoulTurn_marksDirections() {
        val xml = """<ServiceResult><msgHeader><headerCd>0</headerCd><headerMsg>정상</headerMsg></msgHeader><msgBody>
          <itemList><seq>1</seq><station>111000001</station><stationNm>기점</stationNm><transYn>Y</transYn></itemList>
          <itemList><seq>2</seq><station>111000002</station><stationNm>가</stationNm><transYn>N</transYn></itemList>
          <itemList><seq>3</seq><station>111000003</station><stationNm>회차지</stationNm><transYn>Y</transYn></itemList>
          <itemList><seq>4</seq><station>111000004</station><stationNm>나</stationNm><transYn>N</transYn></itemList>
          <itemList><seq>5</seq><station>111000005</station><stationNm>기점</stationNm><transYn>N</transYn></itemList>
          </msgBody></ServiceResult>"""
        val turn = SeoulMappers2.turnStation(SeoulBusXml.parse(xml))
        assertEquals("SEB111000003", turn)                                   // 첫 정류장의 Y 는 빼고
        val stops = (1..5).map { st("SEB11100000$it") }
        val marked = RouteDirections.markTurn(stops, turn)
        assertEquals(listOf("0", "0", "0", "1", "1"), marked.map { it.upDown })
        assertEquals(2, RouteDirections.turnIndex(marked))
        assertEquals(stops, RouteDirections.markTurn(stops, null))           // 회차 정보 없으면 그대로 → 전체
        assertEquals(stops, RouteDirections.markTurn(stops, "SEB111000005")) // 마지막이 회차 = 편도 → 나누지 않음
    }
}
