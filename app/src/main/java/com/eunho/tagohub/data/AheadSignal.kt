package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import kotlin.math.abs

/** 길 안내 중 앞에 있는 실시간 신호 교차로와 거리 */
data class AheadIntersection(val intersection: SignalIntersection, val distanceM: Double)

/**
 * 길 안내 중 앞 교차로의 신호를 고르는 규칙 (화면과 따로 둬서 시험하기 쉽게).
 *  · 앞 교차로: 진행 방향 ±[CONE_DEG] 안에 있고 [MAX_M] 안에서 가장 가까운 실시간 신호 교차로 ([PASSED_M] 안은 이미 지난 것으로 봄)
 *  · 내가 들어가는 쪽(접근로): 교차로에서 나를 보는 방위를 8방위로 맞춘 것. 신호 자료의 방향(nt 북쪽 …)을
 *    "그쪽에서 교차로로 들어오는 차의 신호"로 본다. 자료에 이 뜻이 적혀 있지 않아 실제 도로에서 확인이 필요하다.
 */
object AheadSignal {
    const val MAX_M = 300.0
    const val PASSED_M = 15.0
    const val CONE_DEG = 35.0

    /** 8방위 코드: 북·북동·동·남동·남·남서·서·북서 (신호 자료의 방향 코드와 같음) */
    private val CODES = listOf("nt", "ne", "et", "se", "st", "sw", "wt", "nw")

    /** 두 방위의 차이(0~180도) */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }

    /** 방위(도) → 8방위 코드 */
    fun code(deg: Double): String = CODES[(((deg % 360.0 + 360.0) % 360.0 + 22.5) / 45.0).toInt() % 8]

    /** 진행 방향([bearing]) 앞의 가장 가까운 교차로. 방향을 모르면 null */
    fun pick(lat: Double, lng: Double, bearing: Double?, candidates: List<SignalIntersection>): AheadIntersection? {
        if (bearing == null) return null
        // 위도·경도로 먼저 넓게 걸러서 거리 계산을 줄임 (약 400m)
        val dLat = 0.0036
        val dLng = 0.0045
        return candidates.asSequence()
            .filter { abs(it.lat - lat) <= dLat && abs(it.lng - lng) <= dLng }
            .map { it to Geo.meters(lat, lng, it.lat, it.lng) }
            .filter { (it, d) -> d in PASSED_M..MAX_M && angleDiff(Geo.bearing(lat, lng, it.lat, it.lng), bearing) <= CONE_DEG }
            .minByOrNull { it.second }
            ?.let { AheadIntersection(it.first, it.second) }
    }

    /** 내가 교차로에 들어가는 쪽의 방향 코드 (교차로에서 나를 보는 방위) */
    fun approach(intersection: SignalIntersection, lat: Double, lng: Double): String =
        code(Geo.bearing(intersection.lat, intersection.lng, lat, lng))

    /** 그 접근로의 직진·좌회전·유턴 신호 (이 순서). 보행자·버스·자전거는 운전과 상관없어 뺌 */
    fun forApproach(phases: List<SignalPhase>, approach: String): List<SignalPhase> {
        val order = listOf("St", "Lt", "Ut")
        return phases.filter { it.direction == approach && it.kind in order }.sortedBy { order.indexOf(it.kind) }
    }
}
