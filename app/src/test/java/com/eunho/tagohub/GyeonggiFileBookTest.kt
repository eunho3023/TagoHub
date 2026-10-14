package com.eunho.tagohub

import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.GyeonggiRouteBook
import com.eunho.tagohub.ui.bus.mergeStopRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** 앱 안 노선인가정보(예전 자료) + 최신 목록 + 실시간 도착 번호 */
class GyeonggiFileBookTest {
    private fun r(id: String, no: String, s: String? = null, e: String? = null) =
        BusRoute("GGB$id", no, null, s, e, null, null, null, null, null, "31")

    @Test
    fun latestOverridesFile_fileOnlyKept() {
        // 실제: 216000070 은 파일 707-1(신안산대학교→초지고등학교), 2026 은 1200(원시역→수원역)
        val file = listOf(r("216000070", "707-1", "신안산대학교", "초지고등학교"), r("200000085", "98"), r("210000076", "96-1"))
        val latest = listOf(r("216000070", "1200", "원시역1번출구", "수원역10번출구.역전시장"), r("219000016", "1200"))
        val book = GyeonggiRouteBook.combine(file, latest)
        assertEquals("1200", book["GGB216000070"]!!.routeNo)          // 바뀐 번호
        assertEquals("원시역1번출구", book["GGB216000070"]!!.startName)
        assertEquals("98", book["GGB200000085"]!!.routeNo)            // 파일에만 있는 노선 유지
        assertEquals("1200", book["GGB219000016"]!!.routeNo)          // 새 노선 추가
        assertEquals(4, book.size)
    }

    @Test
    fun stopRow_prefersLiveNumber() {
        // 최신 목록을 받기 전: 앱 안 자료는 707-1 이지만 도착 정보는 1200 → 1200 으로 보임
        val rows = mergeStopRows(
            listOf(BusArrival("GGB216000070", "1200", "직행좌석형시내버스", null, 3, 240)),
            listOf(r("216000070", "707-1"), r("200000085", "98")),
            nowMin = 12 * 60,
        )
        assertEquals("1200", rows.first { it.routeId == "GGB216000070" }.routeNo)
        assertEquals("직행좌석형시내버스", rows.first { it.routeId == "GGB216000070" }.routeType)
        assertEquals("98", rows.first { it.routeId == "GGB200000085" }.routeNo) // 도착 정보 없으면 앱 안 번호
    }
}
