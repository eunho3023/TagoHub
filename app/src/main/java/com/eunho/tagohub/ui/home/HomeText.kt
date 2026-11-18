package com.eunho.tagohub.ui.home

import com.eunho.tagohub.data.BusArrival

/** 정류장에 서는 노선 하나: 번호·종류와 가장 빨리 오는 버스의 남은 시간 (모르면 null) */
data class RouteEta(val routeNo: String, val routeType: String?, val text: String?, val seconds: Int?)

/** 홈 화면의 글 만들기 (순수 함수라 테스트할 수 있음) */
object HomeText {

    /** 정류장에 도착 정보가 있는 노선을 하나씩, 가장 빨리 오는 순으로 (도착 시각을 모르는 노선은 뒤에) */
    fun routeEtas(arrivals: List<BusArrival>): List<RouteEta> =
        arrivals.groupBy { it.routeNo to it.routeType }.map { (k, list) ->
            val sec = list.mapNotNull { it.arrSeconds }.minOrNull()
            RouteEta(k.first, k.second, sec?.let(::shortEta), sec)
        }.sortedWith(compareBy({ it.seconds ?: Int.MAX_VALUE }, { it.routeNo }))

    /**
     * 저장해 둔 도착을 지난 시간만큼 당겨서: elapsed 초가 지났으면 남은 시간에서 빼고, 이미 지나갔을 버스는 뺌.
     * elapsed 가 null 이면(너무 오래됨) 시간은 지우고 노선만 남김. 시간을 모르던 노선은 그대로
     */
    fun aged(routes: List<RouteEta>, elapsed: Int?): List<RouteEta> =
        if (elapsed == null) routes.map { it.copy(text = null, seconds = null) }
        else routes.mapNotNull { r ->
            val s = r.seconds ?: return@mapNotNull r
            val left = s - elapsed
            if (left <= 0) null else r.copy(text = shortEta(left), seconds = left)
        }.sortedWith(compareBy({ it.seconds ?: Int.MAX_VALUE }, { it.routeNo }))

    /** 30초 이하는 "곧", 그 밖은 올림한 분 ("1분", "3분") — 홈 화면은 짧게 */
    fun shortEta(seconds: Int): String = if (seconds <= 30) "곧" else "${(seconds + 59) / 60}분"
}
