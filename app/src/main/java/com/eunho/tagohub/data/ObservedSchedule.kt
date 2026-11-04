package com.eunho.tagohub.data

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 지난 운행 기록(이 폰 + 서버에 모인 기록, [ArrivalLog]·[SharedArrivalLog])으로 "다음 버스가 보통 몇 시에 오는지" 어림.
 * 도착 정보·버스 위치로 다음 차가 안 보일 때 배차 간격보다 먼저 쓴다 ("약 N분 · 지난 기록 기준").
 * 여러 날 같은 무렵에 기록된 운행만 믿는다 ([MIN_DAYS]).
 */
object ObservedSchedule {
    const val MIN_DAYS = ArrivalStats.MIN_DAYS
    const val MAX_AHEAD_MIN = ArrivalStats.MAX_AHEAD_MIN
    private const val TTL_MS = 10 * 60_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<ArrivalStats.Hit>>>()

    /** 이 노선·정류장의 기록 (10분 동안 재사용) */
    suspend fun hits(routeId: String, nodeId: String, nowMs: Long = System.currentTimeMillis()): List<ArrivalStats.Hit> {
        val key = "$routeId|$nodeId"
        cache[key]?.takeIf { nowMs - it.first < TTL_MS }?.let { return it.second }
        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ArrivalLog.read(routeId) }.filter { it.nodeId == nodeId }
        val merged = SharedArrivalLog.merge(local, SharedArrivalLog.fetch(routeId, nodeId))
        cache[key] = nowMs to merged
        return merged
    }

    /** [ArrivalStats.nextAfter] (공용 코드) */
    fun nextAfter(table: ArrivalStats.Table, afterMin: Int, nowMin: Int): Int? = ArrivalStats.nextAfter(table, afterMin, nowMin)

    /** 지금부터 [afterSec] 초 뒤 다음으로 기록상 오는 버스까지 남은 초. 없으면 null */
    suspend fun nextSec(routeId: String, nodeId: String, afterSec: Int, now: ZonedDateTime = ZonedDateTime.now(ArrivalLog.zone)): Int? {
        val hits = runCatching { hits(routeId, nodeId) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return null
        val day: DayOfWeek = now.dayOfWeek
        val nowMin = now.hour * 60 + now.minute
        val nowSecOfDay = nowMin * 60 + now.second
        val m = nextAfter(ArrivalLog.table(hits, nodeId, day), (nowSecOfDay + afterSec) / 60, nowMin) ?: return null
        return (m * 60 - nowSecOfDay).takeIf { it > 0 }
    }
}
