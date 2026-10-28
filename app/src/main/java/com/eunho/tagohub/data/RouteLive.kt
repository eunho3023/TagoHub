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

    /** 위치상 가장 가까운 버스가 도착정보의 첫 버스보다 이만큼(정류장) 이상 더 가까우면 도착정보에 빠진 버스로 봄 */
    const val NEARER_GAP = 3

    /**
     * 버스 위치([posAway] 정류장 전)와 도착정보([apiPrevStops] 정류장 전) 중 어느 쪽을 보일지: 위치가 더 믿을 만한 경우 true.
     * 도착 예측 API 는 일부 버스만 주기도 하고 위치보다 늦게 갱신돼서, 이미 와 있거나(0) 훨씬 가까운 버스가 위치에 보이면 위치를 따른다.
     * 한두 정류장 차이는 같은 버스를 두 자료가 약간 다르게 센 것이라 도착정보를 그대로 둔다.
     */
    fun preferPosition(apiPrevStops: Int?, posAway: Int?): Boolean =
        posAway != null && (apiPrevStops == null || posAway == 0 || posAway <= apiPrevStops - NEARER_GAP)

    fun text(stopsAway: Int, minutes: Int?): String = when {
        stopsAway <= 0 -> "정류장 도착"
        minutes != null -> "${stopsAway}정류장 전 · 약 ${minutes.coerceAtLeast(1)}분"
        else -> "${stopsAway}정류장 전"
    }
}
