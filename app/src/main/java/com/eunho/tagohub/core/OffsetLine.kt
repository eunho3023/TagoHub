package com.eunho.tagohub.core

import kotlin.math.sqrt

/**
 * 화면 좌표 선을 진행 방향 오른쪽으로 d 픽셀 평행 이동 (상·하행 선을 나란히 띄워 그리려고).
 * 화면 좌표는 y 가 아래로 커지므로 진행 방향 (dx, dy) 의 오른쪽 법선은 (-dy, dx).
 * 꺾이는 점은 양쪽 구간 법선의 평균 방향으로 옮기고(마이터), 아주 뾰족한 곳은 2d 까지로 제한한다.
 */
object OffsetLine {
    fun offset(xs: FloatArray, ys: FloatArray, d: Float): Pair<FloatArray, FloatArray> {
        val n = xs.size
        val ox = xs.copyOf()
        val oy = ys.copyOf()
        if (n < 2 || d == 0f) return ox to oy
        // 구간별 오른쪽 단위 법선 (길이 0 구간은 이전 값 유지)
        val nx = FloatArray(n - 1)
        val ny = FloatArray(n - 1)
        var lx = 0f
        var ly = 0f
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-3f) {
                lx = -dy / len
                ly = dx / len
            }
            nx[i] = lx
            ny[i] = ly
        }
        // 앞쪽 길이 0 구간 채우기
        val firstValid = (0 until n - 1).firstOrNull { nx[it] != 0f || ny[it] != 0f } ?: return ox to oy
        for (i in 0 until firstValid) { nx[i] = nx[firstValid]; ny[i] = ny[firstValid] }
        for (i in 0 until n) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == n - 1) n - 2 else i
            var mx = nx[a] + nx[b]
            var my = ny[a] + ny[b]
            val ml = sqrt(mx * mx + my * my)
            if (ml < 1e-3f) { mx = nx[b]; my = ny[b] } else { mx /= ml; my /= ml }
            val cos = (mx * nx[b] + my * ny[b]).coerceAtLeast(0.5f) // 2d 제한
            ox[i] = xs[i] + mx * d / cos
            oy[i] = ys[i] + my * d / cos
        }
        return ox to oy
    }
}
