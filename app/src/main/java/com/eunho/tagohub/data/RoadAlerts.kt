package com.eunho.tagohub.data

import kotlin.math.abs
import kotlin.math.max

/** 경로 안내 중 미리 알려 줄 도로 시설의 종류 */
enum class RoadAlertKind(val label: String, val announceM: Double, val maxOffsetM: Double) {
    Ice("상습 결빙 구간", 1500.0, 120.0),
    Toll("톨게이트", 2000.0, 150.0),
    Vms("전광판", 700.0, 60.0),
}

/** 경로 위의 시설 하나: [along] 은 출발점부터의 거리(m) */
class RoadAlert(val kind: RoadAlertKind, val name: String, val detail: String?, val along: Double, val id: String)

/** 앞으로 만날 시설: 이름, 거리, 지금 말할 문구(없으면 null) */
data class RoadAlertStatus(val kind: RoadAlertKind, val name: String, val detail: String?, val distance: Double, val speak: String?)

/**
 * 경로 위의 상습 결빙구간·톨게이트·전광판을 찾아 경로 순서로 놓는다 (지도 레이어와 같은 자료).
 * 톨게이트·전광판은 반대 차로 쪽(진행 방향의 왼쪽)에 있는 것은 우리 쪽이 아니므로 뺀다.
 * 같은 시설이 양방향으로 겹쳐 있으면 [MERGE_M] 안에서 하나로 합친다.
 */
object RoadAlerts {
    private const val MAX_LEFT_M = 15.0
    private const val MERGE_M = 300.0

    fun match(route: NavRoute, kind: RoadAlertKind, features: List<ItsRoadFeature>): List<RoadAlert> {
        val found = features.mapNotNull { f ->
            val p = route.snap(f.lat, f.lng, kind.maxOffsetM, pad = 0.003) ?: return@mapNotNull null
            if (kind != RoadAlertKind.Ice && p.side > MAX_LEFT_M) return@mapNotNull null
            RoadAlert(kind, name(kind, f), detail(kind, f), p.along, f.id)
        }.sortedBy { it.along }
        val out = ArrayList<RoadAlert>()
        for (a in found) {
            val dup = out.lastOrNull { abs(it.along - a.along) < MERGE_M && (kind != RoadAlertKind.Toll || it.name == a.name) }
            if (dup == null) out += a
        }
        return out
    }

    private fun name(kind: RoadAlertKind, f: ItsRoadFeature): String = when (kind) {
        RoadAlertKind.Ice -> "상습 결빙 구간"
        RoadAlertKind.Toll -> f.title.trim().let { if (it.endsWith("톨게이트") || it.endsWith("영업소")) it else "$it 톨게이트" }
        RoadAlertKind.Vms -> "전광판"
    }

    /** 전광판에 뜬 문구 같은 덧붙임 (없으면 null) */
    private fun detail(kind: RoadAlertKind, f: ItsRoadFeature): String? =
        if (kind == RoadAlertKind.Vms) f.details.trim().takeIf { it.isNotEmpty() && it != f.title }?.take(60) else null
}

/** 달리면서 가장 가까운 시설을 알려줌. 시설마다 한 번만 말한다 */
class RoadAlertTracker(alerts: List<RoadAlert>) {
    private val sorted = alerts.sortedBy { it.along }
    private val said = HashSet<String>()

    fun update(progress: Double): RoadAlertStatus? {
        val next = sorted.firstOrNull { it.along >= progress - PASSED_M && max(0.0, it.along - progress) <= it.kind.announceM } ?: return null
        val dist = max(0.0, next.along - progress)
        val key = "${next.kind}:${next.id}:${next.along.toInt()}"
        val speak = if (said.add(key)) "${NavText.distance(dist)} 앞 ${next.name}입니다." else null
        return RoadAlertStatus(next.kind, next.name, next.detail, dist, speak)
    }

    private companion object {
        const val PASSED_M = 50.0
    }
}
