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
        // 진행 방향의 왼쪽이면 양수: (선분 방향) x (점까지) 의 부호
        val side = if (len2 > 0) (bx * py - by * px) / kotlin.math.sqrt(len2) else 0.0
        return Projection(i, dist, cum[i] + t * (cum[i + 1] - cum[i]), side)
    }

    /** 경로 위 [meters] 미터 지점의 좌표(범위 밖이면 양 끝으로 맞춤) */
    fun pointAt(meters: Double): LatLng {
        if (points.isEmpty()) return LatLng(0.0, 0.0)
        if (points.size == 1) return points[0]
        val m = meters.coerceIn(0.0, length)
        val seg = segmentAt(m)
        val a = points[seg]
        val b = points[seg + 1]
        val len = cum[seg + 1] - cum[seg]
        val t = if (len > 0) ((m - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
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

    /** 안내 중 실제로 알려줄 동작(회전·입출구·톨게이트·목적지)의 번호. 교차로 직진 등은 뺀다 */
    val maneuvers: List<Int> = guides.indices.filter { TurnKind.isManeuverGuide(guides[it]) }

    /** [progress] 미터 지점부터 목적지까지 소통 상태를 비율로 나눈 구간 (이어지는 같은 상태는 합침) */
    fun trafficSpans(progress: Double): List<TrafficSpan> {
        val remain = length - progress
        if (roads.isEmpty() || remain <= 0.0) return emptyList()
        val out = ArrayList<TrafficSpan>()
        for (i in roads.indices) {
            if (roadEnd[i] <= progress) continue
            val a = ((max(roadStart[i], progress) - progress) / remain).toFloat().coerceIn(0f, 1f)
            val b = ((roadEnd[i] - progress) / remain).toFloat().coerceIn(0f, 1f)
            if (b <= a) continue
            val state = roads[i].trafficState
            val last = out.lastOrNull()
            if (last != null && last.state == state) out[out.size - 1] = TrafficSpan(last.from, b, state)
            else out += TrafficSpan(a, b, state)
        }
        return out
    }

    /**
     * [progress] 미터 지점 이후에 남은 도로 구간들. 지나온 도로는 빼고, 지금 달리는 도로는 현재 위치부터로 잘라
     * 지도에서 지나간 경로가 지워지게 한다.
     */
    fun remainingRoads(progress: Double): List<DriveRoad> {
        val out = ArrayList<DriveRoad>()
        for (i in roads.indices) {
            if (roadEnd[i] <= progress) continue
            val r = roads[i]
            if (roadStart[i] >= progress || r.points.size < 2) { out += r; continue }
            // 지금 달리는 도로: 점마다 도로 안의 위치를 구해 지나온 점을 자름
            val along = DoubleArray(r.points.size)
            for (k in 1 until r.points.size) along[k] = along[k - 1] + Geo.meters(r.points[k - 1].lat, r.points[k - 1].lng, r.points[k].lat, r.points[k].lng)
            val total = along.last().takeIf { it > 0 } ?: 1.0
            val span = roadEnd[i] - roadStart[i]
            val kept = ArrayList<LatLng>()
            kept += pointAt(progress)
            for (k in r.points.indices) if (roadStart[i] + along[k] / total * span > progress) kept += r.points[k]
            if (kept.size < 2) kept += r.points.last()
            out += r.copy(points = kept)
        }
        return out
    }

    /** [progress] 이후 남은 경로선 (도로 구간 정보가 없을 때 지나온 부분을 지우는 데 씀) */
    fun remainingPoints(progress: Double): List<LatLng> {
        if (points.size < 2) return points
        val seg = segmentAt(progress)
        return listOf(pointAt(progress)) + points.subList(seg + 1, points.size)
    }

    /** [side]: 경로 진행 방향의 왼쪽으로 떨어져 있으면 +, 오른쪽이면 - (미터) */
    data class Projection(val segment: Int, val distance: Double, val along: Double, val side: Double = 0.0)

    companion object {
        fun from(r: KakaoDriveRoute) = NavRoute(r.points, r.guides, r.roads, r.distanceMeters, r.durationSeconds)
    }
}

/**
 * 회전(안내) 종류. 카카오 응답의 종류 코드는 일부만 확인되어(1 좌, 2 우, 12 고속도로 입구, 29 교차로 직진 …),
 * 모든 안내가 갖고 있는 한국어 문구로 판별한다 ("좌회전", "오른쪽에 고속도로 입구", "12시 방향" 등).
 */
enum class TurnKind {
    Start, Destination, Waypoint, Left, Right, UTurn, SlightLeft, SlightRight, Straight, HighwayEnter, HighwayExit, Toll;

    /** 안내할 만한 "동작"인지: 교차로를 직진으로 지나가는 것은 안내하지 않는다(내비 앱도 다음 회전만 보여줌) */
    val isManeuver: Boolean get() = this != Straight && this != Start

    companion object {
        private val clock = Regex("""(\d{1,2})\s*시\s*방향""")

        fun of(g: DriveGuide): TurnKind {
            if (g.isStart) return Start
            if (g.isDestination) return Destination
            if (g.isWaypoint) return Waypoint
            val t = g.guidance
            return when {
                "유턴" in t || "U턴" in t || "u턴" in t -> UTurn
                "고속도로 입구" in t -> HighwayEnter
                "고속도로 출구" in t -> HighwayExit
                "톨게이트" in t -> Toll
                "좌회전" in t -> Left
                "우회전" in t -> Right
                else -> clock.find(t)?.let { m -> fromClock(m.groupValues[1].toIntOrNull() ?: 12) } ?: Straight
            }
        }

        /** "N시 방향" → 12시 직진, 10·11시 왼쪽으로 약간, 7~9시 왼쪽, 1·2시 오른쪽으로 약간, 3~5시 오른쪽, 6시 되돌아감 */
        private fun fromClock(h: Int): TurnKind = when (h) {
            12 -> Straight
            10, 11 -> SlightLeft
            7, 8, 9 -> Left
            1, 2 -> SlightRight
            3, 4, 5 -> Right
            6 -> UTurn
            else -> Straight
        }

        /** 회전교차로 안내인지. 카카오는 "회전교차로에서 오른쪽 3시 방향"처럼 나가는 방향을 시계로 알려 줌(종류 70~81) */
        fun isRoundabout(g: DriveGuide): Boolean = "회전교차로" in g.guidance || "로터리" in g.guidance

        /** 안내할 동작인지: 회전교차로는 곧장 지나가도(12시 방향) 안내함 */
        fun isManeuverGuide(g: DriveGuide): Boolean = of(g).isManeuver || isRoundabout(g)

        private val towards = Regex("^(.+?) *방면으로")

        private fun roundaboutDirection(g: DriveGuide): String = when (of(g)) {
            Left, SlightLeft -> "왼쪽"
            Right, SlightRight -> "오른쪽"
            UTurn -> "되돌아"
            else -> "직진"
        }

        /**
         * 회전교차로에서는 "좌회전·우회전"이 아니라 나가는 쪽을 말함 ("조치원 방면으로 회전교차로에서 오른쪽으로 나가세요").
         * 카카오의 방향은 들어가는 길 기준으로 나가는 출구 방향이라, 돌아 들어가는 좌·우회전과 헷갈리게 읽지 않도록 함.
         */
        private fun roundaboutText(g: DriveGuide): String {
            val lead = towards.find(g.guidance)?.let { "${it.groupValues[1]} 방면으로 " }.orEmpty()
            val dir = roundaboutDirection(g)
            return lead + "회전교차로에서 " + when (dir) {
                "직진" -> "직진하세요"
                "되돌아" -> "되돌아 나가세요"
                else -> "${dir}으로 나가세요"
            }
        }

        /**
         * 배너에 쓸 문구: 핵심 동작을 앞에 ("양재역 방면으로 좌회전" → "좌회전 · 양재역 방면", "서초IC에서 오른쪽 방향" → "오른쪽 방향 · 서초IC").
         * 음성 안내는 [text] 그대로 읽음.
         */
        fun bannerText(g: DriveGuide): String {
            if (isRoundabout(g)) {
                val where = towards.find(g.guidance)?.let { " · ${it.groupValues[1]} 방면" }.orEmpty()
                return "회전교차로 ${roundaboutDirection(g)}$where"
            }
            val t = text(g)
            Regex("^(.+?) *방면으로 *(.+)$").find(t)?.let { return "${it.groupValues[2]} · ${it.groupValues[1]} 방면" }
            Regex("^(.+?)에서 *(.+)$").find(t)?.let { return "${it.groupValues[2]} · ${it.groupValues[1]}" }
            return t
        }

        /** 말하거나 배너에 쓸 문구: "12시 방향" 같은 시계 표현은 읽기 어려워 풀어 쓴다 */
        fun text(g: DriveGuide): String {
            if (g.isDestination) return "목적지"
            if (g.isWaypoint) return "경유지"
            if (isRoundabout(g)) return roundaboutText(g)
            if (!clock.containsMatchIn(g.guidance)) return g.guidance
            val phrase = when (of(g)) {
                SlightLeft -> "왼쪽 방향"
                SlightRight -> "오른쪽 방향"
                Left -> "좌회전"
                Right -> "우회전"
                UTurn -> "유턴"
                else -> "직진"
            }
            return if (g.name.isNotBlank()) "${g.name}에서 $phrase" else phrase
        }
    }
}

/** 경유지를 지나갔는지: 안내 중 차가 경유지 [PASSED_M] 안에 들어오면 지난 것으로 보고 뺌 (재탐색 때 이미 지난 경유지로 되돌아가지 않게) */
object ViaTracker {
    const val PASSED_M = 80.0

    fun remaining(vias: List<LatLng>, lat: Double, lng: Double): List<LatLng> {
        if (vias.isEmpty()) return vias
        // 순서대로 지나가므로 맨 앞 경유지만 확인 (앞의 것을 지나야 다음 것)
        return if (com.eunho.tagohub.core.Geo.meters(lat, lng, vias.first().lat, vias.first().lng) <= PASSED_M) vias.drop(1) else vias
    }
}

/** 경로 미리보기의 한 단계: 동작과, 그 동작 뒤 다음 동작까지 달리는 거리(m, 마지막은 0) */
data class DriveStep(val kind: TurnKind, val text: String, val distanceAfterMeters: Int)

/** 안내 목록에서 직진 통과를 빼고 출발·회전·입출구·톨게이트·목적지만 남긴다 (거리는 원래 안내 사이 거리를 합산) */
fun driveSteps(guides: List<DriveGuide>): List<DriveStep> {
    val pos = IntArray(guides.size)
    var acc = 0
    for (i in guides.indices) { acc += guides[i].distanceFromPrev; pos[i] = acc }
    val keep = guides.indices.filter { val k = TurnKind.of(guides[it]); TurnKind.isManeuverGuide(guides[it]) || k == TurnKind.Start }
    return keep.mapIndexed { n, i ->
        val g = guides[i]
        val kind = TurnKind.of(g)
        val next = keep.getOrNull(n + 1)
        DriveStep(kind, if (kind == TurnKind.Start) "출발" else TurnKind.text(g), if (next != null) pos[next] - pos[i] else 0)
    }
}

/** 남은 경로의 소통 구간:[from]·[to] 는 남은 거리 중 비율(0 = 지금 위치, 1 = 목적지) */
data class TrafficSpan(val from: Float, val to: Float, val state: Int)

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
    /** 그다음 회전(작은 배너용)과, 다음 회전에서 그곳까지의 거리 */
    val afterGuide: DriveGuide? = null,
    val distanceNextToAfter: Double = 0.0,
)

class NavEngine(private val route: NavRoute) {
    private var segment = 0
    private var progress = 0.0
    private var offCount = 0
    private var offRoute = false
    private var arrived = false
    /** route.maneuvers 안에서 다음에 올 동작의 순번 */
    private var nextManeuver = 0
    private val spoken = HashSet<Long>()
    private var started = false

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

        // 다음 동작 찾기: 이미 지나친 것은 건너뜀 (교차로 직진 같은 안내는 처음부터 목록에 없음)
        val m = route.maneuvers
        while (nextManeuver < m.size - 1 && route.guidePos[m[nextManeuver]] < progress - PASSED_M) nextManeuver++
        val nextIndex = m.getOrNull(nextManeuver)
        val guide = nextIndex?.let { route.guides[it] }
        val distToNext = if (nextIndex != null) max(0.0, route.guidePos[nextIndex] - progress) else 0.0
        val afterIndex = m.getOrNull(nextManeuver + 1)
        val after = afterIndex?.let { route.guides[it] }
        val distNextToAfter = if (nextIndex != null && afterIndex != null) max(0.0, route.guidePos[afterIndex] - route.guidePos[nextIndex]) else 0.0

        if (!offRoute && !arrived && nextIndex != null) {
            val leads = leadsFor(speedMps)
            val reached = leads.filter { distToNext <= it && spoken.add(key(nextIndex, it)) }
            if (reached.isNotEmpty()) say += sentence(nextIndex, afterIndex, distToNext)
        }

        return NavState(
            progressMeters = progress,
            offRoute = offRoute,
            arrived = arrived,
            nextGuide = guide,
            distanceToNext = distToNext,
            afterGuide = after,
            distanceNextToAfter = distNextToAfter,
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

    private fun sentence(index: Int, afterIndex: Int?, distance: Double): String {
        val g = route.guides[index]
        val lead = NavText.distance(distance)
        val soon = distance <= NavText.SOON_M
        if (g.isDestination) return if (soon) "잠시 후 목적지입니다." else "$lead 앞이 목적지입니다."
        if (g.isWaypoint) return if (soon) "잠시 후 경유지입니다." else "$lead 앞이 경유지입니다."
        var text = if (soon) "잠시 후, ${TurnKind.text(g)}" else "$lead 앞, ${TurnKind.text(g)}"
        // 바로 이어지는 동작(200m 안)은 함께 알려줌
        if (afterIndex != null) {
            val nextG = route.guides[afterIndex]
            if (!nextG.isDestination && route.guidePos[afterIndex] - route.guidePos[index] < CHAIN_M) text += ". 이어서 ${TurnKind.text(nextG)}"
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
        if (meters < 975) {
            val m = (meters / 50.0).roundToInt() * 50
            return "${max(m, 50)}미터"
        }
        val km = (meters / 500.0).roundToInt() / 2.0
        return if (abs(km - km.toInt()) < 1e-9) "${km.toInt()}킬로미터" else "${km}킬로미터"
    }

    /** 이 거리 안이면 숫자 대신 "잠시 후"라고 말함 */
    const val SOON_M = 150.0

    /** 앞쪽 지점까지 말하는 거리: "잠시 후" 또는 "300미터 앞" */
    fun ahead(meters: Double): String = if (meters <= SOON_M) "잠시 후" else "${distance(meters)} 앞"

    /** 화면용: "42km", "850m" */
    fun short(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${meters.roundToInt()}m"
}

/**
 * 모의주행: GPS 대신 경로를 따라 가상 위치를 만들어 준다 (실제 안내와 같은 서비스·엔진·음성·알림을 거치게).
 * [speedMps] 는 가상 차량의 속도(엔진의 예고 거리 기준), [factor] 배속이면 같은 시간에 그만큼 더 멀리 간다.
 */
class RouteSimulator(private var route: NavRoute, val speedMps: Double = 27.8) {
    data class Fix(val lat: Double, val lng: Double, val speedMps: Double)

    /** 경로 위에서 간 거리(m) */
    var distance = 0.0
        private set
    var finished = false
        private set

    /** [dtMs] 밀리초가 흘렀다고 보고 위치를 한 번 만든다. [northOffsetM] 는 북쪽으로 벗어난 만큼(이탈 시험) */
    fun step(dtMs: Long, factor: Int, northOffsetM: Double = 0.0): Fix {
        if (!finished) {
            distance += speedMps * factor * dtMs / 1000.0
            if (distance >= route.length) {
                distance = route.length
                finished = true
            }
        }
        val p = route.pointAt(distance)
        return Fix(p.lat + northOffsetM / 110_540.0, p.lng, if (finished) 0.0 else speedMps)
    }

    /** 재탐색으로 새 경로가 오면 거기서 이어서 달림 (새 경로는 현재 위치에서 시작하므로 처음부터) */
    fun replaceRoute(newRoute: NavRoute) {
        route = newRoute
        distance = 0.0
        finished = false
    }
}
