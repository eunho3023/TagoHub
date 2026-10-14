package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlin.math.cos

/**
 * 지도에 그릴 GPS 이동 한 구간. 위치는 모두 노선 선 위로 붙인(snap) 점이라 버스가 선을 벗어나지 않는다.
 * from → to 를 at 부터 durMs 동안. fromSeg/toSeg: 선에서 그 점이 놓인 구간 시작 꼭짓점.
 */
data class GpsTrack(
    val from: LatLng?,
    val to: LatLng,
    val at: Long,
    val durMs: Long = 9_000,
    val fromSeg: Int? = null,
    val toSeg: Int? = null,
    val progress: Double? = null,
    val ord: Int? = null,
    /** 서버가 준 원래 좌표 (멈춤 판단용) */
    val raw: LatLng = to,
)

/** 선 위에 붙인 위치: 구간 시작 꼭짓점, 붙인 점, 노선 시작부터 거리(m) */
data class Snap(val seg: Int, val point: LatLng, val progress: Double)

/**
 * 노선 선 = 노선 모양(있으면) 아니면 정류장을 순서대로 이은 선.
 * GPS 점을 이 선 위 가장 가까운 점으로 붙인다. 버스가 알려준 정류장 순번 앞뒤 구간에서만 찾아서
 * 같은 도로를 반대 방향으로도 지나는 노선에서 엉뚱한 쪽에 붙지 않는다.
 */
class RouteGeom(val shape: List<LatLng>?, stops: List<RouteStop>) {
    private val pts = stops.filter { it.lat != null && it.lng != null }
    val stopCount = pts.size
    /** 실제로 쓰는 선 */
    val line: List<LatLng> = shape?.takeIf { it.size >= 2 } ?: pts.map { LatLng(it.lat!!, it.lng!!) }
    private val stopIdx: IntArray? = if (line.size >= 2 && pts.size >= 2) Shapes.stopIndices(line, pts.map { LatLng(it.lat!!, it.lng!!) }) else null
    private val cum = DoubleArray(line.size).also { c ->
        for (i in 1 until line.size) c[i] = c[i - 1] + Geo.meters(line[i - 1].lat, line[i - 1].lng, line[i].lat, line[i].lng)
    }

    /** 정류장 순번 → 그 정류장 좌표 */
    fun stopPoint(ord: Int?): LatLng? =
        ord?.let { o -> pts.firstOrNull { it.ord == o } }?.let { LatLng(it.lat!!, it.lng!!) }

    fun snap(ord: Int?, p: LatLng): Snap? {
        if (line.size < 2) return null
        val k = ord?.let { o -> pts.indexOfFirst { it.ord == o } }?.takeIf { it >= 0 }
        val idx = stopIdx
        val lo = if (k != null && idx != null) idx[maxOf(0, k - 1)] else 0
        val hi = if (k != null && idx != null) idx[minOf(pts.lastIndex, k + 1)] else line.lastIndex
        var best: Snap? = null
        var bestD = Double.MAX_VALUE
        val kx = cos(Math.toRadians(p.lat)) // 경도 1도의 실제 길이 보정 (좁은 범위라 평면 근사)
        for (i in lo until maxOf(lo + 1, hi)) {
            if (i + 1 >= line.size) break
            val a = line[i]
            val b = line[i + 1]
            val dx = (b.lng - a.lng) * kx
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.lng - a.lng) * kx * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0)
            val q = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val d = Geo.meters(p.lat, p.lng, q.lat, q.lng)
            if (d < bestD) {
                bestD = d
                best = Snap(i, q, cum[i] + (cum[i + 1] - cum[i]) * t)
            }
        }
        return best
    }
}

/**
 * 실시간 GPS → 지도 움직임.
 * - 좌표를 노선 선 위로 붙여서 그림 (선을 벗어나지 않음)
 * - 8m 안에서 그대로면 멈춤 (신호·정체·정차)
 * - 이미 간 곳보다 뒤(15m 넘게)로 오는 위치는 늦게 온 옛 데이터로 보고 무시, 3번 연속이면 바로 옮김
 * - 정류장 순번이 노선 절반 넘게 줄면 새 운행 → 바로 옮김
 * - live: 같은 정류장 순번에서 좌표가 실제로 바뀌는 걸 한 번이라도 봤는지.
 *   지역에 따라 TAGO 가 GPS 대신 정류장 좌표를 주는데(정류장에 닿을 때만 바뀜), 그런 버스는 live 가 안 되고
 *   정류장 순번으로 시간에 따라 움직이는 추정 방식으로 그린다 (안 그러면 정류장 사이에서 멈춰 보임).
 */
object GpsTracker {
    const val MOVE_MIN_M = 8.0
    const val BACK_TOLERANCE_M = 15.0
    const val BACK_CONFIRM = 3

    data class State(val track: GpsTrack, val backStreak: Int = 0, val live: Boolean = false)

    /** 받은 좌표가 알려준 정류장에서 이만큼 넘게 떨어져 있으면 정류장 좌표가 아니라 실제 GPS */
    const val OFF_STOP_M = 25.0

    fun next(prev: State?, p: LatLng, ord: Int?, geom: RouteGeom?, now: Long): State {
        val s = geom?.snap(ord, p)
        val shown = s?.point ?: p
        fun track(from: LatLng?, fromSeg: Int?, dur: Long) = GpsTrack(from, shown, now, dur, fromSeg, s?.seg, s?.progress, ord, p)
        // 처음부터 정류장과 떨어진 좌표면 실시간 GPS 로 바로 판단 (추정 → GPS 전환 때 뒤로 튀는 것 방지)
        val offStop = geom?.stopPoint(ord)?.let { Geo.meters(it.lat, it.lng, p.lat, p.lng) > OFF_STOP_M } ?: false
        if (prev == null) return State(track(null, null, 0), live = offStop)
        val g = prev.track
        val moved = Geo.meters(g.raw.lat, g.raw.lng, p.lat, p.lng)
        if (moved < MOVE_MIN_M) return prev.copy(backStreak = 0)
        // 같은 정류장 순번인데 좌표가 움직임, 또는 정류장과 떨어진 좌표 = 진짜 실시간 GPS
        val live = prev.live || offStop || (ord != null && ord == g.ord)

        val newTrip = ord != null && g.ord != null && geom != null && g.ord - ord > geom.stopCount / 2
        val delta = if (!newTrip && s != null && g.progress != null) s.progress - g.progress else 0.0
        if (delta < -BACK_TOLERANCE_M) {
            // 크게 뒤 = 늦게 온 옛 데이터. 3번 연속이면 실제로 보고 바로 옮김 (뒤로 미끄러지지 않게)
            val streak = prev.backStreak + 1
            return if (streak < BACK_CONFIRM) prev.copy(backStreak = streak, live = live) else State(track(null, null, 0), 0, live)
        }
        if (delta < 0) {
            // 조금 뒤 (GPS 오차·시차) = 그 자리에 그대로. 버스는 뒤로 가지 않는다
            return prev.copy(backStreak = 0, live = live)
        }
        if (newTrip) return State(track(null, null, 0), 0, live)
        return State(track(g.to, g.toSeg, (now - g.at).coerceIn(5_000, 30_000)), 0, live)
    }

    /** 그릴 경로: 붙인 점 → 선의 꼭짓점들 → 붙인 점 (선을 따라감) */
    fun path(t: GpsTrack, line: List<LatLng>?): List<LatLng> {
        val from = t.from ?: return listOf(t.to)
        val a = t.fromSeg
        val b = t.toSeg
        if (line != null && a != null && b != null && b >= a && b - a < 600) {
            return listOf(from) + (if (b > a) line.subList(a + 1, b + 1) else emptyList()) + t.to
        }
        return listOf(from, t.to)
    }
}

/**
 * 늦게 온 옛 데이터 막기 (정류장 순번 기준, 모든 버스).
 * 서버가 나중 응답에 더 옛날 위치(한 정류장 전)를 주기도 해서, 순번이 줄면 직전 위치를 유지한다.
 * 3번 연속 줄어든 순번이 오면 실제로 보고 받아들이고, 노선 절반 넘게 줄면(새 운행 시작) 바로 받아들인다.
 */
object StaleGuard {
    const val CONFIRM = 3

    fun filter(previous: List<BusLocation>, fresh: List<BusLocation>, streaks: MutableMap<String, Int>, stopCount: Int): List<BusLocation> {
        val before = previous.filter { it.vehicleNo != null }.associateBy { it.vehicleNo!! }
        return fresh.map { b ->
            val id = b.vehicleNo ?: return@map b
            val old = before[id]
            val o = old?.ord
            val n = b.ord
            if (old == null || o == null || n == null || n >= o) {
                streaks.remove(id)
                return@map b
            }
            if (stopCount > 0 && o - n > stopCount / 2) { // 새 운행
                streaks.remove(id)
                return@map b
            }
            val k = (streaks[id] ?: 0) + 1
            if (k >= CONFIRM) {
                streaks.remove(id)
                b
            } else {
                streaks[id] = k
                old
            }
        }
    }
}
