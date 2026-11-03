package com.eunho.tagohub.ui.home

import com.eunho.tagohub.data.BusArrival

/** 자주 가는 곳 한 줄: 가장 먼저 오는 버스 번호·종류와 남은 시간("곧", "3분") */
data class Eta(val routeNo: String?, val routeType: String?, val text: String?)

/** 정류장에 서는 노선 하나: 번호·종류와 가장 빨리 오는 버스의 남은 시간 (모르면 null) */
data class RouteEta(val routeNo: String, val routeType: String?, val text: String?, val seconds: Int?)

/** 홈 화면의 글 만들기 (순수 함수라 테스트할 수 있음) */
object HomeText {
    /** 가장 빨리 오는 버스. 도착 정보가 없으면 모두 null */
    fun firstArrival(arrivals: List<BusArrival>): Eta {
        val a = arrivals.filter { it.arrSeconds != null }.minByOrNull { it.arrSeconds!! } ?: return Eta(null, null, null)
        return Eta(a.routeNo, a.routeType, shortEta(a.arrSeconds!!))
    }

    /** 정류장에 도착 정보가 있는 노선을 하나씩, 가장 빨리 오는 순으로 (도착 시각을 모르는 노선은 뒤에) */
    fun routeEtas(arrivals: List<BusArrival>): List<RouteEta> =
        arrivals.groupBy { it.routeNo to it.routeType }.map { (k, list) ->
            val sec = list.mapNotNull { it.arrSeconds }.minOrNull()
            RouteEta(k.first, k.second, sec?.let(::shortEta), sec)
        }.sortedWith(compareBy({ it.seconds ?: Int.MAX_VALUE }, { it.routeNo }))

    /** 30초 이하는 "곧", 그 밖은 올림한 분 ("1분", "3분") — 홈 화면은 짧게 */
    fun shortEta(seconds: Int): String = if (seconds <= 30) "곧" else "${(seconds + 59) / 60}분"
}
