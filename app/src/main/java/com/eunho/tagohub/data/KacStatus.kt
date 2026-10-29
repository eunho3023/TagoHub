package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 한국공항공사 실시간 항공기 운항정보 + 기종·등록번호 (GET https://apis.data.go.kr/B551178/flight-aircraft-status/info, type=json).
 * 요청: schAirCode(기준공항 IATA) · schStTime/schEdTime(yyyyMMddHHmm, 그날 0000~2359). 한 번에 100행까지(300행부터 HTTP_ERROR).
 * 응답: airFln(편명) · std/etd(yyyyMMddHHmm 예정/변경) · rmkKor(출발·도착·지연·결항 …; 오늘 이후 날짜는 비어 있음) · io(I 도착/O 출발) ·
 *   city(상대 공항 IATA) · gate · icaoApmCd(기종) · aplRegNo(등록번호) · cdsrYn(코드쉐어).
 * 정기 스케줄([KacDom]·[KacIntl])에는 없는 그날의 실제 상태·변경 시각·탑승구·기종을 정기 스케줄 위에 얹는 데 쓴다. 오늘 기준 며칠 전후까지 자료가 있다.
 */
object KacStatus {
    const val URL = "https://apis.data.go.kr/B551178/flight-aircraft-status/info"

    data class Row(
        val flightId: String, val airline: String, val scheduled: LocalDateTime?, val estimated: LocalDateTime?,
        val remark: String?, val codeshare: Boolean, val departure: Boolean, val other: String,
        val gate: String?, val aircraft: String?, val registration: String?, val depName: String, val arrName: String,
    )

    /** 이 공항은 실시간 운항정보가 있음 (한국공항공사 14개 공항) */
    fun supports(code: String): Boolean = code in AirportLive.LOT_AIRPORT || code == "KPO"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private fun stamp(s: String?): LocalDateTime? = s?.takeIf { it.length >= 12 }?.let { runCatching { LocalDateTime.parse(it.take(12), STAMP) }.getOrNull() }

    private fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parse(body: String): List<Row> = AirportLive.rows(body).mapNotNull { m ->
        Row(
            flightId = m.s("airFln") ?: return@mapNotNull null, airline = m.s("airlineKorean").orEmpty(),
            scheduled = stamp(m.s("std")), estimated = stamp(m.s("etd")),
            remark = m.s("rmkKor"), codeshare = m.s("cdsrYn") == "Y", departure = m.s("io") == "O", other = m.s("city").orEmpty(),
            gate = m.s("gate"), aircraft = m.s("icaoApmCd"), registration = m.s("aplRegNo"),
            depName = m.s("boardingKor").orEmpty(), arrName = m.s("arrivedKor").orEmpty(),
        )
    }

    /** 기준 공항의 하루치에서 [departure] 방향·상대 공항 [other] 인 행만 */
    fun route(rows: List<Row>, departure: Boolean, other: String): List<Row> = rows.filter { it.departure == departure && it.other == other }

    private fun norm(no: String?) = no.orEmpty().filter { it.isLetterOrDigit() }.uppercase()

    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun info(r: Row, withChange: Boolean): List<String> = listOfNotNull(
        r.remark,
        r.gate?.takeIf { r.departure }?.let { "탑승구 $it" },
        r.aircraft?.let { a -> a + (r.registration?.let { " $it" } ?: "") },
        r.estimated?.takeIf { withChange && r.scheduled != null && it != r.scheduled }?.let { "예정 ${hhmm(r.scheduled!!)}" },
    )

    /**
     * 정기 스케줄 [trips] 위에 운항 상태를 얹는다: 편명이 같은 행의 변경 시각·상태(출발·도착·지연·결항)·탑승구·기종.
     * [dep]·[arr] 는 각각 출발·도착 공항 쪽 행(없으면 null). 정기 스케줄에 편이 없을 때([trips] 가 비었을 때)는 행으로 목록을 만든다.
     */
    fun overlay(trips: List<Trip>, dep: List<Row>?, arr: List<Row>?): List<Trip> {
        if (trips.isEmpty()) return fromRows(dep.orEmpty().ifEmpty { arr.orEmpty() }, departure = !dep.isNullOrEmpty())
        val depBy = dep.orEmpty().associateBy { norm(it.flightId) }
        val arrBy = arr.orEmpty().associateBy { norm(it.flightId) }
        return trips.map { t ->
            val key = norm(t.number)
            val d = depBy[key]
            val a = arrBy[key]
            if (d == null && a == null) return@map t
            // 출발 쪽 정보(탑승구)를 우선, 출발 쪽 상태가 비어 있으면 도착 쪽 상태
            val parts = if (d != null) info(d, true).let { p -> if (d.remark == null && a?.remark != null) listOf(a.remark) + p else p } else info(a!!, false)
            t.copy(
                kind = (listOfNotNull(t.kind.takeIf { it.isNotEmpty() }) + parts).joinToString(" · "),
                dep = d?.estimated ?: t.dep, arr = a?.estimated ?: t.arr,
            )
        }
    }

    private fun fromRows(rows: List<Row>, departure: Boolean): List<Trip> = rows
        .filter { !it.codeshare }
        .distinctBy { it.flightId + it.scheduled }
        .sortedBy { it.scheduled ?: LocalDateTime.MAX }
        .map { r ->
            val shown = r.estimated ?: r.scheduled
            Trip(
                kind = (listOfNotNull(r.airline.takeIf { it.isNotEmpty() }) + info(r, true)).joinToString(" · "), number = r.flightId,
                depName = r.depName, arrName = r.arrName,
                dep = if (departure) shown else null, arr = if (departure) null else shown, prices = emptyList(),
            )
        }
}

object KacStatusClient {
    private const val PAGE_ROWS = 100
    private const val MAX_PAGES = 10
    private const val CACHE_MS = 60_000L
    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<KacStatus.Row>>>()

    /** [base] 공항의 [date] 하루치(출발·도착 모두). 같은 공항·날짜는 1분간 다시 받지 않음 */
    private suspend fun day(base: String, date: LocalDate): List<KacStatus.Row> {
        val key = "$base|$date"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }
        val rows = withContext(Dispatchers.IO) {
            val raw = BuildConfig.KAC_API_KEY.trim()
            val apiKey = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
            val d = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            val out = ArrayList<KacStatus.Row>()
            for (page in 1..MAX_PAGES) {
                val url = KacStatus.URL.toHttpUrl().newBuilder().addEncodedQueryParameter("serviceKey", apiKey)
                    .addQueryParameter("type", "json").addQueryParameter("pageNo", page.toString()).addQueryParameter("numOfRows", PAGE_ROWS.toString())
                    .addQueryParameter("schAirCode", base).addQueryParameter("schStTime", d + "0000").addQueryParameter("schEdTime", d + "2359").build()
                val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                DataGoError.check(body, "한국공항공사 실시간 운항정보")
                val got = KacStatus.parse(body)
                out += got
                if (got.isEmpty() || out.size >= AirportLive.totalCount(body)) break
            }
            out
        }
        cache[key] = System.currentTimeMillis() to rows
        return rows
    }

    /**
     * [dep] → [arr] 의 [date] 운항 상태를 [trips](정기 스케줄) 위에 얹는다. 한국공항공사 공항이 아니거나 자료를 못 받으면 [trips] 그대로.
     * 출발·도착 공항이 모두 한국공항공사 공항이면(국내선) 두 쪽 시각을 다 받는다.
     */
    suspend fun apply(dep: String, arr: String, date: LocalDate, trips: List<Trip>): List<Trip> {
        val depOk = KacStatus.supports(dep)
        val arrOk = KacStatus.supports(arr)
        if (!depOk && !arrOk) return trips
        return runCatching {
            coroutineScope {
                val d = if (depOk) async { KacStatus.route(day(dep, date), departure = true, other = arr) } else null
                val a = if (arrOk) async { KacStatus.route(day(arr, date), departure = false, other = dep) } else null
                KacStatus.overlay(trips, d?.await(), a?.await())
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else trips }
    }
}
