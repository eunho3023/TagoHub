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
    /**
     * k 번째 정류장에서 회차함: 앞뒤 정류장이 거의 같은 자리 (예: 입석 → 용운입구 → 입석).
     * 길찾기는 경유지에서 U턴을 하지 않아, 회차 정류장을 가운데 두고 한 번에 물으면 멀리 돌아오고 그 뒤 정류장이 모두 직선이 됨
     */
    fun turnsAt(stops: List<LatLng>, k: Int): Boolean {
        if (k <= 0 || k >= stops.size - 1) return false
        val m = { a: LatLng, b: LatLng -> Geo.meters(a.lat, a.lng, b.lat, b.lng) }
        val ab = m(stops[k - 1], stops[k]); val bc = m(stops[k], stops[k + 1])
        return ab >= 150 && bc >= 150 && m(stops[k - 1], stops[k + 1]) <= 0.3 * minOf(ab, bc)
    }

    /** 앞 구간 끝 정류장을 다음 구간 시작으로 겹쳐서 나눔. 회차 정류장에서는 끊어 가는 길·돌아오는 길을 따로 물음 (서버 shape.js chunks 와 같음) */
    fun chunks(stops: List<LatLng>, size: Int = CHUNK): List<List<LatLng>> {
        val out = ArrayList<List<LatLng>>()
        var start = 0
        while (start < stops.size - 1) {
            var end = minOf(start + size, stops.size)
            for (k in start + 1 until end - 1) if (turnsAt(stops, k)) { end = k + 1; break }
            out += stops.subList(start, end)
            start = end - 1
        }
        return if (out.isEmpty()) listOf(stops) else out
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

/**
 * 정류장 사이 길 고치기: 이어지는 두 정류장 사이의 실제 길 (노선 자료·길찾기가 모르는 회차 길 등).
 * 그 두 정류장을 차례로 지나는 모든 노선에 씀 (웹 site/js/roadline.js SEG_FIXES 와 같은 표)
 */
object SegFixes {
    data class Fix(val from: String, val to: String, val path: List<LatLng>)

    val all: List<Fix> = listOf(
        // 전주 기린대로 병무청(3050104 → 길 건너 3050070): 임실·진안 버스는 기린대로로 올라가 이름 없는 큰 블록(전주고 서쪽)을 돌아 회차
        Fix(
            "TSB305000104", "TSB305000070",
            listOf(
                LatLng(35.821319, 127.152197),
                LatLng(35.821797, 127.151635),
                LatLng(35.822193, 127.15113),
                LatLng(35.82233, 127.150958),
                LatLng(35.822449, 127.15083),
                LatLng(35.822529, 127.150739),
                LatLng(35.82257, 127.150693),
                LatLng(35.822663, 127.150589),
                LatLng(35.822737, 127.150495),
                LatLng(35.822784, 127.150436),
                LatLng(35.822841, 127.150374),
                LatLng(35.822958, 127.150242),
                LatLng(35.82316, 127.150012),
                LatLng(35.823265, 127.149886),
                LatLng(35.823849, 127.149296),
                LatLng(35.823902, 127.149279),
                LatLng(35.823918, 127.149278),
                LatLng(35.823943, 127.149275),
                LatLng(35.823964, 127.149275),
                LatLng(35.824004, 127.149282),
                LatLng(35.824197, 127.149542),
                LatLng(35.824247, 127.14961),
                LatLng(35.824377, 127.149785),
                LatLng(35.824438, 127.149867),
                LatLng(35.824494, 127.14992),
                LatLng(35.82462, 127.150039),
                LatLng(35.82471, 127.150124),
                LatLng(35.824744, 127.150599),
                LatLng(35.823897, 127.150686),
                LatLng(35.823803, 127.150695),
                LatLng(35.823739, 127.150601),
                LatLng(35.823496, 127.150843),
                LatLng(35.82332, 127.151035),
                LatLng(35.823286, 127.151072),
                LatLng(35.823208, 127.151174),
                LatLng(35.823109, 127.151089),
                LatLng(35.823083, 127.151059),
                LatLng(35.822837, 127.150775),
                LatLng(35.822806, 127.150746),
                LatLng(35.82277, 127.150713),
                LatLng(35.822729, 127.150675),
                LatLng(35.822663, 127.150589),
                LatLng(35.822493, 127.150585),
                LatLng(35.822267, 127.150861),
                LatLng(35.822091, 127.151058),
                LatLng(35.82139, 127.151889),
                LatLng(35.821092, 127.152248),
                LatLng(35.821012, 127.152345),
            ),
        ),
    )

    private fun m(a: LatLng, b: LatLng) = Geo.meters(a.lat, a.lng, b.lat, b.lng)

    /** line 에서 점 p 에 가장 가까운 꼭짓점 (from 부터 길 maxM 안) → (번호, 거리) */
    private fun nearest(line: List<LatLng>, p: LatLng, from: Int, maxM: Double): Pair<Int, Double>? {
        var best: Pair<Int, Double>? = null
        var walked = 0.0
        for (i in from until line.size) {
            if (i > from) walked += m(line[i - 1], line[i])
            if (walked > maxM) break
            val d = m(line[i], p)
            if (best == null || d < best.second) best = i to d
        }
        return best
    }

    /** 고친 길을 노선 선에 끼움. [stops] 는 노선 차례대로 (id, 좌표) */
    fun apply(line: List<LatLng>, stops: List<Pair<String, LatLng>>, fixes: List<Fix> = all): List<LatLng> {
        if (line.size < 2 || stops.size < 2) return line
        var out = line
        var cursor = 0
        for (k in 0 until stops.size - 1) {
            val a = stops[k]; val b = stops[k + 1]
            val fix = fixes.firstOrNull { it.from == TagoIds.legacy(a.first) && it.to == TagoIds.legacy(b.first) } ?: continue
            val ha = nearest(out, a.second, cursor, 1e9) ?: continue
            if (ha.second > 150) continue
            val hb = nearest(out, b.second, ha.first, 3000.0) ?: continue
            if (hb.second > 150) continue
            out = out.subList(0, ha.first + 1) + fix.path + out.subList(hb.first, out.size)
            cursor = ha.first + fix.path.size
        }
        return out
    }
}

/** 노선 선형 계산: 정류소 ↔ 도로 선형 대응, 구간 자르기, 경로 위 위치 */
object Shapes {
    private fun bearing(a: LatLng, b: LatLng): Double =
        Math.atan2((b.lng - a.lng) * Math.cos(Math.toRadians(a.lat)), b.lat - a.lat)

    private fun turnAt(a: LatLng, b: LatLng, c: LatLng): Double = angleBetween(bearing(a, b), bearing(b, c))

    private fun angleBetween(x: Double, y: Double): Double {
        val t = Math.abs(x - y) % (2 * Math.PI)
        return if (t > Math.PI) 2 * Math.PI - t else t
    }

    private fun m(a: LatLng, b: LatLng): Double = Geo.meters(a.lat, a.lng, b.lat, b.lng)

    /** path[i] 에서 길을 따라 [dist] m 앞(step 1)·뒤(step -1) 점 (모자라면 끝점) */
    private fun pointAlong(path: List<LatLng>, i: Int, dist: Double, step: Int): LatLng {
        var walked = 0.0
        var k = i
        while (k + step in path.indices) {
            walked += m(path[k], path[k + step])
            if (walked >= dist) return path[k + step]
            k += step
        }
        return if (step > 0) path.last() else path.first()
    }

    /** 점 p 에서 선분 a-b 까지 거리(m) */
    private fun toSeg(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * Math.cos(Math.toRadians(p.lat)); val ky = 110_540.0
        val ax = (a.lng - p.lng) * kx; val ay = (a.lat - p.lat) * ky; val bx = (b.lng - p.lng) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
        val t = if (l > 0) (-(ax * dx + ay * dy) / l).coerceIn(0.0, 1.0) else 0.0
        return Math.hypot(ax + t * dx, ay + t * dy)
    }

    /** i~j 안에 교차로처럼 한 꼭짓점에서 70도 넘게 꺾이는 곳이 있음 (산길 굽이·회전교차로는 여러 꼭짓점에 걸쳐 완만하게 돎) */
    private fun hasSharpTurn(p: List<LatLng>, i: Int, j: Int): Boolean {
        for (k in maxOf(1, i)..minOf(j, p.size - 2)) {
            if (m(p[k - 1], p[k]) < 3 || m(p[k], p[k + 1]) < 3) continue
            if (turnAt(p[k - 1], p[k], p[k + 1]) >= Math.toRadians(70.0)) return true
        }
        return false
    }

    /** i~j 에 회전교차로를 도는 길이 있음: 같은 쪽으로 꼭짓점마다 60도 안씩 이어서 돌아 모두 180도 넘게 돎 (들어가고 나오는 곳은 크게 꺾여도 됨) */
    private fun roundabout(p: List<LatLng>, i: Int, j: Int): Boolean {
        var run = 0.0
        var prev: Double? = null
        for (k in i until j) {
            if (m(p[k], p[k + 1]) < 1) continue
            val b = bearing(p[k], p[k + 1])
            if (prev != null) {
                var t = b - prev
                while (t > Math.PI) t -= 2 * Math.PI
                while (t < -Math.PI) t += 2 * Math.PI
                run = if (Math.abs(t) > Math.PI / 3) 0.0
                    else if (run != 0.0 && Math.signum(t) != Math.signum(run) && Math.abs(t) > 0.05) t
                    else run + t
                if (Math.abs(run) >= Math.PI) return true
            }
            prev = b
        }
        return false
    }

    /** i~j 우회가 큰길 옆에 찍힌 정류장 때문에 생김: 그 길 30m 곁에, 곧게 편 큰길에서 70m 안인 정류장이 있음 */
    private fun strayStop(p: List<LatLng>, i: Int, j: Int, stops: List<Pair<LatLng, String?>>): Boolean =
        stops.any { (s, _) -> toSeg(s, p[i], p[j]) <= 70 && (i until j).any { k -> toSeg(s, p[k], p[k + 1]) <= 30 } }

    /**
     * 노선 정류장 차례로 보면 i~j 에서 회차함: 길 30m 곁에 바로 이어지는 두 정류장이 80m 안에 마주 보거나(길 건너 같은 정류장),
     * 앞뒤 정류장이 거의 같은 자리인 회차 정류장이 있음 (웹 roadline.js turnStop 과 같음)
     */
    private fun turnStop(p: List<LatLng>, i: Int, j: Int, stops: List<Pair<LatLng, String?>>): Boolean {
        val near = { s: LatLng -> (i until j).any { k -> toSeg(s, p[k], p[k + 1]) <= 30 } }
        for (k in stops.indices) {
            val s = stops[k].first
            val n = stops.getOrNull(k + 1)?.first
            if (n != null && m(s, n) <= 80 && near(s) && near(n)) return true
            val pr = stops.getOrNull(k - 1)?.first
            if (pr != null && n != null) {
                val ab = m(pr, s); val bc = m(s, n)
                if (ab >= 150 && bc >= 150 && m(pr, n) <= 0.3 * minOf(ab, bc) && near(s)) return true
            }
        }
        return false
    }

    /** 버스가 실제로 큰길에서 들어가 들르는 정류장 이름 (터미널·차고지·종점·회차지) */
    private val VISIT_RE = Regex("터미널|터미날|차고|종점|회차")

    /** i~j 길 30m 곁에 실제로 들르는 정류장(터미널 등)이 있음 → 곧게 펴지 않음 */
    private fun servesStop(p: List<LatLng>, i: Int, j: Int, stops: List<Pair<LatLng, String?>>): Boolean =
        stops.any { (s, name) -> name != null && VISIT_RE.containsMatchIn(name) && (i until j).any { k -> toSeg(s, p[k], p[k + 1]) <= 30 } }

    /**
     * 골목 들르기만 없애기, 회차·회전교차로·실제로 들르는 곳은 남김 (웹 site/js/roadline.js despike 와 같은 규칙).
     * 노선 모양을 정류장 몇 개씩 끊어 받아 이어 붙인 곳에서, 큰길 옆에 찍힌 정류장 때문에 선이 옆 골목으로 빠짐:
     *  - 들렀다 같은 자리(25m 안, 반대 차로 포함)로 돌아오는 가시·고리 (길 300m 안)
     *  - 곧은 큰길에서 옆으로 빠졌다(80m 안) 같은 큰길로 같은 방향으로 다시 들어오는 짧은 우회
     *    (길 400m 안, 교차로처럼 꺾이는 곳이 있고, 큰길 70m 안에 찍힌 정류장 곁을 지남)
     * 를 곧게. 남기는 것: 회차(들어온·나가는 방향 135도 넘게 반대, 그 안도 그대로), 회전교차로(한쪽으로 고르게 180도 넘게),
     * 이름에 터미널·차고지·종점·회차가 든 정류장 곁을 지나는 길, 노선 처음·끝이라 방향을 모르는 곳.
     * [stops]: 노선 정류장 (좌표, 이름)
     */
    fun despike(path: List<LatLng>, stops: List<Pair<LatLng, String?>> = emptyList()): List<LatLng> {
        if (path.size < 3) return path
        val pts = dropJitter(path)
        val out = ArrayList<LatLng>(pts.size)
        var i = 0
        var keepTo = -1
        while (i < pts.size) {
            if (out.isEmpty() || m(out.last(), pts[i]) >= 0.5) out += pts[i]
            if (i <= keepTo || i == 0) { i++; continue }
            val before = pointAlong(pts, i, 30.0, -1)
            if (m(before, pts[i]) < 5) { i++; continue }
            val hin = bearing(before, pts[i])
            var walked = 0.0
            var far = 0.0
            var cut = -1
            var turn = -1
            for (j in i + 1 until pts.size) {
                walked += m(pts[j - 1], pts[j])
                if (walked > 400.0) break
                val d = m(pts[i], pts[j])
                far = maxOf(far, d)
                val after = pointAlong(pts, j, 30.0, 1)
                if (m(pts[j], after) < 5) break   // 노선 끝
                val hout = bearing(pts[j], after)
                if (d <= 25 && far >= d + 10 && walked >= d * 2.5 + 20 && walked <= 300) {
                    if (angleBetween(hin, hout) >= Math.toRadians(135.0) || roundabout(pts, i, j) || turnStop(pts, i, j, stops)) turn = j
                    else if (!servesStop(pts, i, j, stops)) cut = j
                } else if (d >= 40 && walked >= d * 1.3 + 30) {
                    val ch = bearing(pts[i], pts[j])
                    if (angleBetween(hin, ch) > 0.44 || angleBetween(hout, ch) > 0.44) continue
                    var dev = 0.0
                    for (k in i..j) { dev = maxOf(dev, toSeg(pts[k], pts[i], pts[j])); if (dev > 80) break }
                    if (dev <= 80 && hasSharpTurn(pts, i, j) && !roundabout(pts, i, j) && strayStop(pts, i, j, stops) && !servesStop(pts, i, j, stops)) cut = j
                }
            }
            if (turn >= 0 && turn >= cut) { keepTo = turn; i++; continue }   // 회차는 통째로 남김
            i = if (cut >= 0) cut else i + 1
        }
        return out
    }

    /** 같은 점(0.5m 안) 겹침과, 5m 안에서 앞뒤로 떨리는 점(150도 넘게 되돌아감)을 뺌 (한 번만) */
    private fun dropJitter(path: List<LatLng>): List<LatLng> {
        val pts = ArrayList<LatLng>(path.size)
        for (q in path) if (pts.isEmpty() || m(pts.last(), q) >= 0.5) pts += q
        if (pts.size < 3) return pts
        val out = ArrayList<LatLng>(pts.size)
        out += pts.first()
        for (i in 1 until pts.size - 1) {
            val a = out.last(); val b = pts[i]; val c = pts[i + 1]
            if (turnAt(a, b, c) > Math.toRadians(150.0) && minOf(m(a, b), m(b, c)) <= 5.0) continue
            out += b
        }
        out += pts.last()
        return out
    }

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

    /**
     * 노선 모양에서 두 정류장 사이 구간만 잘라내기 (카카오 경로를 못 받은 구간을 지역 공식 모양으로 채울 때).
     * 두 정류장이 모양 곁(maxM 안)에 있고 순서가 맞아야 함. 아니면 null.
     */
    fun slice(shape: List<LatLng>, from: LatLng, to: LatLng, maxM: Double = 80.0): List<LatLng>? {
        if (shape.size < 2) return null
        val (fa, da) = project(shape, from)
        val (fb, db) = project(shape, to)
        if (da > maxM || db > maxM || fb <= fa) return null
        val total = length(shape)
        if (total <= 0) return null
        // 길이 비율 → 점 번호: 앞쪽 경계는 정류장 위치, 사이 점들은 그대로
        var acc = 0.0
        val out = arrayListOf(along(shape, fa))
        for (i in 1 until shape.size) {
            acc += Geo.meters(shape[i - 1].lat, shape[i - 1].lng, shape[i].lat, shape[i].lng)
            val f = acc / total
            if (f > fa && f < fb) out += shape[i]
        }
        out += along(shape, fb)
        return out.takeIf { it.size >= 2 }
    }

    /**
     * 꺾이는 곳을 부드럽게 (Chaikin 2번). 각 꼭짓점을 양옆 1/4 지점 두 개로 바꿔 모서리를 둥글게 — 처음·끝 점은 그대로.
     * 점이 너무 많으면(이미 촘촘한 도로 경로) 그대로.
     */
    fun smooth(path: List<LatLng>, rounds: Int = 2): List<LatLng> {
        var p = path
        if (p.size < 3 || p.size > 4000) return p
        repeat(rounds) {
            val out = ArrayList<LatLng>(p.size * 2)
            out += p.first()
            for (i in 0 until p.size - 1) {
                val a = p[i]; val b = p[i + 1]
                out += LatLng(a.lat * 0.75 + b.lat * 0.25, a.lng * 0.75 + b.lng * 0.25)
                out += LatLng(a.lat * 0.25 + b.lat * 0.75, a.lng * 0.25 + b.lng * 0.75)
            }
            out += p.last()
            p = out
        }
        return p
    }

    /**
     * 화면 아래 [coverFrac] 만큼을 패널이 가릴 때, 점들이 그 위쪽 지도에 들어오도록 맞춤용 점을 하나 더함:
     * 가장 남쪽 점보다 (남북 폭 × 가린 비율 / 보이는 비율)만큼 남쪽. 점이 2개 미만이거나 비율이 이상하면 그대로.
     */
    fun withRoomBelow(points: List<LatLng>, coverFrac: Double): List<LatLng> {
        if (points.size < 2 || coverFrac <= 0.0 || coverFrac >= 0.95) return points
        val minLat = points.minOf { it.lat }
        val span = (points.maxOf { it.lat } - minLat).coerceAtLeast(0.002)    // 동서로 긴 노선도 아래로 조금은 띄움
        val midLng = (points.minOf { it.lng } + points.maxOf { it.lng }) / 2
        return points + LatLng(minLat - span * coverFrac / (1 - coverFrac), midLng)
    }

    /** 정류장 중 선에서 maxM 안에 있는 것의 비율 (0~1) */
    fun coverage(path: List<LatLng>, stops: List<LatLng>, maxM: Double): Double {
        if (path.size < 2 || stops.isEmpty()) return 0.0
        return stops.count { s -> project(path, s).second <= maxM }.toDouble() / stops.size
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
