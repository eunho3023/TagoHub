package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 실시간 버스를 늦은 만큼 앞으로 당겨 그리기.
 * 버스 위치 API 는 받는 순간 이미 15~30초 전 위치다 (2026-11-16 서울 실측: 위치는 약 14초마다 바뀌고 받을 때 보통 28초 전).
 * 마지막 두 위치로 속도·방향을 구해 "자료 나이 + 받은 뒤 지난 시간" 만큼 그 방향으로 더 간 자리에 그린다.
 * 다음 정류장은 넘지 않고(버스는 정류장에 섬), 멈춘 버스·너무 옛 자료는 당기지 않는다.
 * 웹 site/js/busahead.js 와 같은 규칙.
 */
object BusAhead {
    /** 자료 시각을 주지 않는 API(TAGO 등)의 자료 나이 어림 (서울 실측 28초보다 짧게 잡아 덜 앞서가게) */
    const val UNKNOWN_AGE_MS = 15_000L
    /** 이보다 옛 자료는 어림하지 않음 (멈춘 차·끊긴 단말) */
    const val MAX_AGE_MS = 60_000L
    /**
     * 달리던 속도로 늦은 시간의 이만큼만 당김. 버스는 정류장·신호에서 서는 시간이 있어 다 당기면 지나치게 앞섬.
     * 2026-11-16 서울 5개 노선 5분 실측(734쌍, 그 시각의 실제 위치와 거리): 받은 자리 그대로 평균 109m·중앙 76m·상위10% 269m,
     * 다 당김(1.0) 103·81·230, 0.5 배 94·68·219 (0.3~0.7 중 가장 좋음)
     */
    const val LEAD_FACTOR = 0.5
    /** 아무리 늦어도 이만큼까지만 앞으로 */
    const val MAX_LEAD_MS = 40_000L
    const val MAX_AHEAD_M = 250.0
    /** 시속 70km 넘는 속도는 GPS 튐으로 봄 */
    const val MAX_SPEED_MPS = 70 / 3.6
    /** 이보다 느리면 멈춘 것으로 */
    const val MIN_SPEED_MPS = 1.0
    /** 진행 방향 선에서 이만큼 안쪽에 있는 정류장을 "앞 정류장"으로 */
    const val STOP_SIDE_M = 35.0
    /** 막 지나친 정류장(바로 옆)은 빼고 */
    const val STOP_MIN_AHEAD_M = 10.0

    /** 직전 위치 [from] → [to] 를 [durMs] 동안 간 속도(m/s). 모르거나 튀면 0 */
    fun speed(from: LatLng?, to: LatLng, durMs: Long): Double {
        if (from == null || durMs <= 0) return 0.0
        val v = Geo.meters(from.lat, from.lng, to.lat, to.lng) / (durMs / 1000.0)
        return if (v < MIN_SPEED_MPS || v > MAX_SPEED_MPS) 0.0 else v
    }

    /** 받은 순간 자료 나이(ms): 자료 시각을 알면 그것, 모르면 [UNKNOWN_AGE_MS] */
    fun ageAt(receivedMs: Long, dataAtMs: Long?): Long =
        if (dataAtMs == null) UNKNOWN_AGE_MS else (receivedMs - dataAtMs).coerceAtLeast(0)

    /**
     * [nowMs] 에 받은 위치에서 얼마나(m) 앞으로 그릴지.
     * [receivedMs] 받은 시각, [ageMs] 받을 때 자료 나이, [speedMps] 속도, [capM] 앞 정류장까지 거리 등 상한.
     */
    fun aheadM(speedMps: Double, receivedMs: Long, ageMs: Long, nowMs: Long, capM: Double = MAX_AHEAD_M): Double {
        if (speedMps <= 0 || ageMs > MAX_AGE_MS) return 0.0
        val lead = (ageMs + (nowMs - receivedMs)).coerceIn(0, MAX_LEAD_MS)
        return minOf(speedMps * lead / 1000.0 * LEAD_FACTOR, capM, MAX_AHEAD_M).coerceAtLeast(0.0)
    }

    /**
     * [p] 에서 [bearingDeg] 쪽으로 곧장 갈 때 만나는 가장 가까운 정류장까지 거리(m).
     * 진행 방향 선에서 [STOP_SIDE_M] 안, [STOP_MIN_AHEAD_M]~[maxM] 앞에 있는 정류장만. 없으면 null
     */
    fun stopAhead(p: LatLng, bearingDeg: Double, stops: List<LatLng>, maxM: Double = MAX_AHEAD_M): Double? {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        val ux = sin(Math.toRadians(bearingDeg))
        val uy = cos(Math.toRadians(bearingDeg))
        var best: Double? = null
        for (s in stops) {
            val dx = (s.lng - p.lng) * kx
            val dy = (s.lat - p.lat) * ky
            val along = dx * ux + dy * uy
            if (along < STOP_MIN_AHEAD_M || along > maxM) continue
            if (abs(dx * uy - dy * ux) > STOP_SIDE_M) continue
            if (best == null || along < best) best = along
        }
        return best
    }

    /** [p] 에서 [bearingDeg] 쪽으로 [m] 미터 간 점 */
    fun move(p: LatLng, bearingDeg: Double, m: Double): LatLng {
        if (m <= 0) return p
        val r = Math.toRadians(bearingDeg)
        return LatLng(p.lat + m * cos(r) / 110_540.0, p.lng + m * sin(r) / (111_320.0 * cos(Math.toRadians(p.lat))))
    }
}
