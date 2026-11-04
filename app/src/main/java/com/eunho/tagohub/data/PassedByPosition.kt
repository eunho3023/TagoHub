package com.eunho.tagohub.data

/**
 * 도착 예측이 늦게 갱신될 때 실시간 버스 위치로 바로잡기.
 * 예: 버스는 이미 이 정류장에 와 있거나 지나갔는데 도착 정보는 아직 "3정류장 전 · 3분".
 */
object PassedByPosition {
    /** 이 정류장 앞 이만큼(정류장 수) 안에 버스가 있으면 아직 오는 중 */
    const val APPROACH_STOPS = 6

    /** 이 정류장을 막 지난 것으로 보는 범위(정류장 수) */
    const val JUST_PASSED_STOPS = 2

    enum class Verdict { KEEP, AT_STOP, PASSED }

    /**
     * @param stopOrds 이 노선에서 이 정류장의 순번들 (순환 노선처럼 여러 번 나오면 판단하지 않음)
     * @param busOrds 노선의 운행 중인 버스들의 현재 정류장 순번
     * @param claimedStops 도착 정보가 말하는 남은 정류장 수 (모르면 null)
     * @return 앞쪽에 오는 버스가 없고 이 정류장에 서 있으면 [Verdict.AT_STOP], 막 지났으면 [Verdict.PASSED], 모르면 [Verdict.KEEP]
     */
    fun verdict(stopOrds: List<Int>, busOrds: List<Int>, claimedStops: Int? = null): Verdict {
        val stop = stopOrds.singleOrNull() ?: return Verdict.KEEP
        // 도착 정보가 말하는 자리 근처(조금 더 뒤까지)에 버스가 있으면 그 버스가 오는 중
        val window = maxOf(APPROACH_STOPS, (claimedStops ?: 0) + 2)
        if (busOrds.any { it in (stop - window)..(stop - 1) }) return Verdict.KEEP
        if (busOrds.any { it == stop }) return if ((claimedStops ?: 0) > 0) Verdict.AT_STOP else Verdict.KEEP
        return if (busOrds.any { it in (stop + 1)..(stop + JUST_PASSED_STOPS) }) Verdict.PASSED else Verdict.KEEP
    }

    /** 지나갔다고 확신하면 true (모르면 false → 목록을 그대로 둠) */
    fun passed(stopOrds: List<Int>, busOrds: List<Int>): Boolean = verdict(stopOrds, busOrds) == Verdict.PASSED
}
