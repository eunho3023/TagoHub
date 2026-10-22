package com.eunho.tagohub.ui.home

import com.eunho.tagohub.data.BusArrival

/** 자주 가는 곳 한 줄: 가장 먼저 오는 버스 번호·종류와 남은 시간("곧", "3분") */
data class Eta(val routeNo: String?, val routeType: String?, val text: String?)

/** 홈 화면의 글 만들기 (순수 함수라 테스트할 수 있음) */
object HomeText {
    /** 가장 빨리 오는 버스. 도착 정보가 없으면 모두 null */
    fun firstArrival(arrivals: List<BusArrival>): Eta {
        val a = arrivals.filter { it.arrSeconds != null }.minByOrNull { it.arrSeconds!! } ?: return Eta(null, null, null)
        return Eta(a.routeNo, a.routeType, shortEta(a.arrSeconds!!))
    }

    /** 30초 이하는 "곧", 그 밖은 올림한 분 ("1분", "3분") — 홈 화면은 짧게 */
    fun shortEta(seconds: Int): String = if (seconds <= 30) "곧" else "${(seconds + 59) / 60}분"
}
