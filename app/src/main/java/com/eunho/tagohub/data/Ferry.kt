package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 연안여객선 (한국해양교통안전공단, 공공데이터포털).
 * 기항지(항구)·운항노선(항로마다 들르는 항구 순서)은 앱 파일(ferry_ports.json·ferry_lines.json, pipeline/build_api_files.py 가 매주 새로 만듦),
 * 그날 운항 스케줄(출항 시각·출항지·도착지·정상/통제·통제 사유)은 실시간 API. 스케줄은 오늘·내일만 나옴.
 */
object Ferry {
    data class Port(val code: String, val name: String, val lat: Double?, val lng: Double?, val sido: String)

    /** [stops]: 들르는 항구 순서 (항구 코드, 정방향·역방향 정박 "YY"/"YN"…), 소요 분은 정방향·역방향 처음→끝 */
    data class Line(val name: String, val circular: Boolean, val fwdMin: Int, val revMin: Int, val stops: List<Pair<String, String>>)

    data class Sailing(
        val date: String, val time: String, val ship: String,
        val from: String, val fromName: String, val to: String, val toName: String,
        val lineKey: String, val reverse: Boolean, val routeName: String,
        /** 정상·통제·비운(운항 안 함)·증회·증선 */
        val kind: String,
        /** 출항전·출항중·완료 … */
        val state: String,
        val reason: String?,
    )

    private fun Any?.str() = this?.toString()?.trim().orEmpty()
    private fun Any?.num() = this?.toString()?.trim()?.toDoubleOrNull()

    fun parsePorts(text: String): List<Port> =
        (runCatching { MiniJson.parse(text) }.getOrNull() as? List<*>).orEmpty().mapNotNull { r ->
            val f = r as? List<*> ?: return@mapNotNull null
            val code = f.getOrNull(0).str()
            val name = f.getOrNull(1).str()
            if (code.isEmpty() || name.isEmpty()) null else Port(code, name, f.getOrNull(2).num(), f.getOrNull(3).num(), f.getOrNull(4).str())
        }

    fun parseLines(text: String): Map<String, Line> {
        val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        return root.entries.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val stops = (m["s"] as? List<*>).orEmpty().mapNotNull { s -> (s as? List<*>)?.let { it.getOrNull(0).str() to it.getOrNull(1).str().ifEmpty { "YY" } } }
            k.str() to Line(m["n"].str(), m["c"].num() == 1.0, m["f"].num()?.toInt() ?: 0, m["r"].num()?.toInt() ?: 0, stops)
        }.toMap()
    }

    /** 운항 스케줄 API 응답 → 운항 목록 */
    fun parseSailings(body: String): List<Sailing> = AirportLive.rows(body).mapNotNull { m ->
        val from = m["oport_cd"].str()
        val to = m["dest_cd"].str()
        if (from.isEmpty() || to.isEmpty()) return@mapNotNull null
        Sailing(
            date = m["rlvt_ymd"].str(), time = m["sail_tm"].str().padStart(4, '0'), ship = m["psnshp_nm"].str(),
            from = from, fromName = m["oport_nm"].str(), to = to, toName = m["dest_nm"].str(),
            lineKey = m["lcns_seawy_cd"].str() + "|" + m["nvg_seawy_cd"].str(), reverse = m["nvg_drc_cd"].str() == "2",
            routeName = m["nvg_seawy_nm"].str().ifEmpty { m["lcns_seawy_nm"].str() },
            kind = m["nvg_se_nm"].str(), state = m["nvg_stts_nm"].str(),
            reason = (m["cntrl_rsn_nm"].str().ifEmpty { m["nnavi_rsn_nm"].str() }.ifEmpty { m["cnls_etc_rsn"].str() }).takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /** 이 운항이 들르는 항구 (출항지부터 도착지까지). 노선 순서는 정방향, 역방향 운항은 거꾸로. 노선을 모르면 출항지·도착지만 */
    fun stopsOf(s: Sailing, line: Line?): List<String> {
        val pair = listOf(s.from, s.to)
        line ?: return pair
        val seq = (if (s.reverse) line.stops.reversed() else line.stops)
        val i = seq.indexOfFirst { it.first == s.from }
        if (i < 0) return pair
        val j = (i + 1 until seq.size).firstOrNull { seq[it].first == s.to } ?: return pair
        val flag = if (s.reverse) 1 else 0
        return seq.subList(i, j + 1).filterIndexed { k, st -> k == 0 || k == j - i || st.second.getOrNull(flag) != 'N' }.map { it.first }
    }

    /** [dep] 에서 출항해 [arr] 에 들르는 그날 운항. 도착 시각은 도착지가 [arr] 이고 노선 소요 시간을 알 때만 */
    fun trips(sailings: List<Sailing>, lines: Map<String, Line>, ports: Map<String, Port>, dep: String, arr: String, date: LocalDate): List<Trip> =
        sailings.mapNotNull { s ->
            if (s.from != dep) return@mapNotNull null
            val line = lines[s.lineKey]
            val stops = stopsOf(s, line)
            if (arr !in stops.drop(1)) return@mapNotNull null
            val t = runCatching { LocalTime.of(s.time.take(2).toInt(), s.time.drop(2).take(2).toInt()) }.getOrNull() ?: return@mapNotNull null
            val start = LocalDateTime.of(date, t)
            val min = line?.let { if (s.reverse) it.revMin else it.fwdMin } ?: 0
            val end = if (arr == s.to && min > 0) start.plusMinutes(min.toLong()) else null
            val via = stops.size - 2
            Trip(
                kind = listOfNotNull(s.ship.ifEmpty { null }, status(s), if (via > 0) "경유 ${via}곳" else null).joinToString(" · "),
                number = s.routeName.ifEmpty { null },
                depName = s.fromName.ifEmpty { ports[dep]?.name ?: dep },
                arrName = ports[arr]?.name ?: s.toName,
                dep = start, arr = end, prices = emptyList(), depId = dep, arrId = arr,
            )
        }.sortedBy { it.dep }

    /** "통제(풍랑주의보)" / "운항 안 함" / "운항 완료" / null(출항 전 정상) */
    fun status(s: Sailing): String? = when (s.kind) {
        "통제" -> "통제" + (s.reason?.let { "($it)" } ?: "")
        "비운" -> "운항 안 함" + (s.reason?.let { "($it)" } ?: "")
        else -> when (s.state) {
            "완료" -> "운항 완료"
            "출항중" -> "운항 중"
            else -> if (s.kind == "증회" || s.kind == "증선") s.kind else null
        }
    }

    /** [fixed] 와 같은 노선에 있는 항구 코드 (출발을 고르면 갈 수 있는 곳) */
    fun connected(lines: Map<String, Line>, fixed: String): Set<String> =
        lines.values.filter { l -> l.stops.any { it.first == fixed } }.flatMap { l -> l.stops.map { it.first } }.toSet() - fixed
}

/** 앱 파일 읽기 + 운항 스케줄 받기 */
object FerryClient {
    private const val URL = "https://apis.data.go.kr/B554035/oprt-schd-info-v2/get-oprt-schd-info-v2"
    private val client = Http.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private var stamp: String? = null
    private var portsCache: List<Ferry.Port> = emptyList()
    private var linesCache: Map<String, Ferry.Line> = emptyMap()

    @Synchronized
    private fun load() {
        val s = AssetText.stamp("ferry_ports.json") + "/" + AssetText.stamp("ferry_lines.json")
        if (s == stamp && portsCache.isNotEmpty()) return
        portsCache = AssetText.read("ferry_ports.json")?.let(Ferry::parsePorts).orEmpty()
        linesCache = AssetText.read("ferry_lines.json")?.let(Ferry::parseLines).orEmpty()
        stamp = s
    }

    fun ports(): List<Ferry.Port> { load(); return portsCache }
    fun lines(): Map<String, Ferry.Line> { load(); return linesCache }

    /** 고를 수 있는 항구 (노선에 나오는 곳만), 시·도는 정식 이름 (전남광주통합특별시·강원특별자치도…) */
    fun places(): List<Place> {
        val used = lines().values.flatMap { l -> l.stops.map { it.first } }.toSet()
        return ports().filter { it.code in used }.distinctBy { it.code }
            .map { Place(it.code, it.name, it.sido.takeIf { s -> s.isNotEmpty() }?.let(PlaceGroups::fullRegion)) }
            .sortedBy { it.name }
    }

    private val sailCache = HashMap<String, Pair<Long, List<Ferry.Sailing>>>()

    private suspend fun sailings(date: LocalDate): List<Ferry.Sailing> = withContext(Dispatchers.IO) {
        val ymd = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        synchronized(sailCache) { sailCache[ymd]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return@withContext it.second } }
        val raw = BuildConfig.TAGO_SERVICE_KEY.trim()
        val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
        val out = ArrayList<Ferry.Sailing>()
        for (page in 1..5) {
            val url = URL.toHttpUrl().newBuilder()
                .addEncodedQueryParameter("serviceKey", key)
                .addQueryParameter("pageNo", page.toString()).addQueryParameter("numOfRows", "1000")
                .addQueryParameter("dataType", "JSON").addQueryParameter("rlvtYmd", ymd)
                .build()
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            if ("NOT_FOUND_DATA" in body) break
            DataGoError.check(body, "여객선 운항 스케줄")
            val got = Ferry.parseSailings(body)
            out += got
            if (got.isEmpty() || out.size >= AirportLive.totalCount(body)) break
        }
        synchronized(sailCache) { sailCache[ymd] = System.currentTimeMillis() to out }
        out
    }

    suspend fun trips(dep: String, arr: String, date: LocalDate): TagoResult<List<Trip>> = runCatching {
        val today = LocalDate.now()
        if (date.isBefore(today) || date.isAfter(today.plusDays(1))) error("여객선 운항 스케줄은 오늘과 내일만 볼 수 있어요")
        val ports = ports().associateBy { it.code }
        val out = Ferry.trips(sailings(date), lines(), ports, dep, arr, date)
        // 출항지 근처 바다 상태 (오늘 편만: 관측은 지금 값)
        val p = ports[dep]
        val sea = if (out.isNotEmpty() && date == today && p?.lat != null && p.lng != null)
            withContext(Dispatchers.IO) { SeaObs.nearest(SeaObs.latest(), p.lat, p.lng)?.let(SeaObs::text) }?.takeIf { it.isNotEmpty() } else null
        if (sea == null) out else out.map { it.copy(kind = it.kind + " · " + sea) }
    }.fold({ TagoResult.Success(it) }, { TagoResult.Error(it.message ?: "여객선 운항 스케줄 조회 실패") })
}

/** 여객선 항구 위치·시·도 (기항지 파일) */
object FerryPortGeo : PlaceGeo {
    private fun find(p: Place) = FerryClient.ports().firstOrNull { it.code == p.id }
    override fun pointOf(p: Place) = find(p)?.let { if (it.lat != null && it.lng != null) it.lat to it.lng else null }
    override fun regionOf(p: Place) = find(p)?.sido
}

/**
 * 바다 상태 (기상청 API허브 해양 종합 관측 sea_obs.php: 부이·등표 등 약 190곳, 최근 1시간). 여객선 운항편에 출항지 근처 파고·바람을 붙임.
 * 응답은 EUC-KR 글자, "#" 줄은 설명, 값 줄은 "TP, TM, STN_ID, STN_KO, LON, LAT, WH, WD, WS, WS_GST, TW, TA, PA, HM, …"
 */
object SeaObs {
    data class Obs(val id: String, val name: String, val lat: Double, val lng: Double, val wave: Double?, val wind: Double?)

    private fun good(v: String?) = v?.trim()?.toDoubleOrNull()?.takeIf { it > -50 }

    fun parse(text: String): List<Obs> = text.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.mapNotNull { line ->
        val f = line.split(',').map { it.trim() }
        if (f.size < 9) return@mapNotNull null
        val lng = good(f[4]) ?: return@mapNotNull null
        val lat = good(f[5]) ?: return@mapNotNull null
        Obs(f[2], f[3], lat, lng, good(f[6]), good(f[8]))
    }.groupBy { it.id }.map { (_, v) -> v.last() }.toList()

    /** [lat],[lng] 에서 [maxKm] 안의 가장 가까운 관측 (파고나 바람이 있는 것) */
    fun nearest(all: List<Obs>, lat: Double, lng: Double, maxKm: Double = 60.0): Obs? =
        all.filter { it.wave != null || it.wind != null }
            .map { it to Geo.meters(lat, lng, it.lat, it.lng) }
            .filter { it.second <= maxKm * 1000 }
            .minByOrNull { it.second }?.first

    /** "파고 2.9m · 바람 13m/s" */
    fun text(o: Obs): String = listOfNotNull(o.wave?.let { "파고 ${"%.1f".format(it)}m" }, o.wind?.let { "바람 ${it.toInt()}m/s" }).joinToString(" · ")

    private val client = Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private var at = 0L
    private var cache: List<Obs> = emptyList()

    @Synchronized
    fun latest(): List<Obs> {
        if (System.currentTimeMillis() - at < 10 * 60_000 && cache.isNotEmpty()) return cache
        val key = BuildConfig.KMA_HUB_KEY.trim()
        if (key.isEmpty()) return cache
        val tm = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        val got = runCatching {
            client.newCall(Request.Builder().url("https://apihub.kma.go.kr/api/typ01/url/sea_obs.php?tm=$tm&stn=0&help=0&authKey=$key").build()).execute()
                .use { r -> if (r.isSuccessful) r.body?.bytes()?.let { parse(String(it, charset("EUC-KR"))) } else null }
        }.getOrNull().orEmpty()
        at = System.currentTimeMillis()
        if (got.isNotEmpty()) cache = got
        return cache
    }
}
