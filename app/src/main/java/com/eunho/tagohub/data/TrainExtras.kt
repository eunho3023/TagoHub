package com.eunho.tagohub.data

import java.time.Duration
import java.time.LocalDateTime

/**
 * 환승 1회 경로: 첫 열차로 [hub] 까지 가서 [waitMinutes] 분 기다렸다가 둘째 열차로 도착역까지.
 * [to] 가 있으면 같은 도시의 다른 역으로 옮겨 탐 (서울역 → 용산역처럼 지하철·택시로, [waitMinutes] 는 옮기는 시간까지)
 */
data class TrainTransfer(val hub: String, val waitMinutes: Int, val legs: List<Trip>, val to: String? = null) {
    val dep: LocalDateTime? get() = legs.first().dep
    val arr: LocalDateTime? get() = legs.last().arr

    /** 두 열차 어른 요금 합계 (한쪽이라도 모르면 모름) */
    val fare: Int?
        get() = legs.map { l -> l.prices.firstOrNull()?.second }.let { f -> if (f.any { it == null }) null else f.sumOf { it!! } }
}

/** 열차 한 편이 서는 역과 도착(출발) 시각 */
data class StopTime(val name: String, val time: LocalDateTime?)

/**
 * 환승 찾기 (순수 계산). 국토교통부 시간표는 "출발역 → 도착역" 직통만 주므로,
 * 노선이 갈라지는 큰 역(환승역)을 하나씩 거치는 두 직통을 이어 붙인다.
 */
object TrainTransfers {
    /** 환승역 후보. 이름이 역 목록에 없으면 건너뜀 */
    val HUBS = listOf(
        "서울", "용산", "영등포", "수원", "평택", "천안아산", "오송", "조치원", "대전", "서대전", "김천구미", "동대구", "신경주",
        "울산", "부산", "밀양", "마산", "익산", "정읍", "광주송정", "목포", "순천", "여수EXPO", "행신", "청량리", "원주", "제천", "영주", "강릉",
    )

    /** 갈아타는 데 필요한 최소 시간(분) */
    const val MIN_WAIT_MIN = 10

    /**
     * 같은 도시의 다른 역: 한 역에 내려 다른 역에서 타는 환승도 찾음 (예: 일요일 강릉 → 목포는 서울역에 내려 용산역에서 탐).
     * 웹 trips.js CITY_STATIONS 와 같음
     */
    val CITY_STATIONS = listOf(
        listOf("서울", "용산", "청량리", "영등포", "상봉", "행신"), listOf("대전", "서대전"), listOf("광주송정", "광주"),
        listOf("부산", "부전"), listOf("동대구", "대구"), listOf("천안아산", "천안"),
    )

    /** 다른 역으로 옮겨 탈 때 필요한 최소 시간(분): 지하철·택시로 옮기는 시간 */
    const val MOVE_MIN = 30

    /** 첫 열차마다, 환승 시간을 지키면서 가장 빨리 도착하는 둘째 열차 하나를 짝지음 */
    fun combine(first: List<Trip>, second: List<Trip>, hub: String, minWait: Int = MIN_WAIT_MIN, to: String? = null): List<TrainTransfer> {
        val seconds = second.filter { it.dep != null && it.arr != null }
        return first.mapNotNull { a ->
            val aArr = a.arr ?: return@mapNotNull null
            val b = seconds
                .filter { !it.dep!!.isBefore(aArr.plusMinutes(minWait.toLong())) }
                .minByOrNull { it.arr!! } ?: return@mapNotNull null
            TrainTransfer(hub, Duration.between(aArr, b.dep).toMinutes().toInt(), listOf(a, b), to)
        }
    }

    /** 같은 첫 열차는 하나만(가장 빨리 도착하는 환승역), 너무 늦게 도착하는 것은 빼고, 출발 순으로. 많으면 하루에 고르게 [limit] 개 */
    fun select(all: List<TrainTransfer>, limit: Int = 15): List<TrainTransfer> {
        val perFirst = all.filter { it.arr != null && it.dep != null }
            .sortedWith(compareBy({ it.arr }, { it.dep }))
            .distinctBy { it.legs.first().number to it.legs.first().dep }
        val best = perFirst.minOfOrNull { it.arr!! } ?: return emptyList()
        val kept = perFirst.filter { !it.arr!!.isAfter(best.plusMinutes(MAX_LATER_MIN)) }.sortedBy { it.dep }
        if (kept.size <= limit) return kept
        val step = kept.size.toDouble() / limit
        return (0 until limit).map { kept[(it * step).toInt()] }
    }

    /** 가장 빠른 도착보다 이만큼(분) 넘게 늦으면 보여주지 않음 */
    private const val MAX_LATER_MIN = 120L
}

/** 역 목록을 가나다순(초성 머리글) 또는 지역별(시·도)로 묶음 */
object PlaceGroups {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val BASE = mapOf('ㄲ' to 'ㄱ', 'ㄸ' to 'ㄷ', 'ㅃ' to 'ㅂ', 'ㅆ' to 'ㅅ', 'ㅉ' to 'ㅈ')

    /** 첫 글자의 초성 (쌍자음은 합침). 한글이 아니면 '#' */
    fun initial(name: String): Char {
        val c = name.firstOrNull() ?: return '#'
        if (c in '가'..'힣') {
            val j = CHO[(c - '가') / 588]
            return BASE[j] ?: j
        }
        return '#'
    }

    fun byInitial(list: List<Place>): List<Pair<String, List<Place>>> =
        list.sortedBy { it.name }.groupBy { initial(it.name) }.toSortedMap(compareBy<Char>({ it == '#' }, { CHO.indexOf(it) })).map { (k, v) -> k.toString() to v }

    private val REGIONS = listOf(
        listOf("서울"), listOf("세종"), listOf("부산"), listOf("대구"), listOf("인천"), listOf("광주", "전남광주"), listOf("대전"), listOf("울산"),
        listOf("경기"), listOf("강원"), listOf("충청북", "충북"), listOf("충청남", "충남"), listOf("전북", "전라북"), listOf("전라남", "전남"),
        listOf("경상북", "경북"), listOf("경상남", "경남"), listOf("제주"),
    )

    private fun rank(region: String): Int =
        REGIONS.indexOfFirst { names -> names.any { region.startsWith(it) } }.let { if (it < 0) REGIONS.size else it }

    /**
     * 화면에 보여줄 시·도 이름은 정식 이름: "강원" → "강원특별자치도", "전북" → "전북특별자치도", "충남" → "충청남도", "인천" → "인천광역시".
     * 광주광역시와 전라남도는 전남광주통합특별시로 합쳐짐 (한국해양교통안전공단 여객선 자료도 이 이름). 모르는 이름은 그대로. 웹 trips.js fullSido 와 같음
     */
    fun fullRegion(name: String): String {
        val t = name.trim()
        return SIDO.firstOrNull { (prefixes, _) -> prefixes.any { t.startsWith(it) } }?.second ?: t
    }

    private val SIDO = listOf(
        listOf("서울") to "서울특별시", listOf("부산") to "부산광역시", listOf("대구") to "대구광역시", listOf("인천") to "인천광역시",
        listOf("광주", "전남광주", "전라남", "전남") to "전남광주통합특별시", listOf("대전") to "대전광역시", listOf("울산") to "울산광역시",
        listOf("세종") to "세종특별자치시", listOf("경기") to "경기도", listOf("강원") to "강원특별자치도", listOf("충청북", "충북") to "충청북도",
        listOf("충청남", "충남") to "충청남도", listOf("전라북", "전북") to "전북특별자치도", listOf("경상북", "경북") to "경상북도",
        listOf("경상남", "경남") to "경상남도", listOf("제주") to "제주특별자치도",
    )

    fun byRegion(list: List<Place>): List<Pair<String, List<Place>>> =
        list.groupBy { it.sub?.takeIf(String::isNotBlank)?.let(::fullRegion) ?: "기타" }
            .toList().sortedWith(compareBy({ rank(it.first) }, { it.first }))
            .map { (k, v) -> k to v.sortedBy { it.name } }
}
