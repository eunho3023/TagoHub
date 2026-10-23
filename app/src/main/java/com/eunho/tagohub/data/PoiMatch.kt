package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/**
 * 카카오 지도 POI(정류장·역·장소)를 앱 데이터에 맞추기.
 * 카카오 POI 는 이름과 좌표만 주므로, 가까운 정류장·역 중 이름이 비슷한 것을 고른다.
 */
object PoiMatch {
    /** 공백·문장부호·괄호 안·끝의 '역'·'정류장'·호선 표기를 빼고 비교 */
    /** "역" 뒤에 붙는 노선·출구 표기: "강남역 신분당선", "신촌역 2번출구", "서울역 1호선 경부선" (장소 이름 "서울역 롯데마트" 는 아님) */
    private val LINE_OR_EXIT = Regex("""^(\d+\s*호선|\d+\s*번\s*출구|출구.*|[가-힣A-Za-z0-9]*선|[가-힣]*철도|[가-힣]*경전철|GTX-?[A-Ca-c]|신분당|분당)(\s+.*)?$""")

    /** 지도 POI 는 "강남역 신분당선"처럼 역 이름 뒤에 노선·출구가 붙기도 해서, 그런 꼬리는 떼고 역 이름만 남김 */
    private fun stripLineSuffix(s: String): String {
        val t = s.trim()
        val i = t.indexOfFirst { it.isWhitespace() }
        if (i <= 0) return t
        val first = t.substring(0, i)
        return if (first.endsWith("역") && LINE_OR_EXIT.matches(t.substring(i).trim())) first else t
    }

    fun norm(s: String): String = stripLineSuffix(s)
        .replace(Regex("\\(.*?\\)"), "")
        .replace(Regex("\\d+호선"), "")
        .replace(Regex("[\\s.·,\\-_]"), "")
        .removeSuffix("정류장").removeSuffix("정류소").removeSuffix("역")

    fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // 포함 관계는 짧은 쪽이 3글자 이상일 때만 ('서울' ⊂ '서울대입구' 같은 오인 방지)
        return x == y || (minOf(x.length, y.length) >= 3 && (x.contains(y) || y.contains(x)))
    }

    /** 가까운 후보(이름, 위도, 경도) 중: 이름이 비슷한 가장 가까운 것 → 없으면 nearM 안의 가장 가까운 것 */
    fun <T> pick(name: String, lat: Double, lng: Double, cands: List<T>, nameOf: (T) -> String, posOf: (T) -> Pair<Double, Double>?, nearM: Double): T? {
        val withD = cands.mapNotNull { c -> posOf(c)?.let { (la, ln) -> c to Geo.meters(lat, lng, la, ln) } }.sortedBy { it.second }
        return withD.firstOrNull { similar(name, nameOf(it.first)) }?.first ?: withD.firstOrNull { it.second <= nearM }?.first
    }
}
