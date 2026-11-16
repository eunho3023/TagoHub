package com.eunho.tagohub

import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.ArrivalMerge
import com.eunho.tagohub.data.BusArrival
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalMergeTest {
    @Test
    fun tagoTimes_plusLocalDirectionAndStop() {
        val tago = TagoResult.Success(listOf(BusArrival("GGB1", "720", "직행좌석", "저상버스", 3, 300), BusArrival("GGB1", "720", "직행좌석", null, 9, 900),
            BusArrival("GGB2", "11-3", "일반", null, 1, 60)))
        val local = TagoResult.Success(listOf(BusArrival("2", "720", null, null, 3, 310, atStop = "수원역", towards = "강남역"),
            BusArrival("2", "720", null, null, 9, 880, atStop = "화서역", towards = "강남역")))
        val m = (ArrivalMerge.merge(tago, local) as TagoResult.Success).data
        assertEquals(listOf("11-3", "720", "720"), m.map { it.routeNo })                       // TAGO 시간 순
        assertEquals("수원역" to "강남역", m[1].atStop to m[1].towards)                          // 빠른 차끼리
        assertEquals("화서역", m[2].atStop); assertEquals(300, m[1].arrSeconds); assertEquals("저상버스", m[1].vehicleType)   // 시간·저상은 TAGO
        assertEquals(null, m[0].towards)                                                       // 지역 API 에 없는 노선은 그대로
        assertEquals(2, (ArrivalMerge.merge(TagoResult.Error("x"), local) as TagoResult.Success).data.size)   // TAGO 실패 → 지역 API
    }

    /** 경기·전주·제주: 지역 API 가 더 새로워서 먼저, 못 받거나 비면 TAGO */
    @Test
    fun preferLocal_usesRegionalFirstAndTagoOnlyWhenRegionalMissing() {
        val tago = TagoResult.Success(listOf(BusArrival("JUB1", "6001", "일반", null, 4, 400)))
        val local = TagoResult.Success(listOf(BusArrival("JUB1", "6001", "일반", null, 3, 290), BusArrival("JUB2", "110", "일반", null, 1, 60)))
        val m = (ArrivalMerge.preferLocal(local, tago) as TagoResult.Success).data
        assertEquals(listOf("110", "6001"), m.map { it.routeNo })   // 지역 자료만, 시간 순
        assertEquals(290, m[1].arrSeconds)
        assertEquals(400, ((ArrivalMerge.preferLocal(TagoResult.Error("x"), tago) as TagoResult.Success).data[0].arrSeconds))   // 지역 실패 → TAGO
        assertEquals(400, ((ArrivalMerge.preferLocal(TagoResult.Success(emptyList()), tago) as TagoResult.Success).data[0].arrSeconds)) // 지역 빔 → TAGO
        assertEquals(400, ((ArrivalMerge.preferLocal(null, tago) as TagoResult.Success).data[0].arrSeconds))
    }
}
