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
 *  - 실시간 값: 서버 /w/topis/… (src/seoultraffic.js) — 링크 속도(5분 저장), 돌발, 공영자전거 대여소(1분 저장)
 * 지도 층(서울 도로 소통·서울 돌발·공영자전거)과 운전 길안내(앞쪽 정체 구간·돌발)에 씀. (교통량 지점 층은 쓰는 사람이 적어 뺐음)
 */
object SeoulTopis {
    const val FLOW = "seoul_flow"
    const val ACC = "seoul_acc"
    /**
     * 공영자전거 대여소: 서울 따릉이(bikeList) + 서울 밖 10곳(한국지역정보개발원 전국 공영자전거: 대전 타슈·세종 어울링·창원 누비자 등).
     * + 실시간 대수 없는 대여소(전국자전거대여소표준데이터: 세종·여수·순천·김해 등, 대수 칸 "-", 운영시간·요금은 info).
     * id = "seoul_bike:<대여 가능 대수 또는 ->:<대여소 ID>" (서울 밖 대여소 ID 는 "지역코드:ID", 표준데이터는 "std:순번"). 층 이름은 예전 그대로(저장된 켜기 값 유지)
     */
    const val BIKE = "seoul_bike"
    /** 서버 /w/topis 와 같은 길로 받는 층 (주차장은 서버 정적 파일, data/Parking.kt) */
    val LAYERS = setOf(FLOW, ACC, BIKE, Parking.LAYER) + Amenity.LAYERS

    /** [bikes] 가 null 이면 실시간 대수를 모르는 대여소 (표준데이터), [info] 는 운영시간·요금·전화 */
    data class Bike(val id: String, val name: String, val lat: Double, val lng: Double, val bikes: Int?, val racks: Int, val info: String? = null)
    /** 예전 「서울 교통량」 층 (뺐음: 저장된 켜기 값을 지우는 데만 씀) */
    const val REMOVED_VOL = "seoul_vol"

    data class Link(
        val id: String, val road: String, val from: String, val to: String, val dist: Int, val div: String,
        val pts: List<LatLng>, val s: Double, val w: Double, val n: Double, val e: Double,
    )

    data class Acc(val id: String, val type: String?, val sub: String?, val lat: Double, val lng: Double, val info: String, val from: String?, val to: String?)

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

    /**
     * 한 요청에 묻는 링크 수. Workers 는 한 요청에서 바깥 연결을 동시에 6개만 열어서(나머지는 기다림)
     * 6개씩 나눠 여러 요청을 한꺼번에 보내면 서울 API 한 차례 시간에 다 받음
     */
    private const val SPEED_GROUP = 6
    /** 한꺼번에 보내는 요청 수 */
    private const val SPEED_PARALLEL = 48

    /** 링크별 실시간 속도(km/h). 6개씩 나눠 여러 요청을 한꺼번에 보냄 (정렬해 묶어 같은 묶음은 서버 저장을 같이 씀) */
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

    /** 공영자전거 대여소 전부 (서버가 1분 저장) */
    suspend fun bikes(): List<Bike> = parseBikes(get("bike"))

    /** 서버 응답 [{id, name, lat, lng, bikes, racks, info}] 읽기 (bikes 가 null 이면 대수 모름) (MiniJson: 단위 테스트에서도 돌게) */
    fun parseBikes(body: String?): List<Bike> {
        val root = body?.let { runCatching { com.eunho.tagohub.core.MiniJson.parse(it) }.getOrNull() } ?: return emptyList()
        return com.eunho.tagohub.core.MiniJson.findRows(root, "id").mapNotNull { r ->
            val id = r.str("id") ?: return@mapNotNull null
            val lat = r.double("lat") ?: return@mapNotNull null
            val lng = r.double("lng") ?: return@mapNotNull null
            Bike(id, r.str("name") ?: "공영자전거 대여소", lat, lng, r.int("bikes"), r.int("racks") ?: 0, r.str("info"))
        }
    }

    /** 지도 점: 글자는 대여 가능 대수(모르면 "-"), 누르면 "대여 가능 7대 · 거치대 15" (모르면 "실시간 대수 없음 · 거치대 10 · 24시간 · 무료") */
    fun bikeFeature(b: Bike) = ItsRoadFeature(
        "$BIKE:${b.bikes ?: "-"}:${b.id}", BIKE, b.name,
        (if (b.bikes == null) "실시간 대수 없음" else "대여 가능 ${b.bikes}대") + (if (b.racks > 0) " · 거치대 ${b.racks}" else "") +
            (b.info?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), b.lat, b.lng,
    )

    /** 사고·공사 등 이름 (유형 · 세부) */
    fun accName(a: Acc): String = listOfNotNull(a.type, a.sub?.takeIf { it != a.type }).joinToString(" · ").ifBlank { "돌발" }

    /** 지도 층 (화면 범위) */
    suspend fun features(context: Context, layer: String, s: Double, w: Double, n: Double, e: Double): List<ItsRoadFeature> {
        // 공영자전거는 서울 밖(대전·세종·창원 등)도 있음
        if (layer == BIKE) return bikes().filter { it.lat in s..n && it.lng in w..e }.map(::bikeFeature)
        if (layer == Parking.LAYER) return Parking.features(s, w, n, e)
        if (layer in Amenity.LAYERS) return Amenity.features(layer, s, w, n, e)
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
            else -> emptyList()
        }
    }

    /**
     * [lat],[lng] 에서 [maxM] 미터 안을 서울 링크가 지나는지. 서울 도로 소통을 켜면 같은 도로(경부·경인고속도로 서울 구간)의
     * 고속도로 소통 선을 빼서 두 층이 겹쳐 그려지지 않게 함
     */
    fun covers(context: Context, lat: Double, lng: Double, maxM: Double = COVER_M): Boolean = covers(links(context), lat, lng, maxM)

    fun covers(ls: List<Link>, lat: Double, lng: Double, maxM: Double = COVER_M): Boolean {
        val pad = maxM / 111_000.0 * 1.5
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        for (l in ls) {
            if (lat < l.s - pad || lat > l.n + pad || lng < l.w - pad * 1.3 || lng > l.e + pad * 1.3) continue
            for (i in 0 until l.pts.size - 1) {
                val ax = (l.pts[i].lng - lng) * kx
                val ay = (l.pts[i].lat - lat) * ky
                val vx = (l.pts[i + 1].lng - lng) * kx - ax
                val vy = (l.pts[i + 1].lat - lat) * ky - ay
                val len2 = vx * vx + vy * vy
                val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
                if (hypot(ax + vx * t, ay + vy * t) <= maxM) return true
            }
        }
        return false
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
    private const val SIDE_M = 5.0
    private const val COVER_M = 60.0
    private const val PAD = 0.003
    private const val ON_ROUTE_M = 25.0
    private const val SAME_DIR_DEG = 60.0
    private const val MAX_ROUTE_LINKS = 400
}
