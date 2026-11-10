package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo

/**
 * 카카오 지도 POI(정류장·역·장소)를 앱 데이터에 맞추기.
 * 카카오 POI 는 이름과 좌표만 주므로, 가까운 정류장·역 중 이름이 비슷한 것을 고른다.
 */
object PoiMatch {
    /**
     * 지도에서 누른 곳의 이름이 지하철역인지: "강남역", "강남역 2호선", "신촌역 2번출구" 는 역,
     * "강남역.강남역사거리"(점 들어간 정류장 이름)·"서울역 롯데마트"(장소)는 아님
     */
    fun looksLikeStation(name: String): Boolean {
        val n = name.replace(Regex("""\(.*?\)"""), "").trim()
        if (n.contains('.')) return false
        if (n.endsWith("역")) return true
        val m = Regex("""^(.+역)\s+(.+)$""").find(n) ?: return false
        return LINE_OR_EXIT.matches(m.groupValues[2])
    }

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

    /**
     * 정류장 이름은 "전주역.전주역사거리"처럼 점 뒤에 사거리·건물 이름을 덧붙이기도 한다.
     * 점 뒤를 붙여 버리면 "전주역" POI 와 같다고 보지 못해 정류장 대신 주소 카드가 떴으므로,
     * 첫 점 앞까지만 비교한다.
     */
    fun norm(s: String): String = stripLineSuffix(s)
        .substringBefore('.')
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

    /**
     * 전국 정류소 좌표(내장 DB)로 지도 POI 를 정류장에 맞춘다. 좌표는 이름 확인용으로 함께 쓴다.
     * 1) 이름이 **정확히** 같은 후보 중 가장 가까운 것 (이름이 같으면 300m 안이면 인정)
     * 2) 이름이 비슷한 후보 중 가장 가까운 것
     * 3) 이름이 안 맞으면 좌표가 nearM 안으로 매우 가까운 것 (정류장 아이콘은 좌표가 정확할 때만)
     */
    fun <T> pick(name: String, lat: Double, lng: Double, cands: List<T>, nameOf: (T) -> String, posOf: (T) -> Pair<Double, Double>?, nearM: Double): T? {
        val withD = cands.mapNotNull { c -> posOf(c)?.let { (la, ln) -> c to Geo.meters(lat, lng, la, ln) } }.sortedBy { it.second }
        val n = norm(name)
        return (if (n.isEmpty()) null else withD.firstOrNull { norm(nameOf(it.first)) == n }?.first)
            ?: withD.firstOrNull { similar(name, nameOf(it.first)) }?.first
            ?: withD.firstOrNull { it.second <= nearM }?.first
    }
}
