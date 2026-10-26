package com.eunho.tagohub.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 한국공항공사 국내선·국제선 항공기 스케줄(공공데이터포털 파일데이터, assets/air_schedule.tsv; pipeline/build_air_data.py 가 만듦).
 * 실시간 운항 API(인증키 필요)가 안 될 때 쓰는 정기 스케줄이다. 파일에 든 기간(시즌) 밖의 날짜는 맞는 스케줄이 없어서,
 * 마지막 한 주의 요일 패턴을 이어 붙인 "예상"으로 보여 준다(항공사가 시즌마다 시각을 바꾸므로 달라질 수 있음).
 */
object AirSchedule {
    data class Row(
        val intl: Boolean, val airline: String, val flight: String, val dep: String, val arr: String,
        val depTime: LocalTime, val arrTime: LocalTime, val days: Set<DayOfWeek>, val start: LocalDate, val end: LocalDate,
    )

    class Result(val trips: List<Trip>, val projected: Boolean)

    /** 예상으로 이어 붙일 때 참고하는 마지막 기간(일) */
    private const val TAIL_DAYS = 7L

    private var appContext: Context? = null
    private var rows: List<Row> = emptyList()
    private var airlines: Map<String, String> = emptyMap()
    private var airports: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val c = appContext
            if (c != null) {
                fun text(name: String) = runCatching { c.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
                set(text("air_schedule.tsv")?.let(::parse).orEmpty(), parseNames(text("air_airlines.tsv")), parseAirports(text("air_airports.tsv")))
            }
            loaded = true
        }
    }

    internal fun set(r: List<Row>, a: Map<String, String>, ap: Map<String, String>) {
        synchronized(this) { rows = r; airlines = a; airports = ap; loaded = true }
    }

    fun parseNames(text: String?): Map<String, String> = text.orEmpty().lineSequence().mapNotNull { l ->
        val f = l.split('\t'); if (f.size >= 2 && f[0].isNotBlank()) f[0] to f[1] else null
    }.toMap()

    fun parseAirports(text: String?): Map<String, String> = parseNames(text)

    private const val DAY_CHARS = "월화수목금토일"

    fun parse(text: String): List<Row> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 10) return@mapNotNull null
        runCatching {
            Row(
                intl = f[0] == "I", airline = f[1], flight = f[2], dep = f[3], arr = f[4],
                depTime = LocalTime.parse(f[5]), arrTime = LocalTime.parse(f[6]),
                days = f[7].mapNotNull { c -> DAY_CHARS.indexOf(c).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) } }.toSet(),
                start = LocalDate.parse(f[8]), end = LocalDate.parse(f[9]),
            )
        }.getOrNull()
    }.toList()

    /** 이 구간·날짜의 운항편 (출발 시각순). 파일 기간 밖이면 [Result.projected] 가 true 인 예상 */
    fun trips(dep: String, arr: String, date: LocalDate): Result {
        ensure()
        val pair = rows.filter { it.dep == dep && it.arr == arr }
        if (pair.isEmpty()) return Result(emptyList(), false)
        val exact = pair.filter { date in it.start..it.end && date.dayOfWeek in it.days }
        val lastEnd = rows.maxOf { it.end }
        val (picked, projected) = when {
            exact.isNotEmpty() -> exact to false
            date > lastEnd -> pair.filter { it.end >= lastEnd.minusDays(TAIL_DAYS) && date.dayOfWeek in it.days } to true
            else -> emptyList<Row>() to false
        }
        val list = picked.distinctBy { it.flight to it.depTime }.sortedBy { it.depTime }.map { r ->
            val d = LocalDateTime.of(date, r.depTime)
            val a = LocalDateTime.of(date, r.arrTime).let { t -> if (t.isBefore(d)) t.plusDays(1) else t }
            Trip(
                kind = (airlines[r.airline] ?: r.airline) + if (projected) " · 예상" else "",
                number = r.flight, depName = airportName(r.dep), arrName = airportName(r.arr), dep = d, arr = a, prices = emptyList(),
            )
        }
        return Result(list, projected && list.isNotEmpty())
    }

    /** 이 구간(어느 날짜든)이 파일에 있는지 */
    fun hasPair(dep: String, arr: String): Boolean {
        ensure()
        return rows.any { it.dep == dep && it.arr == arr }
    }

    /** 국내선에 나오는 공항 (김포·제주 … 많이 쓰는 순). id = 공항 코드 */
    fun domesticAirports(): List<Place> {
        ensure()
        val codes = rows.filter { !it.intl }.flatMap { listOf(it.dep, it.arr) }.toSet()
        val infos = appContext?.let { AirportInfo.all(it) }.orEmpty()
        return codes.map { c ->
            val short = airportName(c)
            Place(c, infos.firstOrNull { it.code == c }?.name ?: "${short}공항", c)
        }.sortedBy { p -> PREFERRED.indexOf(p.id).let { if (it < 0) PREFERRED.size else it } }
    }

    /** 국내선을 운항하는 항공사 (id = 항공사 ICAO 코드) */
    fun domesticAirlines(): List<Grade> {
        ensure()
        return rows.filter { !it.intl }.map { it.airline }.distinct().map { Grade(it, airlines[it] ?: it) }.sortedBy { it.name }
    }

    private val PREFERRED = listOf("GMP", "CJU", "PUS", "TAE", "CJJ", "KWJ", "MWX", "YNY", "USN", "RSU", "HIN", "KPO", "KUV", "WJU", "ICN")

    fun airportName(code: String): String = airports[code] ?: KacIntl.KAC.firstOrNull { it.first == code }?.second ?: code

    /** 파일에 든 스케줄의 마지막 날 (없으면 null) */
    fun lastDate(): LocalDate? { ensure(); return rows.maxOfOrNull { it.end } }
}
