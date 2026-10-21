package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 자동차 경로 안내 엔진 (화면·GPS·소리와 무관한 순수 계산).
 *
 * 입력: 경로([NavRoute]) + 현재 위치·속도 → 출력: 경로 위 진행 거리, 이탈 여부, 다음 안내, 남은 거리·시간, 지금 말할 문구.
 * GPS 를 받는 서비스와 소리(TTS)는 이 엔진의 결과만 쓰므로, 경로를 따라 가상으로 달리는 테스트로 검증할 수 있다.
 */
class NavRoute(
    val points: List<LatLng>,
    val guides: List<DriveGuide>,
    val roads: List<DriveRoad>,
    val totalMeters: Int,
    val totalSeconds: Int,
) {
    /** 점마다의 누적 거리(m) */
    val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.meters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
    }
    val length: Double get() = cum.lastOrNull() ?: 0.0

    /** 안내마다 경로 위 위치(m). 안내 거리를 차례로 더한 값을 경로 길이에 맞춰 보정 */
    val guidePos: DoubleArray = run {
        val sum = guides.sumOf { it.distanceFromPrev }.toDouble()
        val scale = if (sum > 0) length / sum else 1.0
        var acc = 0.0
        DoubleArray(guides.size) { i ->
            acc += guides[i].distanceFromPrev
            min(acc * scale, length)
        }
    }

    /** 도로 구간별 시작 위치(m, 경로 길이에 맞춘 값)와 그 뒤 구간들의 소요 시간 합(초) */
    private val roadStart: DoubleArray
    private val roadEnd: DoubleArray
    private val secondsAfter: IntArray

    init {
        val sum = roads.sumOf { it.distance }.toDouble()
        val scale = if (sum > 0) length / sum else 1.0
        roadStart = DoubleArray(roads.size)
        roadEnd = DoubleArray(roads.size)
        var acc = 0.0
        for (i in roads.indices) {
            roadStart[i] = acc
            acc += roads[i].distance * scale
            roadEnd[i] = acc
        }
        secondsAfter = IntArray(roads.size + 1)
        for (i in roads.indices.reversed()) secondsAfter[i] = secondsAfter[i + 1] + roads[i].duration
    }

    /** [progress] 미터 지점에서 목적지까지 남은 시간(초). 도로별 소통 시간이 있으면 그것으로, 없으면 평균 속도로 */
    fun remainingSeconds(progress: Double): Int {
        if (roads.isEmpty() || length <= 0) {
            return if (length > 0) (totalSeconds * ((length - progress) / length)).roundToInt().coerceAtLeast(0) else 0
        }
        var lo = 0
        var hi = roads.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (roadEnd[mid] < progress) lo = mid + 1 else hi = mid
        }
        val r = roads[lo]
        val span = roadEnd[lo] - roadStart[lo]
        val within = if (span > 0) ((roadEnd[lo] - progress) / span).coerceIn(0.0, 1.0) else 0.0
        return (r.duration * within).roundToInt() + secondsAfter[lo + 1]
    }

    /** 점 [i] 에서 [i+1] 사이 선분에 위치(lat,lng)를 내린 결과 */
    fun project(lat: Double, lng: Double, i: Int): Projection {
        val a = points[i]
        val b = points[i + 1]
        val kx = 111_320.0 * cos(Math.toRadians(a.lat))
        val ky = 110_540.0
        val bx = (b.lng - a.lng) * kx
        val by = (b.lat - a.lat) * ky
        val px = (lng - a.lng) * kx
        val py = (lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
        val dist = hypot(px - t * bx, py - t * by)
        return Projection(i, dist, cum[i] + t * (cum[i + 1] - cum[i]))
    }

    /** [meters] 미터 지점이 들어 있는 선분 번호 */
    fun segmentAt(meters: Double): Int {
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= meters) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, max(0, points.size - 2))
    }

    data class Projection(val segment: Int, val distance: Double, val along: Double)

    companion object {
        fun from(r: KakaoDriveRoute) = NavRoute(r.points, r.guides, r.roads, r.distanceMeters, r.durationSeconds)
    }
}

/** 한 번 갱신한 결과 */
data class NavState(
    /** 경로 위에서 출발점부터 온 거리(m) */
    val progressMeters: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** 다음 회전 안내(없으면 null) */
    val nextGuide: DriveGuide?,
    val distanceToNext: Double,
    val remainingMeters: Double,
    val remainingSeconds: Int,
    /** 지금 말하거나 띄울 문구(이미 말한 것은 다시 나오지 않음) */
    val speak: List<String>,
)

class NavEngine(private val route: NavRoute) {
    private var segment = 0
    private var progress = 0.0
    private var offCount = 0
    private var offRoute = false
    private var arrived = false
    private var nextIndex = 0
    private val spoken = HashSet<Long>()
    private var started = false

    init {
        // 출발지 안내(종류 100)는 건너뜀
        while (nextIndex < route.guides.size && route.guides[nextIndex].isStart) nextIndex++
    }

    /**
     * @param speedMps 현재 속도(m/s). 모르면 0 → 도심 기준 예고 거리를 씀
     * @param accuracyM GPS 오차 반경(m). 클수록 이탈 판정을 느슨하게
     */
    fun update(lat: Double, lng: Double, speedMps: Double = 0.0, accuracyM: Double = 0.0): NavState {
        val say = ArrayList<String>()
        if (!started) {
            started = true
            // 처음 한 번만 전체 경로 안에서 가장 가까운 곳을 찾음 (출발점이 아닌 곳에서 시작해도 되게)
            val first = nearest(lat, lng, 0, route.points.size - 2)
            if (first != null && first.distance < 150.0) {
                segment = first.segment
                progress = first.along
            }
        }

        val limit = max(OFF_ROUTE_M, accuracyM * 2.0)
        val fromSeg = route.segmentAt(max(0.0, progress - BACK_M))
        // 가까운 앞쪽(300m)부터 찾고, 거기서 못 찾을 때만 넓게(3km) 찾음.
        // 처음부터 넓게 찾으면 인터체인지처럼 경로가 가까이 되돌아오는 곳에서 GPS 가 흔들릴 때
        // 앞쪽 선분을 내 위치로 잘못 잡아 진행 거리가 앞으로 튀고, 그 뒤 진짜 위치가 이탈로 보이게 됨.
        // 한 번 갱신에 앞으로 갈 수 있는 거리를 속도로 제한 (GPS 간격 약 1~2초 기준). 가까이 되돌아오는 램프 쪽으로 튀는 것을 막음
        val maxStep = max(MIN_STEP_M, speedMps * 4.0 + 30.0)
        var best = nearest(lat, lng, fromSeg, route.segmentAt(min(route.length, progress + NEAR_FORWARD_M)), progress + maxStep)
        if (best == null || best.distance > limit) {
            // GPS 가 잠깐 끊겼다가(터널 등) 멀리 앞에서 다시 잡힌 경우
            val wide = nearest(lat, lng, fromSeg, route.segmentAt(min(route.length, progress + FORWARD_M)))
            if (wide != null && wide.distance <= limit && (best == null || wide.distance < best.distance)) best = wide
        }

        if (best != null && best.distance <= limit) {
            // 뒤로 조금 흔들리는 것은 무시하고 앞으로만 나아감
            progress = max(progress, best.along)
            segment = best.segment
            offCount = 0
            offRoute = false
        } else {
            offCount++
            if (offCount >= OFF_COUNT && !offRoute) {
                offRoute = true
                say += "경로를 벗어났습니다. 경로를 다시 찾습니다."
            }
        }

        val remaining = max(0.0, route.length - progress)
        if (!arrived && remaining <= ARRIVE_M && !offRoute) {
            arrived = true
            say += "목적지 부근입니다. 안내를 종료합니다."
        }

        // 다음 안내 지점 찾기: 이미 지나친 안내는 건너뜀
        while (nextIndex < route.guides.size - 1 && route.guidePos[nextIndex] < progress - PASSED_M) nextIndex++
        val guide = route.guides.getOrNull(nextIndex)
        val distToNext = if (guide != null) max(0.0, route.guidePos[nextIndex] - progress) else 0.0

        if (!offRoute && !arrived && guide != null) {
            val leads = leadsFor(speedMps)
            val reached = leads.filter { distToNext <= it && spoken.add(key(nextIndex, it)) }
            if (reached.isNotEmpty()) say += sentence(nextIndex, distToNext)
        }

        return NavState(
            progressMeters = progress,
            offRoute = offRoute,
            arrived = arrived,
            nextGuide = guide,
            distanceToNext = distToNext,
            remainingMeters = remaining,
            remainingSeconds = route.remainingSeconds(progress),
            speak = say,
        )
    }

    /** [from]..[to] 번 선분 중 가장 가까운 곳. [maxAlong] 보다 앞쪽 지점은 후보에서 뺌 */
    private fun nearest(lat: Double, lng: Double, from: Int, to: Int, maxAlong: Double = Double.MAX_VALUE): NavRoute.Projection? {
        if (route.points.size < 2) return null
        var best: NavRoute.Projection? = null
        for (i in from.coerceAtLeast(0)..min(to, route.points.size - 2)) {
            val p = route.project(lat, lng, i)
            if (p.along > maxAlong) continue
            if (best == null || p.distance < best.distance) best = p
        }
        return best
    }

    private fun key(index: Int, lead: Int) = index.toLong() * 100_000L + lead

    /** 속도에 맞춘 예고 거리(m): 빠를수록 일찍. 고속도로 시속 70km 이상 / 간선 40km 이상 / 도심 */
    private fun leadsFor(speedMps: Double): List<Int> {
        val kmh = speedMps * 3.6
        return when {
            kmh >= 70 -> listOf(2000, 1000, 500)
            kmh >= 40 -> listOf(500, 200)
            else -> listOf(300, 100)
        }
    }

    private fun sentence(index: Int, distance: Double): String {
        val g = route.guides[index]
        val lead = NavText.distance(distance)
        if (g.isDestination) return "$lead 앞이 목적지입니다."
        var text = "$lead 앞, ${g.guidance}"
        // 바로 이어지는 안내(200m 안)는 함께 알려줌
        val nextG = route.guides.getOrNull(index + 1)
        if (nextG != null && !nextG.isDestination && route.guidePos[index + 1] - route.guidePos[index] < CHAIN_M) {
            text += ". 이어서 ${nextG.guidance}"
        }
        return text
    }

    private companion object {
        /** 경로에서 이만큼 벗어나면 이탈 후보 (GPS 오차가 크면 더 늘림) */
        const val OFF_ROUTE_M = 45.0
        /** 이탈 후보가 연속 몇 번이면 이탈로 봄 (순간적인 GPS 튐 무시) */
        const val OFF_COUNT = 3
        const val BACK_M = 100.0
        const val NEAR_FORWARD_M = 300.0
        const val MIN_STEP_M = 80.0
        const val FORWARD_M = 3000.0
        const val ARRIVE_M = 40.0
        const val PASSED_M = 15.0
        const val CHAIN_M = 200.0
    }
}

object NavText {
    /** 말하기 좋은 거리: 1000m 미만은 50m 단위, 이상은 0.5km 단위 → "300미터", "1.5킬로미터" */
    fun distance(meters: Double): String {
        if (meters < 1000) {
            val m = (meters / 50.0).roundToInt() * 50
            return "${max(m, 50)}미터"
        }
        val km = (meters / 500.0).roundToInt() / 2.0
        return if (abs(km - km.toInt()) < 1e-9) "${km.toInt()}킬로미터" else "${km}킬로미터"
    }

    /** 화면용: "42km", "850m" */
    fun short(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${meters.roundToInt()}m"
}
