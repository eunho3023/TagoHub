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
    /** 이 수 이상의 날에 기록된 운행만 씀 (하루 기록은 우연일 수 있음) */
    const val MIN_DAYS = 2
    /** 이보다 먼 다음 차는 보여 주지 않음 (분) */
    const val MAX_AHEAD_MIN = 120
    private const val TTL_MS = 10 * 60_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<ArrivalLog.Hit>>>()

    /** 이 노선·정류장의 기록 (10분 동안 재사용) */
    suspend fun hits(routeId: String, nodeId: String, nowMs: Long = System.currentTimeMillis()): List<ArrivalLog.Hit> {
        val key = "$routeId|$nodeId"
        cache[key]?.takeIf { nowMs - it.first < TTL_MS }?.let { return it.second }
        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ArrivalLog.read(routeId) }.filter { it.nodeId == nodeId }
        val merged = SharedArrivalLog.merge(local, SharedArrivalLog.fetch(routeId, nodeId))
        cache[key] = nowMs to merged
        return merged
    }

    /**
     * [afterMin](오늘 하루 중 분) 뒤로 기록상 처음 오는 운행의 예상 시각(분). 여러 날 기록된 운행만, [MAX_AHEAD_MIN] 안에서. 없으면 null.
     */
    fun nextAfter(table: ArrivalLog.Table, afterMin: Int, nowMin: Int): Int? =
        table.slots.filter { it.days >= MIN_DAYS && it.expected > afterMin && it.expected - nowMin <= MAX_AHEAD_MIN }
            .minByOrNull { it.expected }?.expected

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
