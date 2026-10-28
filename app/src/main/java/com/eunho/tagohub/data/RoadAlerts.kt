package com.eunho.tagohub.data

import kotlin.math.abs
import kotlin.math.max

/**
 * 경로 안내·자유주행 중 미리 알려 줄 도로 위험·시설의 종류.
 * (예전에는 지도 레이어 버튼으로 켰지만, 이제는 달리는 동안 앞에 있을 때 알려 준다.)
 */
enum class RoadAlertKind(val label: String, val announceM: Double, val maxOffsetM: Double) {
    Ice("상습 결빙 구간", 1500.0, 120.0),
    Risk("도로 위험 예보 구간", 1000.0, 150.0),
    Disaster("재난 정보", 1500.0, 300.0),
    Caution("주의운전 구간", 1000.0, 150.0),
    Safety("교통안전 지점", 500.0, 80.0),
    Toll("톨게이트", 2000.0, 150.0),
    /** 한국도로공사 실시간 돌발(사고·작업·낙하물 등) */
    Incident("고속도로 돌발", 2500.0, 150.0),
    /** 한국도로공사 정체 구간 */
    Congest("고속도로 정체 구간", 3000.0, 250.0),
    Rest("휴게소", 2000.0, 250.0),
}

/** 지도 위의 위험·시설 한 곳 */
class RoadHazard(val kind: RoadAlertKind, val name: String, val detail: String?, val lat: Double, val lng: Double, val id: String)

/** 경로 위의 시설 하나: [along] 은 출발점부터의 거리(m) */
class RoadAlert(val kind: RoadAlertKind, val name: String, val detail: String?, val along: Double, val id: String)

/** 앞으로 만날 시설: 이름, 거리, 지금 말할 문구(없으면 null) */
data class RoadAlertStatus(val kind: RoadAlertKind, val name: String, val detail: String?, val distance: Double, val speak: String?)

/**
 * 경로 위의 위험·시설을 찾아 경로 순서로 놓는다.
 * 톨게이트는 반대 차로 쪽(진행 방향의 왼쪽)에 있는 것은 우리 쪽이 아니므로 뺀다.
 * 같은 종류가 가까이 겹쳐 있으면(양방향 등) [MERGE_M] 안에서 하나로 합친다.
 */
object RoadAlerts {
    private const val MAX_LEFT_M = 15.0
    private const val MERGE_M = 300.0
    private const val PAD = 0.003

    fun match(route: NavRoute, hazards: List<RoadHazard>): List<RoadAlert> {
        val pts = route.points
        if (pts.size < 2 || hazards.isEmpty()) return emptyList()
        // 전국 목록을 모든 선분에 대보지 않도록 경로가 지나는 네모 안의 것만 먼저 고름
        val minLat = pts.minOf { it.lat } - PAD
        val maxLat = pts.maxOf { it.lat } + PAD
        val minLng = pts.minOf { it.lng } - PAD
        val maxLng = pts.maxOf { it.lng } + PAD
        val found = ArrayList<RoadAlert>()
        for (h in hazards) {
            if (h.lat < minLat || h.lat > maxLat || h.lng < minLng || h.lng > maxLng) continue
            val p = route.snap(h.lat, h.lng, h.kind.maxOffsetM, pad = PAD) ?: continue
            if (h.kind == RoadAlertKind.Toll && p.side > MAX_LEFT_M) continue
            found += RoadAlert(h.kind, h.name, h.detail, p.along, h.id)
        }
        found.sortBy { it.along }
        val out = ArrayList<RoadAlert>()
        for (a in found) {
            val dup = out.lastOrNull { it.kind == a.kind && abs(it.along - a.along) < MERGE_M && (a.kind != RoadAlertKind.Toll || it.name == a.name) }
            if (dup == null) out += a
        }
        return out
    }
}

/** 달리면서 가장 가까운 위험·시설을 알려줌. 한 곳당 한 번만 말한다 */
class RoadAlertTracker(alerts: List<RoadAlert>) {
    private val sorted = alerts.sortedBy { it.along }
    private val said = HashSet<String>()

    fun update(progress: Double): RoadAlertStatus? {
        val next = sorted.firstOrNull { it.along >= progress - PASSED_M && max(0.0, it.along - progress) <= it.kind.announceM } ?: return null
        val dist = max(0.0, next.along - progress)
        val key = "${next.kind}:${next.id}:${next.along.toInt()}"
        val speak = if (said.add(key)) "${NavText.ahead(dist)} ${next.name}입니다." else null
        return RoadAlertStatus(next.kind, next.name, next.detail, dist, speak)
    }

    private companion object {
        const val PASSED_M = 50.0
    }
}
