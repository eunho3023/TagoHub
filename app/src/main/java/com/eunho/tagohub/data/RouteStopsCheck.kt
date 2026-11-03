package com.eunho.tagohub.data

/** 앱 안 노선 정류장 순서가 온전한지 (데이터를 만들 때 서버가 일부만 줬으면 순번이 군데군데 빔) */
object RouteStopsCheck {
    /** 이보다 큰 순번 차이는 빠진 게 아니라 방향마다 따로 매긴 번호(대구 1001…·2001…)로 봄 */
    private const val MAX_GAP = 30

    /**
     * 이웃한 순번 사이에 빠진 번호가 2개 이상이고 전체의 10% 이상이면 빠진 것.
     * 0부터 시작하거나 방향마다 1001·2001 로 매기는 노선은 그대로 둠.
     */
    fun incomplete(ords: List<Int>): Boolean {
        val q = ords.sorted()
        val missing = q.zipWithNext { a, b -> b - a - 1 }.filter { it in 1 until MAX_GAP }.sum()
        return missing >= 2 && missing >= q.size * 0.1
    }
}
