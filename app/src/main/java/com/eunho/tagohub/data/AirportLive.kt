package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 한국공항공사 실시간 공항 정보 (공공데이터포털 B551178, 인증키 = KAC_API_KEY, type=json).
 *  · airport-congestion/v1(김포·제주) · v2(김해·청주·대구): 구간별 혼잡도 레벨 1~4
 *  · airport-process-time/v1: 구간별 평균 소요시간(초)
 *  · parking-congestion/info: 주차장별 혼잡률·혼잡정도
 *  · parking-valet-congestion/gmp-dom: 김포 국내선 발렛파킹 대기
 *  · parking-available-spaces/gmp-int-indoor: 김포 국제선 실내주차장 빈 주차면
 *  · airport-taxi-wait/cju: 제주 택시승강장 대기
 */
object AirportLive {
    data class Congestion(val airport: String, val time: String?, val levels: List<Int?>, val all: Int?) {
        companion object {
            fun label(level: Int?): String = when (level) { 1 -> "원활"; 2 -> "보통"; 3 -> "혼잡"; 4 -> "매우 혼잡"; else -> "정보 없음" }
        }
    }

    /** 구간별 평균 소요시간(초). [open] 이 false 면 지금 운영하지 않음 */
    data class ProcessTime(val airport: String, val time: String?, val open: Boolean?, val sectionSec: List<Int?>, val totalSec: Int?)

    data class Lot(val airport: String, val name: String, val label: String?, val percent: String?, val used: Int?, val total: Int?, val time: String?)

    data class Valet(val time: String?, val zones: List<Int?>, val total: Int?, val label: String?, val at: java.time.LocalDateTime? = null)

    data class Taxi(val time: String?, val taxis: Int?, val passengers: Int?, val waitMin: Int?, val at: java.time.LocalDateTime? = null)

    /** 이 시각보다 오래된 값은 "지금" 정보로 보여 주지 않음(발렛파킹·택시는 API 가 오래전 기록 하나만 주는 때가 있음) */
    const val FRESH_MINUTES = 180L

    fun isFresh(at: java.time.LocalDateTime?, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean =
        at != null && !at.isAfter(now.plusMinutes(10)) && java.time.Duration.between(at, now).toMinutes() <= FRESH_MINUTES

    private fun stamp(date: String?, time: String?): java.time.LocalDateTime? = runCatching {
        val d = date ?: return@runCatching null
        val t = (time ?: "000000").padStart(6, '0')
        java.time.LocalDateTime.of(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(), t.substring(0, 2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt())
    }.getOrNull()

    /** 김포 실내주차장: 층별 빈 주차면 / 전체 주차면 */
    data class IndoorFloor(val lot: String, val level: String, val empty: Int, val total: Int)

    private fun Map<*, *>.s(k: String) = this[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun Map<*, *>.n(k: String) = s(k)?.toDoubleOrNull()?.toInt()

    /** 응답 JSON 의 items → 행 목록 (item 이 하나면 객체, 여럿이면 배열; items 가 바로 배열일 수도 있음) */
    internal fun rows(text: String): List<Map<*, *>> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val body = (root["response"] as? Map<*, *>)?.get("body") as? Map<*, *> ?: return emptyList()
        val items = body["items"]
        val raw = if (items is Map<*, *>) items["item"] else items
        return when (raw) { is List<*> -> raw.filterIsInstance<Map<*, *>>(); is Map<*, *> -> listOf(raw); else -> emptyList() }
    }

    internal fun totalCount(text: String): Int {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return 0
        return ((root["response"] as? Map<*, *>)?.get("body") as? Map<*, *>)?.get("totalCount")?.toString()?.toDoubleOrNull()?.toInt() ?: 0
    }

    fun congestion(text: String): List<Congestion> = rows(text).mapNotNull { m ->
        Congestion(
            airport = m.s("IATA_APCD") ?: return@mapNotNull null, time = m.s("PRC_HR"),
            levels = listOf(m.n("CGDR_A_LVL"), m.n("CGDR_B_LVL"), m.n("CGDR_C_LVL")), all = m.n("CGDR_ALL_LVL"),
        )
    }

    fun processTime(text: String): List<ProcessTime> = rows(text).mapNotNull { m ->
        ProcessTime(
            airport = m.s("IATA_APCD") ?: return@mapNotNull null, time = m.s("PRC_HR"),
            open = m.n("OPR_STS_CD")?.let { it == 1 },
            sectionSec = listOf("A", "B", "C", "D").map { m.n("STY_TCT_AVG_$it") }, totalSec = m.n("STY_TCT_AVG_ALL"),
        )
    }

    /** 주차장 혼잡도 응답에 나오는 공항 이름 (IATA → airportKor) */
    private val LOT_AIRPORT = mapOf(
        "GMP" to "김포", "PUS" to "김해", "CJU" to "제주", "TAE" to "대구", "KWJ" to "광주", "RSU" to "여수", "USN" to "울산",
        "KUV" to "군산", "WJU" to "원주", "CJJ" to "청주", "MWX" to "무안", "HIN" to "사천", "YNY" to "양양",
    )

    /** [iata] 공항의 주차장만. 이 API 에 없는 공항(포항경주 등)은 빈 목록 */
    fun lotsOf(iata: String, all: List<Lot>): List<Lot> {
        val name = LOT_AIRPORT[iata] ?: return emptyList()
        return all.filter { it.airport.startsWith(name) }
    }

    fun lots(text: String): List<Lot> = rows(text).mapNotNull { m ->
        Lot(
            airport = m.s("airportKor").orEmpty(), name = m.s("parkingAirportCodeName") ?: return@mapNotNull null,
            label = m.s("parkingCongestion"), percent = m.s("parkingCongestionDegree"),
            used = m.n("parkingOccupiedSpace"), total = m.n("parkingTotalSpace"),
            time = listOfNotNull(m.s("sysGetdate"), m.s("sysGettime")).joinToString(" ").ifEmpty { null },
        )
    }

    /** 발렛파킹은 이력이 쌓여 있어서 가장 최근 행(일자+시각 최대)을 씀 */
    fun valet(text: String): Valet? = rows(text).maxByOrNull { (it.s("crtDt") ?: "") + (it.s("crtTime") ?: "") }?.let { m ->
        Valet(
            time = valetTime(m.s("crtDt"), m.s("crtTime")), zones = listOf(m.n("cam1"), m.n("cam2"), m.n("cam3")),
            total = m.n("total"), label = m.s("cdgr"), at = stamp(m.s("crtDt"), m.s("crtTime")),
        )
    }

    private fun valetTime(d: String?, t: String?): String? {
        if (d == null || d.length < 8) return null
        val day = "${d.substring(4, 6)}-${d.substring(6, 8)}"
        val clock = t?.padStart(6, '0')?.let { "${it.substring(0, 2)}:${it.substring(2, 4)}" }
        return listOfNotNull(day, clock).joinToString(" ")
    }

    fun taxi(text: String): Taxi? = rows(text).firstOrNull()?.let { m ->
        Taxi(
            time = m.s("prcdtm")?.takeIf { it.length >= 12 }?.let { "${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(8, 10)}:${it.substring(10, 12)}" },
            taxis = m.n("witTaxiCT"), passengers = m.n("witPaxCT"), waitMin = m.n("xptBdgMi"),
            at = m.s("prcdtm")?.takeIf { it.length >= 12 }?.let { stamp(it.substring(0, 8), it.substring(8)) },
        )
    }

    /** 층별 빈 주차면(입차 가능 EMPTY)과 전체 주차면 수 */
    fun indoor(rows: List<Map<*, *>>): List<IndoorFloor> = rows
        .groupBy { (it.s("parkingName") ?: "") to (it.s("levelName") ?: "") }
        .map { (k, cells) -> IndoorFloor(k.first, k.second, cells.count { it.s("cellStatus") == "EMPTY" }, cells.size) }
        .sortedWith(compareBy({ it.lot }, { it.level }))

    /** 분 단위 표기 ("약 12분") */
    fun minutes(sec: Int?): String? = sec?.let { "약 ${((it + 30) / 60).coerceAtLeast(1)}분" }
}

/** 한 공항의 실시간 정보 묶음. 못 받은 항목은 null / 빈 목록, 실패 이유는 [errors] */
data class AirportLiveInfo(
    val congestion: AirportLive.Congestion?,
    val process: AirportLive.ProcessTime?,
    val lots: List<AirportLive.Lot>,
    val valet: AirportLive.Valet?,
    val indoor: List<AirportLive.IndoorFloor>,
    val taxi: AirportLive.Taxi?,
    val errors: List<String>,
) {
    val isEmpty: Boolean get() = congestion == null && process == null && lots.isEmpty() && valet == null && indoor.isEmpty() && taxi == null
}

object AirportLiveClient {
    private const val BASE = "https://apis.data.go.kr/B551178/"
    private const val INDOOR_PAGE_ROWS = 100
    private const val INDOOR_MAX_PAGES = 30
    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    // 혼잡도 API 는 공항에 따라 v1(김포·제주) / v2(김해·청주·대구) 로 나뉨
    private val V1 = setOf("GMP", "CJU")
    private val V2 = setOf("PUS", "CJJ", "TAE")

    private fun get(path: String, name: String, vararg params: Pair<String, String>): String {
        val raw = BuildConfig.KAC_API_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val url = (BASE + path).toHttpUrl().newBuilder().addEncodedQueryParameter("serviceKey", key).addQueryParameter("type", "json")
            .also { b -> params.forEach { (k, v) -> b.addQueryParameter(k, v) } }.build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        DataGoError.check(body, name)
        return body
    }

    /** [iata] 공항의 실시간 정보를 한꺼번에(동시에) 받음. 항목마다 따로 실패할 수 있음 */
    suspend fun load(iata: String): AirportLiveInfo = withContext(Dispatchers.IO) {
        coroutineScope {
            val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
            fun <T> guarded(block: () -> T): T? = runCatching(block).onFailure { errors += it.message ?: "조회 실패" }.getOrNull()

            val congestion = async {
                val v = when (iata) { in V1 -> "v1"; in V2 -> "v2"; else -> null } ?: return@async null
                guarded { AirportLive.congestion(get("airport-congestion/$v", "한국공항공사 공항 혼잡도", "pageNo" to "1", "numOfRows" to "20")).firstOrNull { it.airport == iata } }
            }
            val process = async {
                guarded { AirportLive.processTime(get("airport-process-time/v1", "한국공항공사 공항 소요시간", "pageNo" to "1", "numOfRows" to "20")).firstOrNull { it.airport == iata } }
            }
            val lots = async {
                // schAirportCode 는 무시되고 전국 25개 주차장이 다 오므로 이 공항 것만 거름
                guarded { AirportLive.lotsOf(iata, AirportLive.lots(get("parking-congestion/info", "한국공항공사 주차장 혼잡도", "pageNo" to "1", "numOfRows" to "100"))) }.orEmpty()
            }
            val valet = async {
                if (iata != "GMP") return@async null
                // 전체 건수는 만 건이 넘게 나오지만 실제로 받아지는 건 첫 쪽의 한 건뿐이고 그 시각이 오래된 경우가 있음 → 최근 3시간 안의 기록만 보여 줌
                guarded {
                    AirportLive.valet(get("parking-valet-congestion/gmp-dom", "한국공항공사 김포 발렛파킹 혼잡도", "pageNo" to "1", "numOfRows" to "1"))
                }?.takeIf { AirportLive.isFresh(it.at) }
            }
            val indoor = async {
                if (iata != "GMP") return@async emptyList()
                guarded {
                    // 이 API 는 한 번에 300행부터는 "HTTP_ERROR(04)" 로 거절함(100행은 됨) → 100행씩 첫 쪽의 전체 건수만큼 이어서 받음 (약 25쪽, 한 쪽 0.1초 안팎)
                    fun page(n: Int) = get("parking-available-spaces/gmp-int-indoor", "한국공항공사 김포 실내주차장", "pageNo" to n.toString(), "numOfRows" to INDOOR_PAGE_ROWS.toString())
                    val first = page(1)
                    val total = AirportLive.totalCount(first)
                    val lastPage = ((total + INDOOR_PAGE_ROWS - 1) / INDOOR_PAGE_ROWS).coerceIn(1, INDOOR_MAX_PAGES)
                    val rest = (2..lastPage).mapNotNull { n -> runCatching { page(n) }.getOrNull() }
                    AirportLive.indoor((listOf(first) + rest).flatMap { AirportLive.rows(it) })
                }.orEmpty()
            }
            val taxi = async {
                if (iata != "CJU") return@async null
                guarded { AirportLive.taxi(get("airport-taxi-wait/cju", "한국공항공사 제주 택시승강장", "pageNo" to "1", "numOfRows" to "1")) }
                    ?.takeIf { AirportLive.isFresh(it.at) }   // 제주 택시는 호출했을 때 한 달 반 전 기록이 오기도 함
            }
            AirportLiveInfo(congestion.await(), process.await(), lots.await(), valet.await(), indoor.await(), taxi.await(), errors.distinct())
        }
    }
}
