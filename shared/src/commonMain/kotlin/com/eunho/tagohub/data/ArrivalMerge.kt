package com.eunho.tagohub.data

import com.eunho.tagohub.core.TagoResult

/**
 * 버스 도착 = TAGO(시간 초·남은 정류장·저상) + 지역 API(방면·현재 정류장).
 * 같은 노선번호끼리 빠른 순서대로 짝지어 지역 API 의 방면·현재 정류장을 덧붙임.
 * TAGO 가 실패하거나 비면 지역 API 결과를 그대로.
 */
object ArrivalMerge {
    private fun key(no: String) = no.replace(" ", "").replace("번", "").lowercase()

    /**
     * 지역 API 를 먼저 (경기 GBIS·전주시·제주 BIS). 같은 정류장·같은 순간에 비교하니 지역 자료가 TAGO 보다 1~2분 새롭고
     * 버스도 더 많이 나왔음 (2026-11-16: 수원역 31대/20대, 전북대 42/24, 제주터미널 5/2, 같은 버스 첫차가 90~140초 빠름).
     * 지역 API 가 실패하거나 비었을 때만 TAGO
     */
    fun preferLocal(local: TagoResult<List<BusArrival>>?, tago: TagoResult<List<BusArrival>>): TagoResult<List<BusArrival>> {
        val l = (local as? TagoResult.Success)?.data.orEmpty()
        return if (l.isNotEmpty()) TagoResult.Success(l.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }) else tago
    }

    fun merge(tago: TagoResult<List<BusArrival>>, local: TagoResult<List<BusArrival>>?): TagoResult<List<BusArrival>> {
        val t = (tago as? TagoResult.Success)?.data.orEmpty()
        val l = (local as? TagoResult.Success)?.data.orEmpty()
        if (t.isEmpty()) return if (l.isNotEmpty()) TagoResult.Success(l) else tago
        if (l.isEmpty()) return TagoResult.Success(t)
        val byNo = l.groupBy { key(it.routeNo) }.mapValues { (_, v) -> v.sortedBy { it.arrSeconds ?: Int.MAX_VALUE } }
        val used = HashMap<String, Int>()
        val out = t.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }.map { a ->
            val k = key(a.routeNo)
            val i = used[k] ?: 0
            used[k] = i + 1
            val m = byNo[k]?.getOrNull(i) ?: byNo[k]?.firstOrNull()
            if (m == null) a else a.copy(atStop = a.atStop ?: m.atStop, towards = a.towards ?: m.towards)
        }
        return TagoResult.Success(out)
    }
}
