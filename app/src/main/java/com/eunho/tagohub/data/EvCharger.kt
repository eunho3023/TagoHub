package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * 전기차 충전소.
 *  - 먼저 환경부(한국환경공단) 전기자동차 충전소 정보 (공공데이터포털 키 = TAGO_SERVICE_KEY): 전국 모든 운영사 충전기의 위치·지금 상태.
 *    시·군·구(법정동 코드 앞 5자리)로만 물을 수 있어서 화면에 걸친 시·군·구를 카카오 좌표→행정구역으로 알아내 받음.
 *    구 하나가 충전기 수천 대(강남 약 5,900대)라 2천 대씩 받아 10분 기억하고, 그 사이에는 1분마다 시·도의 "최근 10분 동안 상태가 바뀐 충전기"만 덧입힘.
 *  - 환경부를 못 받으면 한국전력 전력데이터 개방포털 EVchargeManage (한전 충전기만, 키 KEPCO_API_KEY): 시·도 이름으로 받아 2분 기억
 */
object EvCharger {
    const val LAYER = "ev"
    private const val KEPCO_URL = "https://bigdata.kepco.co.kr/openapi/v1/EVchargeManage.do"
    private const val ME_URL = "https://apis.data.go.kr/B552584/EvCharger"
    private const val MAX = 200

    /** 충전기 상태 (운영사마다 코드가 달라 한 가지로) */
    const val FREE = 1
    const val BUSY = 2
    const val DOWN = 3
    const val UNKNOWN = 0

    /**
     * @param state [FREE]·[BUSY]·[DOWN]·[UNKNOWN]
     * @param updated 상태 바뀐 때 (yyyyMMddHHmmss, 모르면 "")
     */
    data class Charger(
        val id: String, val station: String, val stationName: String, val addr: String, val lat: Double, val lng: Double,
        val fast: Boolean, val state: Int, val operator: String = "한전", val updated: String = "", val extra: String = "",
    )

    data class Station(val id: String, val name: String, val addr: String, val lat: Double, val lng: Double, val chargers: List<Charger>) {
        val free: Int get() = chargers.count { it.state == FREE }
    }

    fun stateName(state: Int) = when (state) { FREE -> "충전 가능"; BUSY -> "충전 중"; DOWN -> "점검 중"; else -> "상태 모름" }

    // ── 한전 ──

    /** 한전 상태 코드: 1 충전 가능, 2 충전 중, 3 고장·점검, 그 밖은 통신 문제 등으로 모름 */
    fun kepcoState(code: String) = when (code.trim()) { "1" -> FREE; "2" -> BUSY; "3" -> DOWN; else -> UNKNOWN }

    fun parse(body: String?): List<Charger> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? Map<*, *> ?: return emptyList()
        return (root["data"] as? List<*>).orEmpty().mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val s = { k: String -> m[k]?.toString()?.trim().orEmpty() }
            val lat = s("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = s("longi").toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in 33.0..39.0 || lng !in 124.0..132.0) return@mapNotNull null
            Charger(s("cpId"), s("csId").ifEmpty { "$lat,$lng" }, s("csNm").ifEmpty { "충전소" }, s("addr"), lat, lng, s("chargeTp") == "2", kepcoState(s("cpStat")))
        }
    }

    // ── 환경부 ──

    /** 환경부 상태 코드: 1 통신이상, 2 충전대기, 3 충전중, 4 운영중지, 5 점검중, 9 상태미확인 */
    fun meState(code: String) = when (code.trim()) { "2" -> FREE; "3" -> BUSY; "4", "5" -> DOWN; else -> UNKNOWN }

    /** 충전기 타입: 02 AC 완속, 08 DC 콤보(완속) 말고는 급속 (01 차데모, 04 DC 콤보, 07 AC 3상 …) */
    fun meFast(type: String) = type.trim() !in setOf("02", "08", "")

    private fun meItems(body: String?): Pair<List<Map<*, *>>, Int> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? Map<*, *> ?: return emptyList<Map<*, *>>() to -1
        val items = root["items"]
        val list = when (items) {
            is Map<*, *> -> when (val it = items["item"]) { is List<*> -> it; is Map<*, *> -> listOf(it); else -> emptyList<Any>() }
            is List<*> -> items
            else -> return emptyList<Map<*, *>>() to -1   // 오류 응답 (OpenAPI_ServiceResponse 등)
        }
        return list.filterIsInstance<Map<*, *>>() to (root["totalCount"]?.toString()?.toDoubleOrNull()?.toInt() ?: list.size)
    }

    /** 충전기 정보(getChargerInfo) 한 쪽 → 충전기들과 전체 수 (오류면 null) */
    fun parseMe(body: String?): Pair<List<Charger>, Int>? {
        val (rows, total) = meItems(body)
        if (total < 0) return null
        return rows.mapNotNull { m ->
            val s = { k: String -> m[k]?.toString()?.trim().orEmpty() }
            if (s("delYn") == "Y") return@mapNotNull null
            val lat = s("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = s("lng").toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in 33.0..39.0 || lng !in 124.0..132.0) return@mapNotNull null
            val extra = listOfNotNull(
                s("useTime").takeIf { it.isNotEmpty() }?.let { if ("24시간" in it) "24시간" else "이용 $it" },
                if (s("parkingFree") == "Y") "주차 무료" else null,
                if (s("limitYn") == "Y") "이용 제한" else null,
            ).joinToString(" · ")
            Charger(s("chgerId"), s("statId"), s("statNm").ifEmpty { "충전소" }, s("addr"), lat, lng, meFast(s("chgerType")), meState(s("stat")),
                s("busiNm").ifEmpty { s("bnm") }, s("statUpdDt"), extra)
        } to total
    }

    /** 상태 바뀐 충전기(getChargerStatus) → {충전소|충전기: (상태, 바뀐 때)} */
    fun parseChanges(body: String?): Map<String, Pair<Int, String>>? {
        val (rows, total) = meItems(body)
        if (total < 0) return null
        return rows.associate { m ->
            val s = { k: String -> m[k]?.toString()?.trim().orEmpty() }
            "${s("statId")}|${s("chgerId")}" to (meState(s("stat")) to s("statUpdDt"))
        }
    }

    /** 받아 둔 충전기에 그 뒤 바뀐 상태를 덧입힘 (바뀐 때가 더 늦을 때만) */
    fun applyChanges(list: List<Charger>, changes: Map<String, Pair<Int, String>>): List<Charger> = list.map { c ->
        val ch = changes["${c.station}|${c.id}"] ?: return@map c
        if (ch.second >= c.updated) c.copy(state = ch.first, updated = ch.second) else c
    }

    fun stations(chargers: List<Charger>): List<Station> = chargers.groupBy { it.station }.map { (id, list) ->
        val f = list.first()
        Station(id, f.stationName, f.addr, f.lat, f.lng, list)
    }

    /** 지도 점: "충전 가능 3/6대 · 급속 2대 · 운영사 · 주소 · 이용시간". 점에 빈 충전기 수를 보이려고 id 둘째 칸에 넣음 */
    fun feature(st: Station): ItsRoadFeature {
        val fast = st.chargers.count { it.fast }
        val down = st.chargers.count { it.state == DOWN }
        val f = st.chargers.first()
        return ItsRoadFeature(
            "$LAYER:${st.free}:${st.id}", LAYER, st.name,
            listOfNotNull(
                "충전 가능 ${st.free}/${st.chargers.size}대",
                if (fast > 0) "급속 ${fast}대" else "완속",
                if (down > 0) "점검 중 ${down}대" else null,
                f.operator.takeIf { it.isNotBlank() }, st.addr.takeIf { it.isNotBlank() }, f.extra.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            st.lat, st.lng,
        )
    }

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private fun fetch(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun meKey(): String {
        val raw = com.eunho.tagohub.BuildConfig.TAGO_SERVICE_KEY.trim()
        return if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
    }

    private val districtInfo = HashMap<String, Pair<Long, List<Charger>>>()
    private val changeCache = HashMap<String, Pair<Long, Map<String, Pair<Int, String>>>>()
    private val codeAt = HashMap<String, String>()

    /** 시·군·구 하나의 충전기 (2천 대씩, 10분 기억). 못 받으면 null */
    private suspend fun district(code: String): List<Charger>? {
        synchronized(districtInfo) { districtInfo[code]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second } }
        val got = withContext(Dispatchers.IO) {
            val out = ArrayList<Charger>()
            var page = 1
            while (page <= 10) {
                val (rows, total) = parseMe(fetch("$ME_URL/getChargerInfo?serviceKey=${meKey()}&pageNo=$page&numOfRows=2000&dataType=JSON&zscode=$code")) ?: return@withContext null
                out += rows
                if (rows.isEmpty() || page * 2000 >= total) break
                page++
            }
            out
        } ?: return synchronized(districtInfo) { districtInfo[code]?.second }
        synchronized(districtInfo) { districtInfo[code] = System.currentTimeMillis() to got }
        return got
    }

    /** 시·도 하나에서 최근 10분 동안 상태가 바뀐 충전기 (1분 기억) */
    private suspend fun changes(zcode: String): Map<String, Pair<Int, String>> {
        synchronized(changeCache) { changeCache[zcode]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second } }
        val got = withContext(Dispatchers.IO) {
            parseChanges(fetch("$ME_URL/getChargerStatus?serviceKey=${meKey()}&pageNo=1&numOfRows=9999&dataType=JSON&period=10&zcode=$zcode"))
        } ?: return synchronized(changeCache) { changeCache[zcode]?.second.orEmpty() }
        synchronized(changeCache) { changeCache[zcode] = System.currentTimeMillis() to got }
        return got
    }

    /** 좌표 → 시·군·구 코드 5자리 (법정동 코드 앞 5자리, 약 1km 칸마다 기억) */
    private suspend fun districtCode(lat: Double, lng: Double): String? {
        val k = "%.2f,%.2f".format(lat, lng)
        synchronized(codeAt) { codeAt[k]?.let { return it } }
        val c = KakaoExtra.legalCode(lat, lng)?.take(5) ?: return null
        synchronized(codeAt) { codeAt[k] = c }
        return c
    }

    /** 화면 범위의 시·군·구 코드 (가운데와 네 귀퉁이 안쪽, 넷까지) */
    private suspend fun districts(s: Double, w: Double, n: Double, e: Double): List<String> = coroutineScope {
        val dy = (n - s) * 0.3
        val dx = (e - w) * 0.3
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        listOf(cla to clo, cla + dy to clo - dx, cla + dy to clo + dx, cla - dy to clo - dx, cla - dy to clo + dx)
            .map { (a, b) -> async { districtCode(a, b) } }.awaitAll().filterNotNull().distinct().take(4)
    }

    /** 환경부 충전기 (화면 범위). 하나도 못 받으면 null → 한전으로 */
    private suspend fun ministry(s: Double, w: Double, n: Double, e: Double): List<Charger>? = coroutineScope {
        if (com.eunho.tagohub.BuildConfig.TAGO_SERVICE_KEY.isBlank()) return@coroutineScope null
        val codes = districts(s, w, n, e).takeIf { it.isNotEmpty() } ?: return@coroutineScope null
        val info = codes.map { async { district(it) } }.awaitAll()
        if (info.all { it == null }) return@coroutineScope null
        val ch = codes.map { it.take(2) }.distinct().map { async { changes(it) } }.awaitAll().fold(emptyMap<String, Pair<Int, String>>()) { a, b -> a + b }
        applyChanges(info.filterNotNull().flatten(), ch)
    }

    // ── 한전 (환경부를 못 받을 때) ──

    /** 시·도: 주소에 들어가는 이름들(옛 이름·줄임 포함)과 대략의 범위 (남, 서, 북, 동) */
    private class Region(val names: List<String>, val s: Double, val w: Double, val n: Double, val e: Double)

    private val REGIONS = listOf(
        Region(listOf("서울특별시"), 37.41, 126.76, 37.72, 127.19),
        Region(listOf("부산광역시"), 34.88, 128.76, 35.40, 129.32),
        Region(listOf("대구광역시"), 35.60, 128.35, 36.02, 128.77),
        Region(listOf("인천광역시"), 37.00, 124.60, 37.98, 126.80),
        Region(listOf("광주광역시"), 35.05, 126.64, 35.26, 127.02),
        Region(listOf("대전광역시"), 36.18, 127.24, 36.50, 127.56),
        Region(listOf("울산광역시"), 35.32, 128.96, 35.73, 129.47),
        Region(listOf("세종특별자치시"), 36.40, 127.17, 36.74, 127.40),
        Region(listOf("경기도"), 36.89, 126.37, 38.29, 127.86),
        Region(listOf("강원"), 37.02, 127.08, 38.62, 129.37),
        Region(listOf("충청북도", "충북"), 36.00, 127.27, 37.27, 128.66),
        Region(listOf("충청남도", "충남"), 35.97, 125.90, 37.07, 127.65),
        Region(listOf("전북", "전라북도"), 35.28, 126.00, 36.16, 127.92),
        Region(listOf("전라남도", "전남"), 33.90, 125.00, 35.50, 127.90),
        Region(listOf("경상북도", "경북"), 35.57, 127.80, 37.55, 130.00),
        Region(listOf("경상남도", "경남"), 34.46, 127.57, 35.90, 129.23),
        Region(listOf("제주"), 33.10, 126.10, 33.60, 127.00),
    )

    private val kepcoCache = HashMap<String, Pair<Long, List<Charger>>>()

    private suspend fun kepcoRegion(name: String): List<Charger> {
        synchronized(kepcoCache) { kepcoCache[name]?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.let { return it.second } }
        val key = com.eunho.tagohub.BuildConfig.KEPCO_API_KEY.trim()
        if (key.isEmpty()) return emptyList()
        val got = withContext(Dispatchers.IO) {
            val url = KEPCO_URL.toHttpUrl().newBuilder().addQueryParameter("addr", name).addQueryParameter("apiKey", key).addQueryParameter("returnType", "json").build()
            fetch(url.toString())?.let(::parse)
        } ?: return synchronized(kepcoCache) { kepcoCache[name]?.second.orEmpty() }
        synchronized(kepcoCache) { kepcoCache[name] = System.currentTimeMillis() to got }
        return got
    }

    private suspend fun kepco(s: Double, w: Double, n: Double, e: Double): List<Charger> = coroutineScope {
        val names = REGIONS.filter { it.s <= n && it.n >= s && it.w <= e && it.e >= w }.takeIf { it.size <= 3 }?.flatMap { it.names }
            ?: return@coroutineScope emptyList()
        names.map { async { kepcoRegion(it) } }.awaitAll().flatten().distinctBy { it.station + "|" + it.id }
    }

    /** 화면 범위의 충전소 (시·군·구 단위로 받으므로 넓게 보면 안 받음) */
    suspend fun features(s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> {
        if (n - s > 0.6 || e - w > 0.8) return emptyList()
        val all = ministry(s, w, n, e) ?: kepco(s, w, n, e)
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        return stations(all.filter { it.lat in s..n && it.lng in w..e })
            .sortedBy { hypot(it.lat - cla, (it.lng - clo) * 0.8) }.take(MAX).map(::feature)
    }
}
