package com.eunho.tagohub.data

import java.time.Duration
import java.time.LocalDateTime

/**
 * 새로고침했을 때 도착까지 남은 시간이 거꾸로 늘어나지 않게 한다 (카카오맵·네이버처럼).
 * 서버의 도착 예측은 몇십 초마다만 바뀌어서, 화면에서 이미 줄어든 값보다 큰 값이 와도 같은 버스면
 * 줄어든 값을 그대로 둔다. 많이 늘었거나(다른 버스·큰 지연) 버스가 뒤로 간 경우에는 서버 값을 믿는다.
 */
object ArrivalSmoothing {
    /** 화면 값보다 이만큼(초) 넘게 늘면 같은 버스가 아니라고 보고 서버 값을 따름 */
    const val MAX_RISE_SEC = 180

    /** 같은 버스로 보고 줄어든 값을 쓸지 */
    internal fun keepShown(shown: Int, server: Int, oldStops: Int?, newStops: Int?): Boolean =
        shown > 0 && server > shown && server - shown <= MAX_RISE_SEC && (oldStops == null || newStops == null || newStops <= oldStops)

    /**
     * 정류장 도착 목록. [elapsedSec] 은 [prev] 를 받은 뒤 지난 초.
     * 노선마다 도착이 빠른 순으로 짝지어 비교한다.
     */
    fun bus(prev: List<BusArrival>?, elapsedSec: Int, next: List<BusArrival>): List<BusArrival> {
        if (prev.isNullOrEmpty()) return next
        val old = prev.filter { it.arrSeconds != null }.groupBy { it.routeId }.mapValues { e -> e.value.sortedBy { it.arrSeconds } }
        val used = HashMap<String, Int>()
        val order = next.indices.sortedBy { next[it].arrSeconds ?: Int.MAX_VALUE }
        val out = next.toMutableList()
        for (i in order) {
            val n = next[i]
            val sec = n.arrSeconds ?: continue
            val k = used.merge(n.routeId, 1, Int::plus)!! - 1
            val p = old[n.routeId]?.getOrNull(k) ?: continue
            val shown = (p.arrSeconds ?: continue) - elapsedSec
            if (keepShown(shown, sec, p.prevStops, n.prevStops)) out[i] = n.copy(arrSeconds = shown)
        }
        return out
    }

    /** 지하철 도착 목록. 화면은 `seconds - (지금 - received)` 로 남은 시간을 그리므로, 바꾼 줄은 received 를 [now] 로 맞춘다 */
    fun subway(prev: List<SubwayArrival>?, next: List<SubwayArrival>, now: LocalDateTime): List<SubwayArrival> {
        if (prev.isNullOrEmpty()) return next
        fun key(a: SubwayArrival) = a.trainNo?.takeIf { it.isNotBlank() }?.let { "t$it" } ?: "${a.lineId}|${a.upDown}|${a.order}"
        val old = prev.associateBy { key(it) }
        return next.map { n ->
            val p = old[key(n)] ?: return@map n
            val ps = p.seconds?.takeIf { it > 0 } ?: return@map n
            val ns = n.seconds?.takeIf { it > 0 } ?: return@map n
            val shown = ps - (p.received?.let { Duration.between(it, now).seconds.toInt().coerceIn(0, 600) } ?: 0)
            // 지하철은 정거장 수 대신 현재 위치 문구가 달라지므로 같은 열차번호일 때만 짝지음
            if (p.trainNo.isNullOrBlank() && n.trainNo.isNullOrBlank() && p.current != n.current && ns > shown) return@map n
            if (keepShown(shown, ns, null, null)) n.copy(seconds = shown, received = now) else n
        }
    }
}
