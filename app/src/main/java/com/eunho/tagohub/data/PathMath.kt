package com.eunho.tagohub.data

import com.eunho.tagohub.core.OffsetLine
import kotlin.math.cos

/**
 * 지도 엔진과 상관없는 선 계산 (카카오맵은 화면 픽셀로 선을 띄울 수 없어서 실제 거리로 띄운다).
 */
object PathMath {
    /**
     * 선을 진행 방향 오른쪽으로 meters 만큼 띄운다 (상·하행 나란히 그리기).
     * 좁은 범위라 평면으로 근사: 동쪽 = x, 남쪽 = y(화면처럼 아래가 +) → OffsetLine 의 '오른쪽'이 실제 오른쪽.
     */
    fun offsetMeters(points: List<LatLng>, meters: Double): List<LatLng> {
        if (points.size < 2 || meters == 0.0) return points
        val lat0 = points[0].lat
        val lng0 = points[0].lng
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        val xs = FloatArray(points.size) { ((points[it].lng - lng0) * kx).toFloat() }
        val ys = FloatArray(points.size) { (-(points[it].lat - lat0) * ky).toFloat() }
        val (ox, oy) = OffsetLine.offset(xs, ys, meters.toFloat())
        return List(points.size) { LatLng(lat0 - oy[it] / ky, lng0 + ox[it] / kx) }
    }

    /**
     * 차량 표시 위치: glide(직전 보이던 자리 → 새 시작점, glideMs) 뒤에 path 위 startFrac → endFrac 을 durMs 동안.
     * elapsed 는 이번 이동을 시작한 뒤 흐른 시간(ms).
     */
    fun vehicleAt(
        path: List<LatLng>, startFrac: Double, endFrac: Double, durMs: Long,
        glideFrom: LatLng?, glideMs: Long, elapsed: Long,
    ): LatLng {
        if (path.isEmpty()) return glideFrom ?: LatLng(0.0, 0.0)
        val start = Shapes.along(path, startFrac)
        if (glideFrom != null && elapsed < glideMs) {
            val t = (elapsed.toDouble() / glideMs).coerceIn(0.0, 1.0)
            return LatLng(glideFrom.lat + (start.lat - glideFrom.lat) * t, glideFrom.lng + (start.lng - glideFrom.lng) * t)
        }
        if (durMs <= 0 || path.size < 2) return Shapes.along(path, endFrac)
        val k = ((elapsed - (if (glideFrom != null) glideMs else 0)).toDouble() / durMs).coerceIn(0.0, 1.0)
        return Shapes.along(path, startFrac + (endFrac - startFrac) * k)
    }

    /** 화면 가로 픽셀과 그 양 끝의 실제 거리로 m/px (선 띄우는 거리 계산) */
    fun metersPerPixel(left: LatLng, right: LatLng, widthPx: Int): Double =
        if (widthPx <= 0) 0.0 else com.eunho.tagohub.core.Geo.meters(left.lat, left.lng, right.lat, right.lng) / widthPx
}
