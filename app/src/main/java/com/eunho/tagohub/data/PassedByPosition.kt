package com.eunho.tagohub.data

/**
 * 노선의 실시간 버스 위치로, 정류장 도착 목록의 첫 버스가 이미 이 정류장을 지나갔는지 확인한다.
 * 도착 예측이 늦게 갱신돼 "1정류장 전 · 45초" 가 남아 있는 경우를 걸러내는 용도.
 */
object PassedByPosition {
    /** 이 정류장 앞 이만큼(정류장 수) 안에 버스가 있으면 아직 오는 중 */
    const val APPROACH_STOPS = 6

    /** 이 정류장을 막 지난 것으로 보는 범위(정류장 수) */
    const val JUST_PASSED_STOPS = 2

    /**
     * @param stopOrds 이 노선에서 이 정류장의 순번들 (순환 노선처럼 여러 번 나오면 판단하지 않음)
     * @param busOrds 노선의 운행 중인 버스들의 현재 정류장 순번
     * @return 지나갔다고 확신하면 true, 아니면 false (모르면 false → 목록을 그대로 둠)
     */
    fun passed(stopOrds: List<Int>, busOrds: List<Int>): Boolean {
        val stop = stopOrds.singleOrNull() ?: return false
        if (busOrds.any { it in (stop - APPROACH_STOPS)..stop }) return false   // 오는 중이거나 지금 정류장에 있음
        return busOrds.any { it in (stop + 1)..(stop + JUST_PASSED_STOPS) }
    }
}
