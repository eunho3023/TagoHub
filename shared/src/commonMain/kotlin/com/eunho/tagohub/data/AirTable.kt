package com.eunho.tagohub.data

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus

/**
 * 항공 운항 시간표 파일(air_schedule.tsv: 국내/국제, 항공사, 편명, 출발, 도착, 출발시각, 도착시각, 요일, 시작일, 끝일) 계산 (폰·웹·PC 공용).
 * 폰 앱 AirSchedule 과 같은 규칙: 그날 운항편, 파일 기간이 지난 날짜는 마지막 일주일 운항으로 "예상".
 */
object AirTable {
    data class Row(
        val intl: Boolean, val airline: String, val flight: String, val dep: String, val arr: String,
        val depMin: Int, val arrMin: Int, val days: Set<Int>, val start: LocalDate, val end: LocalDate,
    )

    /** 그날 한 편 (하루 중 분, 도착이 다음 날이면 1440 넘음) */
    data class Flight(val airline: String, val flight: String, val depMin: Int, val arrMin: Int)

    data class Result(val flights: List<Flight>, val projected: Boolean)

    private const val DAY_CHARS = "월화수목금토일"
    private const val TAIL_DAYS = 7

    private fun minute(t: String): Int? {
        val p = t.split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    fun parse(text: String): List<Row> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 10) return@mapNotNull null
        runCatching {
            Row(
                intl = f[0] == "I", airline = f[1], flight = f[2], dep = f[3], arr = f[4],
                depMin = minute(f[5]) ?: return@mapNotNull null, arrMin = minute(f[6]) ?: return@mapNotNull null,
                days = f[7].mapNotNull { c -> DAY_CHARS.indexOf(c).takeIf { it >= 0 }?.plus(1) }.toSet(),
                start = LocalDate.parse(f[8].trim()), end = LocalDate.parse(f[9].trim()),
            )
        }.getOrNull()
    }.toList()

    /** 탭으로 나눈 "코드 이름" 목록 */
    fun parseNames(text: String): Map<String, String> = text.lineSequence().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size >= 2 && f[0].isNotBlank()) f[0].trim() to f[1].trim() else null
    }.toMap()

    /** [dep] → [arr] (공항 코드) 그날 운항편, 출발 시각순 */
    fun flights(rows: List<Row>, dep: String, arr: String, date: LocalDate): Result {
        val pair = rows.filter { it.dep == dep && it.arr == arr }
        if (pair.isEmpty()) return Result(emptyList(), false)
        val iso = date.dayOfWeek.isoDayNumber
        val exact = pair.filter { date >= it.start && date <= it.end && iso in it.days }
        val lastEnd = rows.maxOf { it.end }
        val (picked, projected) = when {
            exact.isNotEmpty() -> exact to false
            date > lastEnd -> pair.filter { it.end >= lastEnd.minus(DatePeriod(days = TAIL_DAYS)) && iso in it.days } to true
            else -> emptyList<Row>() to false
        }
        val list = picked.distinctBy { it.flight to it.depMin }.sortedBy { it.depMin }.map { r ->
            Flight(r.airline, r.flight, r.depMin, if (r.arrMin < r.depMin) r.arrMin + 1440 else r.arrMin)
        }
        return Result(list, projected && list.isNotEmpty())
    }

    /** 국내선 공항 코드 (많이 쓰는 순) */
    fun domesticAirports(rows: List<Row>): List<String> {
        val codes = rows.filter { !it.intl }.flatMap { listOf(it.dep, it.arr) }.toSet()
        return codes.sortedBy { c -> PREFERRED.indexOf(c).let { if (it < 0) PREFERRED.size else it } }
    }

    private val PREFERRED = listOf("GMP", "CJU", "PUS", "TAE", "CJJ", "KWJ", "MWX", "YNY", "USN", "RSU", "HIN", "KPO", "KUV", "WJU", "ICN")
}
