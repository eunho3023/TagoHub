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
    fun offsetMeters(points: List<LatLng>, meters: Double): List<LatLng> = offsetMeters(points, DoubleArray(points.size) { meters })

    /** 꼭짓점마다 다른 거리로 (0 이면 그 자리는 그대로) */
    fun offsetMeters(points: List<LatLng>, meters: DoubleArray): List<LatLng> {
        if (points.size < 2 || meters.all { it == 0.0 }) return points
        val lat0 = points[0].lat
        val lng0 = points[0].lng
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        val xs = FloatArray(points.size) { ((points[it].lng - lng0) * kx).toFloat() }
        val ys = FloatArray(points.size) { (-(points[it].lat - lat0) * ky).toFloat() }
        val (ox, oy) = OffsetLine.offset(xs, ys, FloatArray(points.size) { meters.getOrElse(it) { 0.0 }.toFloat() })
        return List(points.size) { LatLng(lat0 - oy[it] / ky, lng0 + ox[it] / kx) }
    }

    /**
     * 오른쪽으로 비키는 실제 거리 한도 (m). 화면 굵기만큼 띄우면 확대했을 때 몇~십몇 m 가 되어
     * 좁은 골목을 도는 노선이 지도 도로 밖으로 나갔음 (2026-11-21). 웹 sideline.js 와 같음
     */
    const val MAX_SIDE_M = 3.0
    private const val TWO_WAY_M = 12.0

    /**
     * 꼭짓점마다 "이 자리를 반대 방향으로도 지나는지" (같은 길을 오가는 구간만 오른쪽으로 비켜 두 줄로, 나머지는 도로 한가운데).
     * [others]: 같은 노선의 다른 선(상·하행을 따로 그릴 때). 자기 선에서는 앞뒤 3구간 안은 보지 않음. 웹 sideline.js twoWayMask 와 같음
     */
    fun twoWayMask(line: List<LatLng>, others: List<List<LatLng>> = emptyList()): BooleanArray {
        val n = line.size
        val out = BooleanArray(n)
        if (n < 2) return out
        val lat0 = line[0].lat
        val lng0 = line[0].lng
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val ux: Double, val uy: Double, val i: Int)
        val segs = ArrayList<Seg>()
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        fun add(l: List<LatLng>, own: Boolean): List<DoubleArray> {
            val p = l.map { doubleArrayOf((it.lng - lng0) * kx, (it.lat - lat0) * ky) }
            for (i in 0 until p.size - 1) {
                val (ax, ay) = p[i].let { it[0] to it[1] }
                val (bx, by) = p[i + 1].let { it[0] to it[1] }
                val len = kotlin.math.hypot(bx - ax, by - ay)
                if (len < 0.5) continue
                val k = segs.size
                segs += Seg(ax, ay, bx, by, (bx - ax) / len, (by - ay) / len, if (own) i else Int.MIN_VALUE / 2)
                val x0 = kotlin.math.floor(minOf(ax, bx) / TWO_WAY_M).toInt(); val x1 = kotlin.math.floor(maxOf(ax, bx) / TWO_WAY_M).toInt()
                val y0 = kotlin.math.floor(minOf(ay, by) / TWO_WAY_M).toInt(); val y1 = kotlin.math.floor(maxOf(ay, by) / TWO_WAY_M).toInt()
                for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(key(gx, gy)) { mutableListOf() } += k
            }
            return p
        }
        val p = add(line, true)
        for (o in others) if (o.size >= 2) add(o, false)
        for (i in 0 until n) {
            val a = minOf(i, n - 2)
            val dx = p[a + 1][0] - p[a][0]
            val dy = p[a + 1][1] - p[a][1]
            val dl = kotlin.math.hypot(dx, dy)
            if (dl < 1e-6) continue
            val ux = dx / dl
            val uy = dy / dl
            val px = p[i][0]
            val py = p[i][1]
            val gx = kotlin.math.floor(px / TWO_WAY_M).toInt()
            val gy = kotlin.math.floor(py / TWO_WAY_M).toInt()
            search@ for (x in gx - 1..gx + 1) for (y in gy - 1..gy + 1) {
                for (k in grid[key(x, y)] ?: continue) {
                    val s = segs[k]
                    if (kotlin.math.abs(s.i.toLong() - i) <= 3) continue
                    if (ux * s.ux + uy * s.uy > -0.7) continue   // 반대 방향(135도 넘게)만
                    val vx = s.bx - s.ax
                    val vy = s.by - s.ay
                    val t = (((px - s.ax) * vx + (py - s.ay) * vy) / (vx * vx + vy * vy)).coerceIn(0.0, 1.0)
                    if (kotlin.math.hypot(px - s.ax - t * vx, py - s.ay - t * vy) <= TWO_WAY_M) { out[i] = true; break@search }
                }
            }
        }
        return out
    }

    /** 같은 길을 오가는 구간만 오른쪽으로 [meters] (한도 [MAX_SIDE_M]) 비킨 선 */
    fun offsetTwoWay(points: List<LatLng>, meters: Double, others: List<List<LatLng>> = emptyList()): List<LatLng> {
        val m = minOf(meters, MAX_SIDE_M)
        val mask = twoWayMask(points, others)
        return offsetMeters(points, DoubleArray(points.size) { if (mask[it]) m else 0.0 })
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
