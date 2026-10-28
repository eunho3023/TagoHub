package com.eunho.tagohub.data

import kotlin.math.abs
import kotlin.math.max

/** 경로 위의 휴게소: [along] 은 출발점부터의 거리(m), [offsetM] 은 경로에서 떨어진 거리 */
class RestStop(val name: String, val along: Double, val offsetM: Double)

/**
 * 반대 방향 휴게소는 반대편 차로 쪽(진행 방향의 왼쪽)에 있다. 이 거리보다 왼쪽으로 떨어진 것은 우리 쪽 휴게소가 아니다.
 * (양방향 휴게소가 길 하나를 사이에 두고 마주 보는 경우 이름 방향 괄호보다 위치가 더 믿을 만함)
 */
private const val MAX_LEFT_M = 15.0

/** 앞으로 만날 휴게소의 상태: 이름, 거리, 지금 말할 문구(없으면 null) */
data class RestStatus(val name: String, val distance: Double, val speak: String?)

/**
 * 고속도로 안내 중 "다음 휴게소까지 N km". 한국도로공사 휴게소 위치를 경로에 맞춰 놓는다.
 * 같은 휴게소가 양방향(서울방향·부산방향)으로 따로 있으므로, 이름 뿌리가 같고 가까운 것끼리는 경로에 더 가까운 쪽 하나만 쓴다.
 */
object RestAhead {
    /** 휴게소가 길에서 이 거리 안이어야 이 경로의 휴게소로 봄 (출입로를 길게 빼는 곳이 있어 넉넉히) */
    const val MAX_OFFSET_M = 250.0
    private const val SAME_PLACE_M = 2000.0
    /** 이 거리 안으로 들어오면 한 번 알려줌 */
    const val ANNOUNCE_M = 5000.0
    private const val PASSED_M = 100.0

    /** "서울만남(부산)휴게소" → "서울만남": 방향 괄호와 "휴게소" 를 뗌 */
    fun root(title: String): String = title.replace(Regex("""\(.*?\)"""), "").replace("휴게소", "").trim()

    fun match(route: NavRoute, features: List<ItsRoadFeature>): List<RestStop> {
        val found = features.mapNotNull { f ->
            route.snap(f.lat, f.lng, MAX_OFFSET_M, pad = 0.003)
                ?.takeIf { it.side <= MAX_LEFT_M }
                ?.let { RestStop(f.title.trim(), it.along, it.distance) }
        }.sortedBy { it.along }
        val out = ArrayList<RestStop>()
        for (r in found) {
            val same = out.lastOrNull { root(it.name) == root(r.name) && abs(it.along - r.along) < SAME_PLACE_M }
            if (same == null) out += r
            else if (r.offsetM < same.offsetM) out[out.indexOf(same)] = r
        }
        return out
    }
}

/** 달리면서 다음 휴게소를 알려줌. 한 곳당 한 번만 말한다 */
class RestTracker(private val stops: List<RestStop>) {
    private val said = HashSet<String>()
    private var index = 0

    fun update(progress: Double, remainingM: Double): RestStatus? {
        while (index < stops.size && stops[index].along < progress - 100.0) index++
        val r = stops.getOrNull(index) ?: return null
        val dist = max(0.0, r.along - progress)
        // 목적지가 휴게소 바로 근처면(곧 도착) 말하지 않음
        var speak: String? = null
        val label = RestAhead.root(r.name) + "휴게소"
        if (dist <= RestAhead.ANNOUNCE_M && remainingM - dist > 2000.0 && said.add(r.name)) {
            speak = "${NavText.ahead(dist)} ${label}입니다."
        }
        return RestStatus(label, dist, speak)
    }
}
