package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 한국공항공사 국내선 운항 스케줄 (GET https://apis.data.go.kr/B551178/flight-schedule/dom, type=json).
 * 요청: schDate(YYYYMMDD) · schDeptCityCode · schArrvCityCode. 같은 편이 운항 기간별로 여러 행이라
 * 검색 날짜가 domesticStdate~Eddate 안이고 그 요일이 Y 인 것만 쓴다.
 * 국토교통부(TAGO) 국내선 API 는 오늘·내일만 제대로 주므로, 그 밖의 날짜는 이 정기 스케줄로 보여 준다.
 */
object KacDom {
    const val URL = "https://apis.data.go.kr/B551178/flight-schedule/dom"

    data class Flight(
        val airline: String, val num: String,
        val depCode: String, val depCity: String, val arrCode: String, val arrCity: String,
        val dep: LocalTime?, val arr: LocalTime?,
        val start: LocalDate?, val end: LocalDate?, val days: Set<DayOfWeek>, val passenger: Boolean,
    )

    private val DAYS = listOf("Mon" to DayOfWeek.MONDAY, "Tue" to DayOfWeek.TUESDAY, "Wed" to DayOfWeek.WEDNESDAY,
        "Thu" to DayOfWeek.THURSDAY, "Fri" to DayOfWeek.FRIDAY, "Sat" to DayOfWeek.SATURDAY, "Sun" to DayOfWeek.SUNDAY)

    /** 응답 전체 건수 (쪽 넘기기용). 못 읽으면 0 */
    fun totalCount(text: String): Int {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return 0
        return (((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>)?.get("totalCount"))?.toString()?.toIntOrNull() ?: 0
    }

    fun parse(text: String): List<Flight> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val body = ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>) ?: return emptyList()
        val raw = (body["items"] as? Map<*, *>)?.get("item")
        val list = when (raw) { is List<*> -> raw; is Map<*, *> -> listOf(raw); else -> emptyList() }
        fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun time(v: String?): LocalTime? = v?.padStart(4, '0')?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?.let { runCatching { LocalTime.of(it.take(2).toInt() % 24, it.drop(2).toInt()) }.getOrNull() }
        fun date(v: String?): LocalDate? = v?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            Flight(
                airline = m.s("airlineKorean") ?: m.s("airlineEnglish") ?: "",
                num = m.s("domesticNum") ?: return@mapNotNull null,
                depCode = m.s("startcityCode").orEmpty(), depCity = m.s("startcity").orEmpty(),
                arrCode = m.s("arrivalcityCode").orEmpty(), arrCity = m.s("arrivalcity").orEmpty(),
                dep = time(m.s("domesticStartTime")), arr = time(m.s("domesticArrivalTime")),
                start = date(m.s("domesticStdate")), end = date(m.s("domesticEddate")),
                days = DAYS.filter { (k, _) -> m.s("domestic$k") == "Y" }.map { it.second }.toSet(),
                passenger = m.s("flightPurpose")?.contains("여객") != false,
            )
        }
    }

    /** 이 날짜에 다니는 여객편 (운항 기간 안 + 그 요일), 같은 편명·시각은 하나로, 출발 시각 순 */
    fun forDate(list: List<Flight>, date: LocalDate): List<Flight> = list
        .filter { it.passenger && (it.start == null || !date.isBefore(it.start)) && (it.end == null || !date.isAfter(it.end)) && date.dayOfWeek in it.days }
        .distinctBy { it.num + it.dep }
        .sortedBy { it.dep ?: LocalTime.MAX }

    /** 앱 시간표 형식으로. 도착 시각이 출발보다 이르면 다음 날 도착 */
    fun toTrips(list: List<Flight>, date: LocalDate): List<Trip> = list.map { f ->
        val dep = f.dep?.let { LocalDateTime.of(date, it) }
        val arr = f.arr?.let { a -> LocalDateTime.of(date, a).let { t -> if (dep != null && t.isBefore(dep)) t.plusDays(1) else t } }
        Trip(kind = f.airline, number = f.num, depName = f.depCity, arrName = f.arrCity, dep = dep, arr = arr, prices = emptyList())
    }

    /** 공항 이름("김포국제공항", "서울/김포" 등) → 공항 코드. 한국공항공사 스케줄에 쓰는 코드 */
    private val CODES = listOf(
        "김포" to "GMP", "제주" to "CJU", "김해" to "PUS", "부산" to "PUS", "대구" to "TAE", "청주" to "CJJ", "광주" to "KWJ", "울산" to "USN",
        "여수" to "RSU", "사천" to "HIN", "포항" to "KPO", "양양" to "YNY", "원주" to "WJU", "무안" to "MWX", "군산" to "KUV", "인천" to "ICN",
    )

    fun codeOf(airportName: String): String? = CODES.firstOrNull { airportName.contains(it.first) }?.second
}
