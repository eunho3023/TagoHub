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
 * 전기차 충전소 (한국전력 전력데이터 개방포털 EVchargeManage: 한전이 운영하는 충전기의 위치·지금 상태, 키 KEPCO_API_KEY).
 * 시·도 이름(주소 일부)으로만 물을 수 있어서, 화면에 걸친 시·도만 받고 2분 동안 기억. 충전기를 충전소(csId)별로 묶어 점 하나로
 */
object EvCharger {
    const val LAYER = "ev"
    private const val URL = "https://bigdata.kepco.co.kr/openapi/v1/EVchargeManage.do"
    private const val MAX = 200

    data class Charger(val id: String, val station: String, val stationName: String, val addr: String, val lat: Double, val lng: Double, val fast: Boolean, val state: String)

    data class Station(val id: String, val name: String, val addr: String, val lat: Double, val lng: Double, val chargers: List<Charger>) {
        val free: Int get() = chargers.count { it.state == "1" }
    }

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

    /** 충전기 상태 코드 → 말 (1 충전 가능, 2 충전 중, 3 고장·점검, 그 밖은 통신 문제 등으로 모름) */
    fun stateName(code: String) = when (code) { "1" -> "충전 가능"; "2" -> "충전 중"; "3" -> "점검 중"; else -> "상태 모름" }

    fun parse(body: String?): List<Charger> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? Map<*, *> ?: return emptyList()
        return (root["data"] as? List<*>).orEmpty().mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val s = { k: String -> m[k]?.toString()?.trim().orEmpty() }
            val lat = s("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = s("longi").toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in 33.0..39.0 || lng !in 124.0..132.0) return@mapNotNull null
            Charger(s("cpId"), s("csId").ifEmpty { "$lat,$lng" }, s("csNm").ifEmpty { "충전소" }, s("addr"), lat, lng, s("chargeTp") == "2", s("cpStat"))
        }
    }

    fun stations(chargers: List<Charger>): List<Station> = chargers.groupBy { it.station }.map { (id, list) ->
        val f = list.first()
        Station(id, f.stationName, f.addr, f.lat, f.lng, list)
    }

    /** 지도 점: "충전 가능 3/6대 · 급속 2대 · 서울특별시 …". 점에 빈 충전기 수를 보이려고 id 둘째 칸에 넣음 */
    fun feature(st: Station): ItsRoadFeature {
        val fast = st.chargers.count { it.fast }
        val down = st.chargers.count { it.state == "3" }
        return ItsRoadFeature(
            "$LAYER:${st.free}:${st.id}", LAYER, st.name,
            listOfNotNull(
                "충전 가능 ${st.free}/${st.chargers.size}대",
                if (fast > 0) "급속 ${fast}대" else "완속",
                if (down > 0) "점검 중 ${down}대" else null,
                "한전", st.addr.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            st.lat, st.lng,
        )
    }

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build() }
    private val cache = HashMap<String, Pair<Long, List<Charger>>>()

    private suspend fun region(name: String): List<Charger> {
        synchronized(cache) { cache[name]?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.let { return it.second } }
        val key = com.eunho.tagohub.BuildConfig.KEPCO_API_KEY.trim()
        if (key.isEmpty()) return emptyList()
        val got = withContext(Dispatchers.IO) {
            runCatching {
                val url = URL.toHttpUrl().newBuilder().addQueryParameter("addr", name).addQueryParameter("apiKey", key).addQueryParameter("returnType", "json").build()
                client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) parse(r.body?.string()) else null }
            }.getOrNull()
        } ?: return synchronized(cache) { cache[name]?.second.orEmpty() }
        synchronized(cache) { cache[name] = System.currentTimeMillis() to got }
        return got
    }

    /** 화면 범위의 충전소 (넓게 보면 안 받음: 시·도 셋까지) */
    suspend fun features(s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> = coroutineScope {
        val names = REGIONS.filter { it.s <= n && it.n >= s && it.w <= e && it.e >= w }.takeIf { it.size <= 3 }?.flatMap { it.names }
            ?: return@coroutineScope emptyList()
        val all = names.map { async { region(it) } }.awaitAll().flatten().distinctBy { it.station + "|" + it.id }
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        stations(all.filter { it.lat in s..n && it.lng in w..e })
            .sortedBy { hypot(it.lat - cla, (it.lng - clo) * 0.8) }.take(MAX).map(::feature)
    }
}
