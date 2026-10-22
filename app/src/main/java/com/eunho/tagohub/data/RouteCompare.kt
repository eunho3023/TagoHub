package com.eunho.tagohub.data

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 안내 중 "더 빠른 경로가 있나" 판단.
 * 새로 받은 추천 경로가 지금 경로와 실제로 다른 길이고, 남은 시간이 충분히 줄어들 때만 알린다.
 * (같은 길이면 정체로 느려진 것이지 대안이 있는 게 아니므로 알리지 않음)
 */
object RouteCompare {
    /** 후보 경로의 이 비율 이상이 지금 경로에서 [FAR_M] 넘게 떨어져 있어야 "다른 길" */
    const val MIN_DIVERGENCE = 0.15
    const val FAR_M = 80.0

    /** 이 정도는 줄어야 알림: 5분 또는 남은 시간의 8% 중 큰 쪽 */
    const val MIN_SAVED_SEC = 300
    const val MIN_SAVED_FRACTION = 0.08

    /** 지금 경로를 따라 갈 남은 거리가 이보다 짧으면 알리지 않음(곧 도착) */
    const val MIN_REMAINING_M = 6000.0

    /** 후보 경로 점 중 [current] 경로에서 [FAR_M] 이상 떨어진 점의 비율 (0~1). 점은 최대 [MAX_SAMPLES] 개만 뽑아 봄 */
    fun divergence(current: NavRoute, candidate: List<LatLng>): Double {
        if (candidate.isEmpty() || current.points.size < 2) return 0.0
        val step = max(1, candidate.size / MAX_SAMPLES)
        var total = 0
        var far = 0
        var i = 0
        while (i < candidate.size) {
            total++
            if (current.snap(candidate[i].lat, candidate[i].lng, FAR_M, pad = 0.0012) == null) far++
            i += step
        }
        return far.toDouble() / total
    }

    /** 줄어드는 시간(초). 알릴 만하지 않으면 null */
    fun savedSeconds(remainingSec: Int, candidateSec: Int, divergence: Double): Int? {
        if (divergence < MIN_DIVERGENCE) return null
        val saved = remainingSec - candidateSec
        val need = max(MIN_SAVED_SEC, (remainingSec * MIN_SAVED_FRACTION).roundToInt())
        return saved.takeIf { it >= need }
    }

    private const val MAX_SAMPLES = 200
}
