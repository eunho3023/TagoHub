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

    /** 위치상 가장 가까운 버스가 도착정보의 첫 버스보다 이만큼(정류장) 이상 더 가까우면 무조건 도착정보에 빠진 버스로 봄 */
    const val NEARER_GAP = 3

    /** 정류장 [stopIndex] 앞쪽에 달리는 모든 버스까지의 정류장 수 (가까운 순). 0 = 이미 와 있음 */
    fun allAway(stopIndex: Int, busIndexes: List<Int>): List<Int> =
        busIndexes.filter { it in 0..stopIndex }.map { stopIndex - it }.sorted()

    /**
     * 버스 위치([aways], 가까운 순)와 도착정보([apiPrevStops] 정류장 전) 중 어느 쪽을 보일지: 위치가 더 믿을 만한 경우 true.
     * 도착 예측 API 는 일부 버스만 주기도 하고 위치보다 늦게 갱신돼서:
     *  - 이미 와 있거나(0), API 값이 없거나, 3정류장 이상 더 가까운 버스가 보이면 위치를 따른다.
     *  - 그보다 가까운 버스가 있어도(1~2정류장) API 의 버스와 같은 거리에 따로 버스가 또 보이면, API 의 버스는 그 버스이고
     *    더 가까운 버스는 API 가 빠뜨린 것이라 위치를 따른다 (2번 노선: API 5정류장 전, 위치엔 3정류장 전과 5정류장 전 버스가 있음).
     *  - 위치에 그런 버스가 없으면 같은 버스를 두 자료가 약간 다르게 센 것이라 도착정보를 그대로 둔다.
     */
    fun preferPosition(apiPrevStops: Int?, aways: List<Int>): Boolean {
        val nearest = aways.minOrNull() ?: return false
        if (apiPrevStops == null || nearest == 0 || nearest <= apiPrevStops - NEARER_GAP) return true
        return nearest < apiPrevStops && aways.any { it != nearest && kotlin.math.abs(it - apiPrevStops) <= 1 }
    }

    fun text(stopsAway: Int, minutes: Int?): String = when {
        stopsAway <= 0 -> "정류장 도착"
        minutes != null -> "${stopsAway}정류장 전 · 약 ${minutes.coerceAtLeast(1)}분"
        else -> "${stopsAway}정류장 전"
    }
}
