package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 한국공항공사 국제선 운항 스케줄 (GET https://apis.data.go.kr/B551178/flight-schedule/int, type=json).
 * 실제 응답(김포→하네다, 2026-10-15): 같은 편이 운항 기간별로 여러 행(2014·2020·2024… 옛 기간 포함) →
 * 검색 날짜가 internationalStdate~Eddate 안이고 그 요일 Y 인 것만. 시간은 하나뿐:
 * internationalIoType OUT = 기준공항(airportCode) 출발 시각, IN = 기준공항 도착 시각.
 * 범위: 한국공항공사 공항(김포·김해·제주·청주·대구·무안·양양). 인천공항은 인천국제공항공사 API.
 */
object KacIntl {
    const val URL = "https://apis.data.go.kr/B551178/flight-schedule/int"

    data class Flight(
        val airline: String, val num: String, val airportCode: String, val airport: String,
        val cityCode: String, val city: String, val out: Boolean, val time: LocalTime?,
        val start: LocalDate?, val end: LocalDate?, val days: Set<DayOfWeek>, val passenger: Boolean,
    )

    private val DAYS = listOf("Mon" to DayOfWeek.MONDAY, "Tue" to DayOfWeek.TUESDAY, "Wed" to DayOfWeek.WEDNESDAY,
        "Thu" to DayOfWeek.THURSDAY, "Fri" to DayOfWeek.FRIDAY, "Sat" to DayOfWeek.SATURDAY, "Sun" to DayOfWeek.SUNDAY)

    fun parse(text: String): List<Flight> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val body = ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>) ?: return emptyList()
        val raw = (body["items"] as? Map<*, *>)?.get("item")
        val list = when (raw) { is List<*> -> raw; is Map<*, *> -> listOf(raw); else -> emptyList() }
        fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val t = m.s("internationalTime")?.padStart(4, '0')
            Flight(
                airline = m.s("airlineKorean") ?: m.s("airlineEnglish") ?: "",
                num = m.s("internationalNum") ?: return@mapNotNull null,
                airportCode = m.s("airportCode").orEmpty(), airport = m.s("airport").orEmpty(),
                cityCode = m.s("cityCode").orEmpty(), city = m.s("city").orEmpty(),
                out = m.s("internationalIoType") != "IN",
                time = t?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.let { runCatching { LocalTime.of(it.take(2).toInt() % 24, it.drop(2).toInt()) }.getOrNull() },
                start = m.s("internationalStdate")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                end = m.s("internationalEddate")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                days = DAYS.filter { (k, _) -> m.s("international$k") == "Y" }.map { it.second }.toSet(),
                passenger = m.s("flightPurpose")?.contains("여객") != false,
            )
        }
    }

    /** 이 날짜에 다니는 여객편 (운항 기간 안 + 그 요일), 같은 편명·시각은 하나로, 시각 순 */
    fun forDate(list: List<Flight>, date: LocalDate): List<Flight> = list
        .filter { it.passenger && (it.start == null || !date.isBefore(it.start)) && (it.end == null || !date.isAfter(it.end)) && date.dayOfWeek in it.days }
        .distinctBy { it.num + it.out + it.time }
        .sortedBy { it.time ?: LocalTime.MAX }

    /** 앱 시간표 형식으로: OUT 이면 출발 시각, IN 이면 도착 시각만 */
    fun toTrips(list: List<Flight>, date: LocalDate): List<Trip> = list.map { f ->
        val at = f.time?.let { LocalDateTime.of(date, it) }
        val (depName, arrName) = if (f.out) f.airport to f.city else f.city to f.airport
        Trip(kind = f.airline, number = f.num, depName = depName, arrName = arrName,
            dep = if (f.out) at else null, arr = if (f.out) null else at, prices = emptyList())
    }

    /** 한국공항공사 국제선 공항 (출발·도착 중 하나는 이 중 하나여야 함) */
    /** 인천공항 (인천국제공항공사 API 로 따로 조회) */
    val ICN = "ICN" to "인천"

    val KAC = listOf("GMP" to "서울/김포", "PUS" to "부산/김해", "CJU" to "제주", "CJJ" to "청주", "TAE" to "대구", "MWX" to "무안", "YNY" to "양양")

    /** 자주 가는 해외 도시 (도시코드 = 응답의 cityCode) */
    val FOREIGN = listOf(
        "HND" to "도쿄/하네다", "NRT" to "도쿄/나리타", "KIX" to "오사카/간사이", "FUK" to "후쿠오카", "NGO" to "나고야", "CTS" to "삿포로", "OKA" to "오키나와",
        "PVG" to "상하이/푸동", "SHA" to "상하이/훙차오", "PEK" to "베이징/서우두", "PKX" to "베이징/다싱", "TPE" to "타이베이/타오위안", "TSA" to "타이베이/쑹산",
        "HKG" to "홍콩", "MFM" to "마카오", "BKK" to "방콕/수완나품", "DAD" to "다낭", "HAN" to "하노이", "SGN" to "호찌민", "MNL" to "마닐라", "CEB" to "세부",
        "SIN" to "싱가포르", "KUL" to "쿠알라룸푸르", "GUM" to "괌", "SPN" to "사이판", "ULN" to "울란바토르", "VVO" to "블라디보스토크",
    )

    fun places(q: String): List<Place> {
        val key = q.trim()
        return (listOf(ICN) + KAC + FOREIGN).filter { (code, name) -> key.isEmpty() || name.contains(key) || code.equals(key, ignoreCase = true) }
            .map { (code, name) -> Place(code, name, code) }
    }

    fun isKac(code: String) = KAC.any { it.first == code }
}
