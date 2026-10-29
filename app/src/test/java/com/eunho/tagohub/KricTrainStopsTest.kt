package com.eunho.tagohub

import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.KricRouteStation
import com.eunho.tagohub.data.KricSubwayApi
import com.eunho.tagohub.data.KricSubwayCodes
import com.eunho.tagohub.data.KricSubwayRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KricTrainStopsTest {
    private fun item(train: String, st: String, arr: String, dep: String) =
        "<item><railOprIsttCd>KR</railOprIsttCd><trnNo>$train</trnNo><dayCd>8</dayCd><dayNm>평일</dayNm><stinCd>$st</stinCd><lnCd>K4</lnCd><arvTm>$arr</arvTm><dptTm>$dep</dptTm></item>"

    private class Fake(val tables: Map<String, String>) : KricSubwayApi {
        var calls = 0
        override suspend fun routeInfo(regionCode: String, lineCode: String) = ""
        override suspend fun timetable(operatorCode: String, dayCode: String, lineCode: String, stationCode: String): String {
            calls++
            val body = tables[stationCode].orEmpty()
            return "<ROOT><header><resultCnt>1</resultCnt><resultCode>00</resultCode><resultMsg>ok</resultMsg></header><body>$body</body></ROOT>"
        }
    }

    private fun st(order: Int, code: String, name: String) = KricRouteStation("01", "1", "x", order, "KR", "K4", code, name)

    @Test
    fun collectsTheStationsATrainCallsAtInTimeOrderAndRemembersTheTables() = runBlocking {
        val api = Fake(mapOf(
            "S1" to item("K2201", "S1", "", "061000") + item("K9999", "S1", "", "070000"),
            "S2" to item("K2201", "S2", "061400", "061430"),
            "S3" to item("K2201", "S3", "062100", "") + item("K9999", "S3", "071000", ""),
            "S4" to item("K9999", "S4", "072000", ""),   // K2201 이 안 서는 역
        ))
        val repo = KricSubwayRepository(api, key = "k")
        val stations = listOf(st(3, "S3", "다"), st(1, "S1", "가"), st(2, "S2", "나"), st(4, "S4", "라"))
        val codes = KricSubwayCodes("KR", "K4", "S1")
        val a = (repo.trainStops(codes, "8", "K2201", stations) as TagoResult.Success).data
        assertEquals(listOf("가", "나", "다"), a.map { it.stationName })
        assertEquals("061430", a[1].departureTime)
        assertEquals(4, api.calls)
        // 같은 노선의 다른 열차는 이미 받은 역별 시각표를 쓰므로 호출이 늘지 않음
        val b = (repo.trainStops(codes, "8", "K9999", stations) as TagoResult.Success).data
        assertEquals(listOf("가", "다", "라"), b.map { it.stationName })
        assertEquals(4, api.calls)
    }

    @Test
    fun aTrainThatIsNotInTheTablesIsReportedInsteadOfShowingAnEmptyList() = runBlocking {
        val repo = KricSubwayRepository(Fake(mapOf("S1" to item("K1", "S1", "", "061000"))), key = "k")
        val r = repo.trainStops(KricSubwayCodes("KR", "K4", "S1"), "8", "K2201", listOf(st(1, "S1", "가"), st(2, "S2", "나")))
        assertTrue(r is TagoResult.Error)
    }
}
