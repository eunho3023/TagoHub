package com.eunho.tagohub.data

/**
 * 노선 목록(정류장 줄)에 보여 줄 실시간 도착 문구.
 * 정류장의 도착정보 화면과 같은 말로 보이게, 그 정류장의 도착정보가 있으면 그것을 그대로 쓰고,
 * 없으면 버스 위치로 "몇 정류장 전"을 세고 걸리는 시간을 어림한다(노선 거리 기준이라 막히면 틀림).
 */
object RouteLive {
    /**
     * [stopIndex] 번째 정류장에 가장 가까이 오는 버스(그 정류장 앞쪽, 같은 방향)까지의 정류장 수. 정차 중이면 0, 오는 버스가 없으면 null.
     * [busIndexes] 는 각 버스가 있는 정류장의 목록 안 번호.
     */
    fun stopsAway(stopIndex: Int, busIndexes: List<Int>): Int? =
        busIndexes.filter { it in 0..stopIndex }.maxOrNull()?.let { stopIndex - it }

    fun text(stopsAway: Int, minutes: Int?): String = when {
        stopsAway <= 0 -> "정류장에 정차 중"
        minutes != null -> "${stopsAway}정류장 전 · 약 ${minutes.coerceAtLeast(1)}분"
        else -> "${stopsAway}정류장 전"
    }
}
