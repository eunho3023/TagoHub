package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import java.util.Locale

data class LatLng(val lat: Double, val lng: Double)

/** Google 인코딩 폴리라인 (OSRM polyline6 = 정밀도 6) */
object Polyline {
    fun decode(s: String, precision: Int = 6): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < s.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && i < s.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0L
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && i < s.length)
            lng += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }
}

/**
 * OSRM(OpenStreetMap 기반 경로 탐색) 공개 서버로 정류소를 차례로 잇는 도로 경로를 받는다.
 * TAGO 는 노선 모양(도로 선형)을 주지 않고 정류소 좌표만 주기 때문.
 */
object Osrm {
    const val BASE = "https://router.project-osrm.org/route/v1/driving/"
    /** 한 번에 보내는 경유지 수 (공개 서버 부담을 줄이려고 나눠서 요청) */
    const val CHUNK = 25

    fun url(points: List<LatLng>): String =
        BASE + points.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lng, it.lat) } +
            "?overview=full&geometries=polyline6&continue_straight=true"

    /** 앞 구간 끝 정류소를 다음 구간 시작으로 겹쳐서 나눈다 */
    fun chunks(stops: List<LatLng>, size: Int = CHUNK): List<List<LatLng>> {
        if (stops.size <= size) return listOf(stops)
        val out = ArrayList<List<LatLng>>()
        var start = 0
        while (start < stops.size - 1) {
            val end = minOf(start + size, stops.size)
            out += stops.subList(start, end)
            start = end - 1
        }
        return out
    }

    private val geometry = Regex("\"geometry\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** routes[0].geometry 만 꺼낸다 (org.json 없이 → JVM 테스트 가능) */
    fun parseGeometry(json: String?): List<LatLng>? {
        if (json == null || !Regex("\"code\"\\s*:\\s*\"Ok\"").containsMatchIn(json)) return null
        val raw = geometry.find(json)?.groupValues?.get(1) ?: return null
        return Polyline.decode(unescape(raw), 6).takeIf { it.size >= 2 }
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                        continue
                    }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/** 노선 선형 계산: 정류소 ↔ 도로 선형 대응, 구간 자르기, 경로 위 위치 */
object Shapes {
    fun length(path: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until path.size) d += Geo.meters(path[i - 1].lat, path[i - 1].lng, path[i].lat, path[i].lng)
        return d
    }

    /**
     * 각 정류소에 대응하는 선형 꼭짓점 인덱스. 앞으로만, 직전 정류소에서 4km 이내에서만 찾는다
     * (순환 노선이 같은 도로를 반대로 지날 때 엉뚱한 곳에 붙는 것 방지).
     */
    fun stopIndices(shape: List<LatLng>, stops: List<LatLng>): IntArray {
        val cum = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            cum[i] = cum[i - 1] + Geo.meters(shape[i - 1].lat, shape[i - 1].lng, shape[i].lat, shape[i].lng)
        }
        val out = IntArray(stops.size)
        var from = 0
        stops.forEachIndexed { k, s ->
            var best = from
            var bestD = Double.MAX_VALUE
            var j = from
            while (j < shape.size && cum[j] - cum[from] <= 4000.0) {
                val d = Geo.meters(s.lat, s.lng, shape[j].lat, shape[j].lng)
                if (d < bestD) {
                    bestD = d
                    best = j
                }
                j++
            }
            out[k] = best
            from = best
        }
        return out
    }

    /**
     * 길찾기 서버(OSRM)가 만든 선의 '한 바퀴 돌기' 제거.
     * 정류장 좌표가 중앙분리대 건너편·일방통행 반대편에 찍혀 있으면, 경유지에서 유턴을 못 하게 한 경로가 블록을 한 바퀴 돌아 그 점에 닿는다.
     * 연속한 두 정류장 사이 선이 직선거리의 1.8배이면서 250m 넘게 더 길면 그 구간만 두 정류장 직선으로 바꾼다.
     * (지역 버스 API 가 주는 실제 노선 모양에는 쓰지 않음 — 회차지 한 바퀴 등은 진짜 버스 경로)
     */
    fun removeDetours(shape: List<LatLng>, stops: List<LatLng>): List<LatLng> {
        if (shape.size < 2 || stops.size < 2) return shape
        val idx = stopIndices(shape, stops)
        val out = ArrayList<LatLng>()
        for (k in 0 until stops.size - 1) {
            val a = idx[k]
            val b = idx[k + 1]
            val straight = Geo.meters(stops[k].lat, stops[k].lng, stops[k + 1].lat, stops[k + 1].lng)
            val piece = if (b > a) shape.subList(a, b + 1) else emptyList()
            val road = if (piece.size >= 2) length(piece) else 0.0
            val loop = piece.size >= 2 && road > straight * 1.8 && road > straight + 250
            val seg = if (piece.size < 2 || loop) listOf(stops[k], stops[k + 1]) else piece
            out += if (out.isEmpty()) seg else seg.drop(1)
        }
        return out
    }

    /**
     * 앱 안에 넣은 노선 모양(경기 경로이력 2025-10)이 지금 노선과 맞는지: 정류장의 ratio 이상이 maxM 안을 지나야 함.
     * 노선 일부가 바뀌었으면 false → 그 노선만 지역 API 에서 최신 모양을 받음.
     */
    fun fitsStops(path: List<LatLng>, stops: List<LatLng>, maxM: Double = 100.0, ratio: Double = 0.9): Boolean {
        if (path.size < 2 || stops.isEmpty()) return false
        val ok = stops.count { s -> project(path, s).second <= maxM }
        return ok >= stops.size * ratio
    }

    /** 이 선이 정류장들 곁(maxM 안)을 모두 지나는지 — 카카오 경로가 이 노선 길이 맞는지 확인 */
    fun passesStops(path: List<LatLng>, stops: List<LatLng>, maxM: Double): Boolean {
        if (path.size < 2) return false
        return stops.all { s -> project(path, s).second <= maxM }
    }

    /** k 번째 정류소 → k+1 번째 정류소 구간. 선형이 없으면 두 정류소 직선 */
    fun segment(stops: List<LatLng>, shape: List<LatLng>?, idx: IntArray?, k: Int): List<LatLng> {
        if (k + 1 >= stops.size) return listOf(stops[k])
        if (shape != null && idx != null && idx[k + 1] > idx[k]) {
            return listOf(stops[k]) + shape.subList(idx[k], idx[k + 1] + 1) + stops[k + 1]
        }
        return listOf(stops[k], stops[k + 1])
    }

    /** 이 점에 가장 가까운 선형 꼭짓점 (from 부터, 그 뒤 maxAheadM 안에서만) */
    fun nearestIndex(shape: List<LatLng>, p: LatLng, from: Int = 0, maxAheadM: Double = Double.MAX_VALUE): Int {
        var best = from.coerceIn(0, shape.lastIndex)
        var bestD = Double.MAX_VALUE
        var walked = 0.0
        var i = best
        while (i < shape.size) {
            if (i > from) {
                walked += Geo.meters(shape[i - 1].lat, shape[i - 1].lng, shape[i].lat, shape[i].lng)
                if (walked > maxAheadM) break
            }
            val d = Geo.meters(p.lat, p.lng, shape[i].lat, shape[i].lng)
            if (d < bestD) {
                bestD = d
                best = i
            }
            i++
        }
        return best
    }

    /**
     * GPS 두 점 사이를 도로(노선 선형)를 따라 잇는다.
     * 선형이 없거나, 거꾸로 가거나, 너무 돌아가면(직선의 3배+200m 초과) 그냥 직선.
     */
    fun roadPath(a: LatLng, b: LatLng, shape: List<LatLng>?): List<LatLng> {
        if (shape == null || shape.size < 2) return listOf(a, b)
        val ia = nearestIndex(shape, a)
        val ib = nearestIndex(shape, b, from = ia, maxAheadM = 3000.0)
        if (ib <= ia) return listOf(a, b)
        val path = listOf(a) + shape.subList(ia, ib + 1) + b
        val straight = Geo.meters(a.lat, a.lng, b.lat, b.lng)
        return if (length(path) <= straight * 3 + 200) path else listOf(a, b)
    }

    /** 점을 경로에 투영: (경로 길이 대비 위치 0~1, 경로까지 거리 m) */
    fun project(path: List<LatLng>, p: LatLng): Pair<Double, Double> {
        if (path.size < 2) return 0.0 to (path.firstOrNull()?.let { Geo.meters(p.lat, p.lng, it.lat, it.lng) } ?: Double.MAX_VALUE)
        val total = length(path)
        if (total <= 0.0) return 0.0 to Geo.meters(p.lat, p.lng, path[0].lat, path[0].lng)
        val kx = Math.cos(Math.toRadians(p.lat))
        var walked = 0.0
        var bestF = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val seg = Geo.meters(a.lat, a.lng, b.lat, b.lng)
            val dx = (b.lng - a.lng) * kx
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.lng - a.lng) * kx * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0)
            val q = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val d = Geo.meters(p.lat, p.lng, q.lat, q.lng)
            if (d < bestD) {
                bestD = d
                bestF = (walked + seg * t) / total
            }
            walked += seg
        }
        return bestF to bestD
    }

    /** 경로 길이의 frac(0~1) 지점 */
    fun along(path: List<LatLng>, frac: Double): LatLng {
        if (path.size == 1) return path[0]
        val total = length(path)
        var left = total * frac.coerceIn(0.0, 1.0)
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = Geo.meters(a.lat, a.lng, b.lat, b.lng)
            if (left <= d || i == path.size - 1) {
                val t = if (d <= 0.0) 0.0 else (left / d).coerceIn(0.0, 1.0)
                return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
            left -= d
        }
        return path.last()
    }
}
