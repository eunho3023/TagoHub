package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 서울 실시간 교통 (서울시 교통정보 TOPIS). 웹 site/js/topis.js 와 같은 자료·기준.
 *  - 도로 링크 모양: assets/seoul_links.json (pipeline/build_seoul_links.py, 한 방향 링크 5천여 개)
 *  - 실시간 값: 서버 /w/topis/… (src/seoultraffic.js) — 링크 속도(5분 저장), 돌발, 교통량 지점·시각별 교통량
 * 지도 층(서울 도로 소통·서울 돌발·서울 교통량)과 운전 길안내(앞쪽 정체 구간·돌발)에 씀.
 */
object SeoulTopis {
    const val FLOW = "seoul_flow"
    const val ACC = "seoul_acc"
    const val VOL = "seoul_vol"
    val LAYERS = setOf(FLOW, ACC, VOL)

    data class Link(
        val id: String, val road: String, val from: String, val to: String, val dist: Int, val div: String,
        val pts: List<LatLng>, val s: Double, val w: Double, val n: Double, val e: Double,
    )

    data class Acc(val id: String, val type: String?, val sub: String?, val lat: Double, val lng: Double, val info: String, val from: String?, val to: String?)

    data class Spot(val no: String, val name: String, val lat: Double, val lng: Double)

    private const val S = 37.41
    private const val N = 37.72
    private const val W = 126.76
    private const val E = 127.19

    fun inSeoul(s: Double, w: Double, n: Double, e: Double) = n > S && s < N && e > W && w < E

    /** 속도 → 소통 등급 1 원활·2 서행·3 정체 (TOPIS 기준: 도시고속도로 70·30, 일반도로 25·15 km/h) */
    fun grade(div: String, kmh: Int): Int = if (div == "02") {
        if (kmh >= 70) 1 else if (kmh >= 30) 2 else 3
    } else {
        if (kmh >= 25) 1 else if (kmh >= 15) 2 else 3
    }

    @Volatile private var links: List<Link>? = null

    /** 서울 도로 링크 (처음 한 번 읽음) */
    fun links(context: Context): List<Link> = links ?: synchronized(this) {
        links ?: runCatching {
            val text = context.applicationContext.assets.open("seoul_links.json").bufferedReader().use { it.readText() }
            parseLinks(text)
        }.getOrDefault(emptyList()).also { links = it }
    }

    fun parseLinks(text: String): List<Link> {
        val obj = JSONObject(text).optJSONObject("links") ?: return emptyList()
        val out = ArrayList<Link>(obj.length())
        for (id in obj.keys()) {
            val a = obj.optJSONArray(id) ?: continue
            val pts = Polyline.decode(a.optString(5), 5)
            if (pts.size < 2) continue
            out += Link(
                id, a.optString(0), a.optString(1), a.optString(2), a.optInt(3), a.optString(4), pts,
                pts.minOf { it.lat }, pts.minOf { it.lng }, pts.maxOf { it.lat }, pts.maxOf { it.lng },
            )
        }
        return out
    }

    private val client by lazy { Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    private suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): String? = withContext(Dispatchers.IO) {
        val base = DataProxy.current?.url?.trimEnd('/') ?: return@withContext null
        val url = "$base/w/topis/$path".toHttpUrlOrNull()?.newBuilder()?.apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
            ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    /** 한 요청에 묻는 링크 수 (서버 SPEED_MAX: Workers 무료 요금제의 한 요청 바깥 요청 50번 제한) */
    private const val SPEED_GROUP = 30
    /** 동시에 보내는 요청 수 */
    private const val SPEED_PARALLEL = 8

    /** 링크별 실시간 속도(km/h). 30개씩 나눠 여러 요청을 동시에 보냄 (정렬해 묶어 같은 묶음은 서버 저장을 같이 씀) */
    suspend fun speeds(ids: List<String>): Map<String, Int> = coroutineScope {
        val out = HashMap<String, Int>()
        for (batch in ids.distinct().sorted().chunked(SPEED_GROUP).chunked(SPEED_PARALLEL)) {
            val bodies = batch.map { part -> async { get("traffic", listOf("links" to part.joinToString(","))) } }.awaitAll()
            for (body in bodies) {
                val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                for (k in o.keys()) o.optJSONArray(k)?.let { out[k] = it.optInt(0) }
            }
        }
        out
    }

    suspend fun accidents(): List<Acc> {
        val arr = get("acc")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Acc(o.optString("id"), o.optString("type").ifBlank { null }, o.optString("sub").ifBlank { null }, o.optDouble("lat"), o.optDouble("lng"),
                o.optString("info"), o.optString("from").ifBlank { null }, o.optString("to").ifBlank { null })
        }.filter { !it.lat.isNaN() && !it.lng.isNaN() }
    }

    suspend fun spots(): List<Spot> {
        val arr = get("spots")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Spot(o.optString("no"), o.optString("name"), o.optDouble("lat"), o.optDouble("lng"))
        }.filter { !it.lat.isNaN() }
    }

    /** 지점·시각별 교통량 (들어옴, 나감) */
    suspend fun volume(spot: String, ymd: String, hh: String): Pair<Int, Int>? {
        val o = get("vol", listOf("spot" to spot, "ymd" to ymd, "hh" to hh))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return o.optInt("in") to o.optInt("out")
    }

    /** 한국 시각 (YYYYMMDD, HH), h 시간 전 */
    private fun kstHour(h: Int): Pair<String, String> {
        val t = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusHours(h.toLong())
        return "%04d%02d%02d".format(t.year, t.monthValue, t.dayOfMonth) to "%02d".format(t.hour)
    }

    /** 사고·공사 등 이름 (유형 · 세부) */
    fun accName(a: Acc): String = listOfNotNull(a.type, a.sub?.takeIf { it != a.type }).joinToString(" · ").ifBlank { "돌발" }

    /** 지도 층 (화면 범위) */
    suspend fun features(context: Context, layer: String, s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> {
        if (!inSeoul(s, w, n, e)) return emptyList()
        return when (layer) {
            FLOW -> {
                val cla = (s + n) / 2
                val clo = (w + e) / 2
                val vis = withContext(Dispatchers.Default) {
                    links(context).filter { it.n >= s && it.s <= n && it.e >= w && it.w <= e }
                        .sortedBy { hypot((it.s + it.n) / 2 - cla, ((it.w + it.e) / 2 - clo) * 0.8) }.take(MAX_FLOW)
                }
                val sp = speeds(vis.map { it.id })
                vis.mapNotNull { l ->
                    val kmh = sp[l.id] ?: return@mapNotNull null
                    val mid = l.pts[l.pts.size / 2]
                    ItsRoadFeature(
                        "$FLOW:${grade(l.div, kmh)}:${l.id}", FLOW, l.road.ifBlank { "서울 도로" },
                        "$kmh km/h · ${l.from} → ${l.to}", mid.lat, mid.lng,
                        // 한 방향 링크라 진행 방향 오른쪽으로 비켜 상·하행이 나란히
                        path = PathMath.offsetMeters(l.pts, SIDE_M),
                    )
                }
            }
            ACC -> accidents().filter { it.lat in s..n && it.lng in w..e }.map { a ->
                val when_ = listOfNotNull(a.from?.let { "$it 부터" }, a.to?.let { "$it 까지" }).joinToString(" ")
                ItsRoadFeature("$ACC:${a.id}", ACC, accName(a), listOf(a.info.replace(Regex("\\n+"), " "), when_).filter { it.isNotBlank() }.joinToString("\n"), a.lat, a.lng)
            }
            VOL -> {
                val inBox = spots().filter { it.lat in s..n && it.lng in w..e }
                val (ymd, hh) = kstHour(1)
                inBox.take(MAX_VOL_DETAIL).map { sp ->
                    val v = volume(sp.no, ymd, hh)
                    val txt = v?.let { (i, o) -> "${hh.toInt()}시 들어옴 ${"%,d".format(i)}대 · 나감 ${"%,d".format(o)}대" } ?: "교통량 정보를 받지 못했어요"
                    ItsRoadFeature("$VOL:${sp.no}", VOL, "${sp.name} 교통량", txt, sp.lat, sp.lng)
                } + inBox.drop(MAX_VOL_DETAIL).map { sp -> ItsRoadFeature("$VOL:${sp.no}", VOL, "${sp.name} 교통량", "", sp.lat, sp.lng) }
            }
            else -> emptyList()
        }
    }

    /** 운전 길안내: 서울 돌발 (경로에 맞추는 것은 RoadAlerts) */
    suspend fun accidentHazards(): List<RoadHazard> = accidents().map { a ->
        RoadHazard(RoadAlertKind.CityIncident, accName(a), a.info.lineSequence().firstOrNull { it.isNotBlank() }, a.lat, a.lng, "seoul:${a.id}")
    }

    /**
     * 운전 길안내: 경로가 지나는 서울 링크(같은 방향만) 중 정체(등급 3)인 구간의 시작점.
     * 경로를 받을 때 한 번 링크 속도를 받아 맞춤 (서울 밖 경로면 빈 목록)
     */
    suspend fun routeCongestion(context: Context, route: NavRoute): List<RoadHazard> {
        val pts = route.points
        if (pts.size < 2) return emptyList()
        val s = pts.minOf { it.lat } - PAD
        val n = pts.maxOf { it.lat } + PAD
        val w = pts.minOf { it.lng } - PAD
        val e = pts.maxOf { it.lng } + PAD
        if (!inSeoul(s, w, n, e)) return emptyList()
        val on = withContext(Dispatchers.Default) {
            links(context).filter { it.n >= s && it.s <= n && it.e >= w && it.w <= e }.mapNotNull { l ->
                val mid = l.pts[l.pts.size / 2]
                val p = route.snap(mid.lat, mid.lng, ON_ROUTE_M) ?: return@mapNotNull null
                val a = pts[p.segment]
                val b = pts[minOf(p.segment + 1, pts.lastIndex)]
                if (angle(bearing(l.pts.first(), l.pts.last()), bearing(a, b)) > SAME_DIR_DEG) return@mapNotNull null
                p.along to l
            }.sortedBy { it.first }.take(MAX_ROUTE_LINKS)
        }
        if (on.isEmpty()) return emptyList()
        val sp = speeds(on.map { it.second.id })
        val out = ArrayList<RoadHazard>()
        var inJam = false
        for ((_, l) in on) {
            val kmh = sp[l.id]
            val jam = kmh != null && grade(l.div, kmh) == 3
            if (jam && !inJam) {
                val start = l.pts.first()
                out += RoadHazard(RoadAlertKind.CityCongest, "${l.road.ifBlank { "서울 도로" }} 정체 구간", "$kmh km/h", start.lat, start.lng, "seoulJam:${l.id}")
            }
            inJam = jam
        }
        return out
    }

    private fun bearing(a: LatLng, b: LatLng): Double =
        Math.toDegrees(atan2((b.lng - a.lng) * cos(Math.toRadians(a.lat)), b.lat - a.lat))

    private fun angle(x: Double, y: Double): Double {
        val d = abs(x - y) % 360.0
        return if (d > 180) 360 - d else d
    }

    private const val MAX_FLOW = 180
    private const val MAX_VOL_DETAIL = 12
    private const val SIDE_M = 5.0
    private const val PAD = 0.003
    private const val ON_ROUTE_M = 25.0
    private const val SAME_DIR_DEG = 60.0
    private const val MAX_ROUTE_LINKS = 400
}
