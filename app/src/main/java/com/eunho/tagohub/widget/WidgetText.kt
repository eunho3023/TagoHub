package com.eunho.tagohub.widget

import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.Favorite

/** 홈 화면 위젯에 보일 글 (순수 함수라 테스트할 수 있음) */
object WidgetText {
    const val MAX_STOPS = 3
    const val MAX_ARRIVALS = 2

    /** 위젯에 올릴 즐겨찾기 정류장: 정류장 종류만, 저장된 순서대로 앞에서 [MAX_STOPS] 개 */
    fun pickStops(favorites: List<Favorite>): List<Favorite> =
        favorites.filter { it.kind == Favorite.Kind.Stop }.take(MAX_STOPS)

    /** "123번  3분 20초 · 2정류장 전" 줄 두 개 (곧 도착하는 순). 도착 정보가 없으면 안내 문구 */
    fun arrivalLines(arrivals: List<BusArrival>): String {
        if (arrivals.isEmpty()) return "도착 정보 없음"
        return arrivals
            .sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
            .take(MAX_ARRIVALS)
            .joinToString("\n") { "${it.routeNo}번  ${ArrivalText.line(it)}" }
    }

    fun failed(): String = "불러오지 못했어요"
}
