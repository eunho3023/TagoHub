package com.eunho.tagohub.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 노선별 "지금 도로 상황" 보정값. 도로 소통 정보를 따로 받는 대신, 그 노선 버스들이 실제로 정류장을 옮겨 간 시간을
 * 평소 소요 시간(거리 기준)과 비교해서 쓴다. 막히면 1보다 커지고 한산하면 1보다 작아진다.
 * 버스 위치를 두 번 이상 본 노선만 보정되고, 처음 보는 노선은 1.0(보정 없음)이다.
 */
object RouteSpeed {
    const val MIN_FACTOR = 0.6
    const val MAX_FACTOR = 2.5
    private const val SMOOTH = 0.3
    private const val MIN_EXPECTED_MIN = 3.0
    private const val MAX_GAP_MS = 12 * 60_000L
    private const val STALE_MS = 20 * 60_000L

    private class Seen(val atMs: Long, val ord: Int, val sawArrive: Boolean)
    private class Factor(val value: Double, val atMs: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val factors = ConcurrentHashMap<String, Factor>()

    /** 노선 [routeId] 의 보정값 (모르면 1.0). 오래된 값은 버림 */
    fun factor(routeId: String, nowMs: Long = System.currentTimeMillis()): Double =
        factors[routeId]?.takeIf { nowMs - it.atMs < STALE_MS }?.value ?: 1.0

    /**
     * 이번에 받은 버스 위치로 보정값을 갱신. [expectedMin] 은 순번 a → b 평소 소요(분).
     * 버스가 정류장을 옮긴 순간만 기준으로 삼아서, 처음 본 위치(언제 도착했는지 모름)에서 출발한 구간은 건너뛴다.
     */
    fun observe(
        routeId: String,
        buses: List<BusLocation>,
        nowMs: Long = System.currentTimeMillis(),
        expectedMin: (Int, Int) -> Double,
    ) {
        for (b in buses) {
            val ord = b.ord ?: continue
            val key = routeId + "|" + (b.vehicleNo ?: continue)
            val prev = seen[key]
            if (prev == null || ord < prev.ord || nowMs - prev.atMs > MAX_GAP_MS) {
                seen[key] = Seen(nowMs, ord, false)
                continue
            }
            if (ord == prev.ord) continue
            if (prev.sawArrive) {
                val expected = expectedMin(prev.ord, ord)
                if (expected >= MIN_EXPECTED_MIN) {
                    val ratio = ((nowMs - prev.atMs) / 60_000.0 / expected).coerceIn(MIN_FACTOR, MAX_FACTOR)
                    val old = factors[routeId]?.takeIf { nowMs - it.atMs < STALE_MS }?.value
                    factors[routeId] = Factor(if (old == null) ratio else old + (ratio - old) * SMOOTH, nowMs)
                }
            }
            seen[key] = Seen(nowMs, ord, true)
        }
    }

    internal fun reset() {
        seen.clear()
        factors.clear()
    }
}
