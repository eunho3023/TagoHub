package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 주차장 (전국주차장정보표준데이터 1만 7천여 곳: 이름·면 수·요금·운영시간·전화). 웹 site/js/parking.js 와 같은 자료.
 * 서버 정적 파일 parking/<칸>.json (pipeline/build_parking.mjs, 위도·경도 0.2도 칸) 중 화면에 걸친 칸만 받고, 받은 칸은 기억.
 */
object Parking {
    const val LAYER = "parking"
    /** 한 번에 보여 줄 수 (화면 가운데에서 가까운 것부터) */
    private const val MAX = 200
    /** 한 번에 받는 칸 수 (넓게 보면 안 받음) */
    private const val MAX_TILES = 12

    data class Lot(
        val id: String, val name: String, val lat: Double, val lng: Double,
        val spaces: Int, val kind: String, val fee: String, val hours: String, val phone: String,
    )

    /** 칸 이름 (pipeline/build_parking.mjs 와 같음) */
    fun tile(lat: Double, lng: Double) = "${floor(lat * 5).toInt()}_${floor(lng * 5).toInt()}"

    /** 화면 범위에 걸친 칸 이름들 */
    fun tilesIn(s: Double, w: Double, n: Double, e: Double): List<String> {
        val out = ArrayList<String>()
        for (a in floor(s * 5).toInt()..floor(n * 5).toInt()) for (o in floor(w * 5).toInt()..floor(e * 5).toInt()) out += "${a}_$o"
        return out
    }

    /** 칸 자료 [[이름, 위도, 경도, 면 수, 구분, 요금, 운영시간, 전화]] 읽기 (MiniJson: 단위 테스트에서도 돌게) */
    fun parseTile(tile: String, body: String?): List<Lot> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? List<*> ?: return emptyList()
        return root.mapIndexedNotNull { i, v ->
            val a = v as? List<*> ?: return@mapIndexedNotNull null
            val s = { k: Int -> a.getOrNull(k)?.toString().orEmpty() }
            val lat = s(1).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val lng = s(2).toDoubleOrNull() ?: return@mapIndexedNotNull null
            Lot("$tile:$i", s(0).ifBlank { "주차장" }, lat, lng, s(3).toDoubleOrNull()?.toInt() ?: 0, s(4), s(5), s(6), s(7))
        }
    }

    /** 지도 점: 누르면 "공영 노외 · 169면 · 5분 250원 · 24시간 · 02-…" */
    fun feature(l: Lot) = ItsRoadFeature(
        "$LAYER:${l.id}", LAYER, l.name,
        listOf(l.kind, if (l.spaces > 0) "${l.spaces}면" else "", l.fee, l.hours, l.phone).filter { it.isNotBlank() }.joinToString(" · "),
        l.lat, l.lng,
    )

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    private var have: Set<String>? = null
    private val tiles = HashMap<String, List<Lot>>()

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val base = DataProxy.current?.url?.trimEnd('/') ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url("$base/$path").build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    /** 화면 범위의 주차장 점 */
    suspend fun features(s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> = coroutineScope {
        val index = lock.withLock {
            have ?: get("parking/index.json")?.let { body ->
                val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *>
                (root?.get("tiles") as? List<*>)?.mapNotNull { it?.toString() }?.toSet()
            }?.also { have = it }
        } ?: return@coroutineScope emptyList()
        val want = tilesIn(s, w, n, e).filter { it in index }.take(MAX_TILES)
        val lots = want.map { t ->
            async {
                lock.withLock { tiles[t] } ?: parseTile(t, get("parking/$t.json")).also { got -> if (got.isNotEmpty()) lock.withLock { tiles[t] = got } }
            }
        }.awaitAll().flatten()
        val cla = (s + n) / 2
        val clo = (w + e) / 2
        // 실시간 빈자리를 주는 주차장(한국교통안전공단)은 그 점으로 보이고, 같은 자리(약 60m)의 표준데이터 점은 뺌
        val live = ParkingLive.lots().filter { it.lat in s..n && it.lng in w..e }
        val counts = if (live.isEmpty()) emptyMap() else ParkingLive.counts()
        val near = { l: Lot -> live.any { hypot(it.lat - l.lat, (it.lng - l.lng) * 0.8) < 0.00055 } }
        val std = lots.filter { it.lat in s..n && it.lng in w..e && !near(it) }.map { (it.lat to it.lng) to feature(it) }
        val now = live.map { (it.lat to it.lng) to ParkingLive.feature(it, counts[it.id]) }
        (now + std).sortedBy { (p, _) -> hypot(p.first - cla, (p.second - clo) * 0.8) }.take(MAX).map { it.second }
    }
}

/**
 * 실시간 빈자리 주차장 (한국교통안전공단 주차정보 제공 API, 공공데이터포털 키 = TAGO_SERVICE_KEY).
 * 주차장 이름·위치·면 수는 앱 파일 parking_live.json (pipeline/build_api_files.py, 매주), 빈자리는 1분마다 한 번에 전국(약 4,700곳)을 받음
 */
object ParkingLive {
    private const val URL = "https://apis.data.go.kr/B553881/Parking_v1/PrkRealtimeInfo_v1"

    data class Lot(val id: String, val name: String, val lat: Double, val lng: Double, val spaces: Int, val owner: String, val fee: String)

    /** 빈자리·전체 면 */
    data class Count(val free: Int, val total: Int)

    /** 앱 파일 [[ID, 이름, 위도, 경도, 면 수, 공영/민영, 유료/무료]] */
    fun parseLots(text: String?): List<Lot> =
        (text?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? List<*>).orEmpty().mapNotNull { v ->
            val a = v as? List<*> ?: return@mapNotNull null
            val s = { k: Int -> a.getOrNull(k)?.toString().orEmpty() }
            val lat = s(2).toDoubleOrNull() ?: return@mapNotNull null
            val lng = s(3).toDoubleOrNull() ?: return@mapNotNull null
            if (s(0).isEmpty()) null else Lot(s(0), s(1).ifBlank { "주차장" }, lat, lng, s(4).toDoubleOrNull()?.toInt() ?: 0, s(5), s(6))
        }

    /** API 응답 → 주차장 ID 별 빈자리 */
    fun parseCounts(body: String?): Map<String, Count> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } as? Map<*, *> ?: return emptyMap()
        return (root["PrkRealtimeInfo"] as? List<*>).orEmpty().mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            val id = m["prk_center_id"]?.toString().orEmpty()
            val free = m["pkfc_Available_ParkingLots_total"]?.toString()?.toDoubleOrNull()?.toInt()
            val total = m["pkfc_ParkingLots_total"]?.toString()?.toDoubleOrNull()?.toInt()
            if (id.isEmpty() || free == null || total == null || total <= 0) null else id to Count(free.coerceIn(0, total), total)
        }.toMap()
    }

    /** 지도 점: "빈자리 12면 / 49면 · 공영 · 유료 · 실시간". 빈자리를 못 받으면 면 수만 */
    fun feature(l: Lot, c: Count?) = ItsRoadFeature(
        "${Parking.LAYER}:live:${c?.free ?: "-"}:${l.id}", Parking.LAYER, l.name,
        listOf(
            c?.let { if (it.free == 0) "만차 (${it.total}면)" else "빈자리 ${it.free}면 / ${it.total}면" } ?: if (l.spaces > 0) "${l.spaces}면" else "",
            l.owner, l.fee, if (c != null) "실시간" else "",
        ).filter { it.isNotBlank() }.joinToString(" · "),
        l.lat, l.lng,
    )

    private var stamp: String? = null
    private var cache: List<Lot> = emptyList()

    @Synchronized
    fun lots(): List<Lot> {
        val s = AssetText.stamp("parking_live.json")
        if (s != stamp) { cache = parseLots(AssetText.read("parking_live.json")); stamp = s }
        return cache
    }

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }
    private val lock = Mutex()
    private var at = 0L
    private var counts: Map<String, Count> = emptyMap()

    suspend fun counts(): Map<String, Count> = lock.withLock {
        if (System.currentTimeMillis() - at < 60_000) return@withLock counts
        val got = withContext(Dispatchers.IO) {
            runCatching {
                val raw = com.eunho.tagohub.BuildConfig.TAGO_SERVICE_KEY.trim()
                val key = if ('%' in raw) raw else java.net.URLEncoder.encode(raw, "UTF-8")
                client.newCall(Request.Builder().url("$URL?serviceKey=$key&pageNo=1&numOfRows=10000&format=2").build()).execute()
                    .use { r -> if (r.isSuccessful) parseCounts(r.body?.string()) else emptyMap() }
            }.getOrDefault(emptyMap())
        }
        if (got.isNotEmpty()) { counts = got; at = System.currentTimeMillis() }
        else at = System.currentTimeMillis() - 45_000   // 실패하면 15초 뒤 다시
        counts
    }
}
