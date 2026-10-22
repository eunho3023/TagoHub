package com.eunho.tagohub.data

import java.time.Duration
import java.time.LocalDateTime

/** 환승 1회 경로: 첫 열차로 [hub] 까지 가서 [waitMinutes] 분 기다렸다가 둘째 열차로 도착역까지 */
data class TrainTransfer(val hub: String, val waitMinutes: Int, val legs: List<Trip>) {
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
        "울산(통도사)", "부산", "밀양", "마산", "익산", "정읍", "광주송정", "목포", "순천", "여수EXPO", "행신", "청량리", "원주", "제천", "영주", "강릉",
    )

    /** 갈아타는 데 필요한 최소 시간(분) */
    const val MIN_WAIT_MIN = 10

    /** 첫 열차마다, 환승 시간을 지키면서 가장 빨리 도착하는 둘째 열차 하나를 짝지음 */
    fun combine(first: List<Trip>, second: List<Trip>, hub: String, minWait: Int = MIN_WAIT_MIN): List<TrainTransfer> {
        val seconds = second.filter { it.dep != null && it.arr != null }
        return first.mapNotNull { a ->
            val aArr = a.arr ?: return@mapNotNull null
            val b = seconds
                .filter { !it.dep!!.isBefore(aArr.plusMinutes(minWait.toLong())) }
                .minByOrNull { it.arr!! } ?: return@mapNotNull null
            TrainTransfer(hub, Duration.between(aArr, b.dep).toMinutes().toInt(), listOf(a, b))
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

/** 열차별 정차역 (순수 계산): 출발역에서 각 역까지의 직통 시간표에 같은 열차번호가 나오는 역 = 정차역 */
object TrainStops {
    /** [scan] 은 열차번호 → (그 역에 도착하는 시각, 역 이름) 목록 */
    fun between(scan: Map<String, List<Pair<LocalDateTime, String>>>, trip: Trip): List<StopTime> {
        val dep = trip.dep
        val arr = trip.arr
        val mid = if (dep == null || arr == null) emptyList() else
            scan[trip.number].orEmpty().filter { it.first.isAfter(dep) && it.first.isBefore(arr) }.sortedBy { it.first }
        return buildList {
            add(StopTime(trip.depName, dep))
            mid.distinctBy { it.second }.filter { it.second != trip.depName && it.second != trip.arrName }.forEach { add(StopTime(it.second, it.first)) }
            add(StopTime(trip.arrName, arr))
        }
    }
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
        listOf("서울"), listOf("세종"), listOf("부산"), listOf("대구"), listOf("인천"), listOf("광주"), listOf("대전"), listOf("울산"),
        listOf("경기"), listOf("강원"), listOf("충청북", "충북"), listOf("충청남", "충남"), listOf("전북", "전라북"), listOf("전라남", "전남"),
        listOf("경상북", "경북"), listOf("경상남", "경남"), listOf("제주"),
    )

    private fun rank(region: String): Int =
        REGIONS.indexOfFirst { names -> names.any { region.startsWith(it) } }.let { if (it < 0) REGIONS.size else it }

    fun byRegion(list: List<Place>): List<Pair<String, List<Place>>> =
        list.groupBy { it.sub?.takeIf(String::isNotBlank) ?: "기타" }
            .toList().sortedWith(compareBy({ rank(it.first) }, { it.first }))
            .map { (k, v) -> k to v.sortedBy { it.name } }
}
