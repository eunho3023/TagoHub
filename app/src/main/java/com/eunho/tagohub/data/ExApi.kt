package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 한국도로공사 고속도로 공공데이터 포털(data.ex.co.kr) OpenAPI.
 * 목록·요청변수·출력변수는 assets/ex_catalog.json (pipeline/build_ex_catalog.py 가 사이트에서 모음).
 */
data class ExParam(val code: String, val label: String, val required: Boolean)

data class ExApi(
    val id: String,
    val service: String,
    val name: String,
    val url: String,
    val params: List<ExParam>,
    /** 출력변수 코드 → 한글 이름 */
    val outputs: Map<String, String>,
)

data class ExResult(val rows: List<Map<String, String>>, val total: Int?)

object ExCatalog {
    /** assets/ex_catalog.json 내용 → API 목록 */
    fun parse(json: String): List<ExApi> {
        val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"]?.toString()?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val req = (m["req"] as? List<*>).orEmpty().mapNotNull { r ->
                val s = r?.toString() ?: return@mapNotNull null
                val code = s.substringBefore(':')
                ExParam(code.removeSuffix("*"), s.substringAfter(':', ""), code.endsWith("*"))
            }
            val out = (m["out"] as? List<*>).orEmpty().mapNotNull { o ->
                val s = o?.toString() ?: return@mapNotNull null
                s.substringBefore(':') to s.substringAfter(':', "")
            }.toMap()
            ExApi(
                id = m["id"]?.toString().orEmpty(),
                service = m["svc"]?.toString().orEmpty().ifBlank { "기타" },
                name = m["name"]?.toString().orEmpty(),
                url = url,
                params = req,
                outputs = out,
            )
        }
    }

    /**
     * "고속도로 정보" 화면에 보여줄 API (93개 중 이동·운전에 쓸모 있는 것). 나머지(전자조달 계약, 지적재산권, 지가, 생활기상지수,
     * 교통량 통계 등)는 사용자에게 필요 없어서 숨김. 지도 레이어(휴게소·영업소·IC·전광판·정체)는 따로 호출하므로 영향 없음.
     * 번호는 assets/ex_catalog.json 의 id.
     */
    val CURATED_IDS = setOf(
        "0620",                                                         // 영업소간 통행요금
        "0147", "0115",                                                 // 실시간·평균 영업소간 통행시간
        "0102", "0103", "0405", "0406", "0303",                         // 교통량·소통·정체·교통예보
        "0614", "0613",                                                 // 공사현황·도로 차단 계획
        "0611", "0148", "0119",                                         // 실시간 문자정보·이력, 전광판 표출 내용
        "0615", "0317", "0316", "0506", "0501", "0505", "0507",         // 휴게소 코드·시설·매장·이벤트
        "0503", "0504", "0502", "0508",                                 // 편의시설·주유소·푸드메뉴·휴게소 날씨
        "0312", "0313", "0314",                                         // 주유 가격·알뜰·LPG
        "0125", "0126", "0129",                                         // 버스전용차로·LCS(차로제어) 시행
        "0621", "0623", "0304",                                         // 영업소 차로·하이패스, 안전주행 콘텐츠
    )

    /** 응답에 같이 오는 공통 항목 (카드에서는 숨김) */
    val META_FIELDS = setOf("code", "message", "count", "pageNo", "numOfRows", "pageSize", "pagingYn")
}

object ExParser {
    /** 응답 JSON → 첫 번째 배열(행 목록). code 가 SUCCESS 가 아니면 메시지와 함께 오류 */
    fun parse(body: String): ExResult {
        val text = body.trim()
        if (!text.startsWith("{")) {
            throw IOException("한국도로공사 API가 JSON이 아닌 응답을 보냈습니다. (필수 요청변수가 빠졌을 수 있어요)")
        }
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IOException("응답 형식이 올바르지 않습니다.")
        val code = root["code"]?.toString()
        val message = root["message"]?.toString().orEmpty()
        if (code != null && !code.equals("SUCCESS", ignoreCase = true)) {
            throw IOException(message.ifBlank { "한국도로공사 API 오류 ($code)" })
        }
        val rows = root.values.firstNotNullOfOrNull { v ->
            (v as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is Map<*, *> } }
        }.orEmpty().map { r ->
            (r as Map<*, *>).entries.mapNotNull { (k, v) ->
                val value = v?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                k.toString() to value
            }.toMap()
        }
        return ExResult(rows, root["count"]?.toString()?.toDoubleOrNull()?.toInt())
    }
}

class ExRepository(
    private val key: String = BuildConfig.EX_API_KEY.trim(),
    private val client: OkHttpClient = httpClient,
) {
    suspend fun call(api: ExApi, params: Map<String, String>, page: Int = 1, rows: Int = 30): ExResult =
        call(api.url, params, page, rows)

    suspend fun call(path: String, params: Map<String, String> = emptyMap(), page: Int = 1, rows: Int = 30): ExResult =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw IOException(com.eunho.tagohub.core.DevMode.msg("local.properties에 EX_API_KEY를 설정하세요. (한국도로공사 인증키)"))
            val url = (BASE + path).toHttpUrl().newBuilder().apply {
                addQueryParameter("key", key)
                addQueryParameter("type", "json")
                addQueryParameter("numOfRows", rows.toString())
                addQueryParameter("pageNo", page.toString())
                params.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v.trim()) }
            }.build()
            client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("한국도로공사 API HTTP ${r.code}")
                ExParser.parse(text)
            }
        }

    /**
     * 전체 목록 모으기. 이 API 들은 한 쪽에 최대 99건만 주고(요청 개수를 키워도 같음), 일부(IC 등)는 한 번에 다 줌.
     * → count 만큼 모일 때까지 쪽을 넘기되, 새 행이 없거나 [MAX_PAGES] 쪽이면 멈춤.
     */
    suspend fun all(path: String, params: Map<String, String> = emptyMap()): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        var page = 1
        while (page <= MAX_PAGES) {
            val res = call(path, params, page = page, rows = PAGE_ROWS)
            if (res.rows.isEmpty()) break
            out += res.rows
            val total = res.total ?: break
            if (out.size >= total || res.rows.size >= total) break
            page++
        }
        return out
    }

    private companion object {
        const val PAGE_ROWS = 99
        const val MAX_PAGES = 60
        const val BASE = "https://data.ex.co.kr"
        const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
        val httpClient: OkHttpClient = Http.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}

/** 지도에 점으로 찍을 수 있는 한국도로공사 데이터 (휴게소·영업소·IC/JCT·전광표지판) */
object ExMapLayers {
    const val REST = "ex_rest"
    const val TOLL = "ex_toll"
    const val IC = "ex_ic"
    const val VMS = "ex_vms"
    const val CONGEST = "ex_congest"
    /** 고속도로 돌발(사고·작업·강우 등 문자정보) */
    const val BURST = "ex_burst"
    /** 안전주행 안내(작업 중·사고·낙하물 등 앞쪽 주의) */
    const val SAFE = "ex_safe"
    /** 고속도로 소통: 모든 구간의 속도를 색으로 (초록 원활·주황 서행·빨강 정체) */
    const val FLOW = "ex_flow"
    val IDS = listOf(REST, TOLL, IC, VMS, CONGEST, BURST, SAFE, FLOW)

    /** VDS(차량 검지기) 위치 파일 assets/ex_vds.json 내용을 읽는 방법. 앱 시작 때 지정 (pipeline/build_ex_vds.py 가 만듦) */
    @Volatile var vdsJson: (() -> String)? = null
    private var vdsCache: Map<String, Pair<Double, Double>>? = null

    /** 위치 목록은 10분, 실시간 정보(전광판 문구·정체)는 짧게 */
    private fun cacheMs(layer: String) = when (layer) {
        VMS -> 3 * 60_000L
        CONGEST -> 2 * 60_000L
        BURST -> 2 * 60_000L
        FLOW -> 2 * 60_000L
        SAFE -> 3 * 60_000L
        else -> 10 * 60_000L
    }
    private val cache = HashMap<String, Pair<Long, List<ItsRoadFeature>>>()

    /** 큰 목록(수백~천 건)이라 10분 동안 메모리에 둠 */
    suspend fun features(layer: String, repo: ExRepository = ExRepository()): List<ItsRoadFeature> {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[layer]?.takeIf { now - it.first < cacheMs(layer) }?.let { return it.second } }
        val list = when (layer) {
            REST -> rest(repo.all("/openapi/locationinfo/locationinfoRest"))
            TOLL -> toll(repo.all("/openapi/locationinfo/locationinfoUnit"))
            IC -> ic(repo.all("/openapi/locationinfo/locationinfoIc"))
            VMS -> vms(repo.all("/openapi/vms/vmsMessageSrch"))
            CONGEST -> congest(repo.all("/openapi/odtraffic/trafficAmountByCongest"), vdsCoords())
            BURST -> burst(repo.all("/openapi/burstInfo/realTimeSms"))
            FLOW -> flowFeatures(repo.all("/openapi/odtraffic/trafficAmountByRealtime"), vdsCoords())
            SAFE -> safe(repo.all("/openapi/safeDriving/safeDrivingSupport"))
            else -> emptyList()
        }
        synchronized(cache) { cache[layer] = now to list }
        return list
    }

    /** {"VDS ID": [위도, 경도, "노선"]} → 아이디별 좌표 */
    private fun vdsCoords(): Map<String, Pair<Double, Double>> {
        vdsCache?.let { return it }
        val text = vdsJson?.invoke() ?: return emptyMap()
        return parseVds(text).also { vdsCache = it }
    }

    fun parseVds(json: String): Map<String, Pair<Double, Double>> =
        (MiniJson.parse(json) as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val l = v as? List<*> ?: return@mapNotNull null
            val la = l.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val lo = l.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            k.toString() to (la to lo)
        }.toMap()

    /**
     * 실시간 정체 구간: API 는 정체(등급 3)인 구간만 주고 좌표가 없어서 VDS ID 로 위치를 찾음. 위치를 모르는 VDS 는 건너뜀.
     * 같은 구간(콘존)이 여러 VDS 로 오면 가장 느린 것 하나만.
     */
    fun congest(rows: List<Map<String, String>>, coords: Map<String, Pair<Double, Double>>): List<ItsRoadFeature> =
        rows.groupBy { it["conzoneId"] ?: it["vdsId"] }.values.mapNotNull { group ->
            val r = group.minByOrNull { it["speed"]?.toDoubleOrNull() ?: Double.MAX_VALUE } ?: return@mapNotNull null
            val pos = coords[r["vdsId"]] ?: return@mapNotNull null
            val hh = r["stdHour"]?.takeIf { it.length >= 4 }?.let { "${it.take(2)}:${it.substring(2, 4)} 기준" }
            ItsRoadFeature(
                id = "$CONGEST:${r["conzoneId"] ?: r["vdsId"]}",
                layer = CONGEST,
                title = listOfNotNull(r["routeName"], r["conzoneName"]).joinToString(" ") + " 정체",
                details = listOfNotNull(
                    r["speed"]?.let { "속도 ${it}km/h" },
                    r["updownTypeCode"]?.let { d -> if (d == "S") "기점 방향" else "종점 방향" },
                    hh,
                ).joinToString("\n"),
                lat = pos.first,
                lng = pos.second,
            )
        }

    /**
     * 실시간 돌발 문자정보: 좌표(latitude 가 위도, altitude 가 경도 — 이름과 달리)가 있는 것만 지도에 찍음.
     * 이벤트/홍보 문구(상황실 전화 안내 등)는 지도에 쓸모가 없어 뺀다.
     */
    fun burst(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        if (r["accType"] == "이벤트/홍보") return@mapNotNull null
        val lat = lat(r["latitude"]) ?: return@mapNotNull null
        val lng = lng(r["altitude"]) ?: return@mapNotNull null
        val text = r["smsText"]?.replace("*", "")?.trim().orEmpty()
        ItsRoadFeature(
            id = "$BURST:${r["nosunNM"]}:${r["seriesNM"] ?: "$lat,$lng"}",
            layer = BURST,
            title = listOfNotNull(r["accType"], r["roadNM"]).joinToString(" · ").ifBlank { "고속도로 돌발" },
            details = listOfNotNull(
                text.takeIf { it.isNotEmpty() },
                r["accPointNM"]?.takeIf { it.isNotBlank() }?.let { "위치: $it" },
                r["startEndTypeCode"]?.let { "방향: $it" },
                r["lateLength"]?.let { "정체 ${it}m" },
                r["accProcessNM"]?.let { "상태: $it" },
                listOfNotNull(r["accDate"], r["accHour"]?.take(5)).joinToString(" ").takeIf { it.isNotBlank() },
            ).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    /**
     * 고속도로 소통 전체: 구간(콘존·방향)마다 가장 느린 검지기 하나의 속도와 소통등급(1 원활·2 서행·3 정체)을 점으로.
     * 지도에서는 등급 색으로 칠한다. id 는 "ex_flow:{등급}:{구간}:{방향}". 위치를 모르는 검지기는 건너뜀.
     */
    fun flow(rows: List<Map<String, String>>, coords: Map<String, Pair<Double, Double>>): List<ItsRoadFeature> =
        rows.filter { it["grade"] in setOf("1", "2", "3") }
            .groupBy { (it["conzoneId"] ?: it["vdsId"]) + ":" + (it["updownTypeCode"] ?: "") }.values.mapNotNull { group ->
                val r = group.minByOrNull { it["speed"]?.toDoubleOrNull() ?: Double.MAX_VALUE } ?: return@mapNotNull null
                val pos = coords[r["vdsId"]] ?: group.firstNotNullOfOrNull { coords[it["vdsId"]] } ?: return@mapNotNull null
                val grade = r["grade"] ?: return@mapNotNull null
                ItsRoadFeature(
                    id = "$FLOW:$grade:${r["conzoneId"] ?: r["vdsId"]}:${r["updownTypeCode"].orEmpty()}",
                    layer = FLOW,
                    title = listOfNotNull(r["routeName"], r["conzoneName"]).joinToString(" "),
                    details = listOfNotNull(
                        r["speed"]?.let { "속도 ${it}km/h" },
                        r["timeAvg"]?.let { "통행시간 ${it}초" },
                        r["updownTypeCode"]?.let { d -> if (d == "S") "기점 방향" else "종점 방향" },
                        when (grade) { "1" -> "소통 원활"; "2" -> "서행"; else -> "정체" },
                    ).joinToString("\n"),
                    lat = pos.first,
                    lng = pos.second,
                )
            }

    /** 이웃한 검지기끼리 이만큼(m) 넘게 떨어져 있으면 사이를 선으로 잇지 않음 (정보가 없는 구간을 가로질러 긋지 않게) */
    private const val FLOW_MAX_GAP_M = 6_000.0

    /** 한 선이 이 점 수를 넘으면 끊음 (화면에 보이는 만큼만 그리게, 긴 도로 하나가 통째로 안 보이는 일 방지) */
    private const val FLOW_MAX_POINTS = 9

    /**
     * 고속도로 소통을 도로 위의 색 선으로. 검지기 ID(노선·방향 + 순번)가 같은 노선·방향끼리 순번대로 이어지므로,
     * 이웃한 두 검지기 사이를 가운데에서 반씩 나눠 각자 자기 소통등급 색으로 칠한다. 선으로 못 잇는 검지기(이웃이 없음)는 [flow] 의 점으로.
     */
    fun flowFeatures(rows: List<Map<String, String>>, coords: Map<String, Pair<Double, Double>>): List<ItsRoadFeature> {
        val (lines, used) = flowLines(rows, coords)
        return lines + flow(rows.filter { it["vdsId"] !in used }, coords)
    }

    fun flowLines(rows: List<Map<String, String>>, coords: Map<String, Pair<Double, Double>>): Pair<List<ItsRoadFeature>, Set<String>> {
        // 검지기마다 가장 나쁜 등급(같으면 가장 느린 것) 한 줄
        val byVds = rows.filter { it["grade"] in setOf("1", "2", "3") && (it["vdsId"]?.length ?: 0) > 5 && it["vdsId"] in coords }
            .groupBy { it["vdsId"]!! }
            .mapValues { (_, g) -> g.maxByOrNull { (it["grade"]?.toIntOrNull() ?: 0) * 1000 - (it["speed"]?.toIntOrNull() ?: 0) }!! }
        val out = ArrayList<ItsRoadFeature>()
        val used = HashSet<String>()

        for ((prefix, ids) in byVds.keys.groupBy { it.dropLast(5) }) {
            val chain = ids.sorted()
            var path = ArrayList<LatLng>()
            var grade = 0
            var first: Map<String, String>? = null
            var part = 0
            fun flush() {
                if (path.size >= 2 && first != null) {
                    val r = first!!
                    val mid = path[path.size / 2]
                    out += ItsRoadFeature(
                        id = "$FLOW:$grade:$prefix:${part++}",
                        layer = FLOW,
                        title = listOfNotNull(r["routeName"], r["conzoneName"]).joinToString(" "),
                        details = listOfNotNull(
                            r["speed"]?.let { "속도 ${it}km/h" },
                            r["updownTypeCode"]?.let { d -> if (d == "S") "기점 방향" else "종점 방향" },
                            when (grade) { 1 -> "소통 원활"; 2 -> "서행"; else -> "정체" },
                        ).joinToString("\n"),
                        lat = mid.lat, lng = mid.lng, path = path.toList(),
                    )
                }
                path = ArrayList()
                first = null
            }
            fun add(g: Int, row: Map<String, String>, pts: List<LatLng>) {
                if (path.isNotEmpty() && (g != grade || path.size >= FLOW_MAX_POINTS || path.last() != pts.first())) flush()
                if (path.isEmpty()) { grade = g; first = row; path.add(pts.first()) }
                path.addAll(pts.drop(1))
            }
            for (i in 0 until chain.size - 1) {
                val a = chain[i]
                val b = chain[i + 1]
                val pa = coords[a]!!
                val pb = coords[b]!!
                if (com.eunho.tagohub.core.Geo.meters(pa.first, pa.second, pb.first, pb.second) > FLOW_MAX_GAP_M) { flush(); continue }
                val ra = byVds[a]!!
                val rb = byVds[b]!!
                val mid = LatLng((pa.first + pb.first) / 2, (pa.second + pb.second) / 2)
                add(ra["grade"]!!.toInt(), ra, listOf(LatLng(pa.first, pa.second), mid))
                add(rb["grade"]!!.toInt(), rb, listOf(mid, LatLng(pb.first, pb.second)))
                used += a
                used += b
            }
            flush()
        }
        return out to used
    }

    /** 안전주행 안내: 같은 사건(incId)은 메시지를 받는 곳마다 여러 줄로 오므로 사건 위치(startX·startY) 하나로 합침 */
    fun safe(rows: List<Map<String, String>>): List<ItsRoadFeature> =
        rows.filter { !it["incId"].isNullOrBlank() }.groupBy { it["incId"] }.values.mapNotNull { group ->
            val r = group.first()
            val lat = lat(r["startY"]) ?: return@mapNotNull null
            val lng = lng(r["startX"]) ?: return@mapNotNull null
            val at = r["analDtmc"]?.takeIf { it.length >= 12 }?.let { "${it.substring(4, 6)}.${it.substring(6, 8)} ${it.substring(8, 10)}:${it.substring(10, 12)} 기준" }
            ItsRoadFeature(
                id = "$SAFE:${r["incId"]}",
                layer = SAFE,
                title = r["contentMsg"]?.trim().orEmpty().ifBlank { "안전주행 안내" },
                details = listOfNotNull(
                    r["routeName"]?.let { n -> "노선: $n" + (r["updownDiv"]?.let { d -> if (d == "S") " (기점 방향)" else " (종점 방향)" }.orEmpty()) },
                    at,
                ).joinToString("\n"),
                lat = lat,
                lng = lng,
            )
        }

    private fun lat(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 30.0..40.0 }
    private fun lng(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 123.0..133.0 }

    fun rest(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$REST:${r["stdRestCd"] ?: r["unitCode"]}",
            layer = REST,
            title = r["unitName"].orEmpty().ifBlank { "휴게소" },
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    fun toll(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$TOLL:${r["unitCode"]?.trim()}",
            layer = TOLL,
            title = r["unitName"].orEmpty().ifBlank { "영업소" } + " 영업소",
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    fun ic(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["yValue"]) ?: return@mapNotNull null
        val lng = lng(r["xValue"]) ?: return@mapNotNull null
        ItsRoadFeature(
            id = "$IC:${r["icCode"]}",
            layer = IC,
            title = r["icName"].orEmpty().ifBlank { "IC·JCT" },
            details = listOfNotNull(r["routeName"]?.let { "노선: $it" }).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    /** 전광표지판: 이 API 는 xCoord 가 위도, yCoord 가 경도 (이름과 반대) */
    fun vms(rows: List<Map<String, String>>): List<ItsRoadFeature> = rows.mapNotNull { r ->
        val lat = lat(r["xCoord"]) ?: return@mapNotNull null
        val lng = lng(r["yCoord"]) ?: return@mapNotNull null
        val message = listOfNotNull(r["vmsMessage"], r["vmsMessage2"]).map { it.trim() }.filter { it.isNotEmpty() }
        ItsRoadFeature(
            id = "$VMS:${r["vmsId"]}",
            layer = VMS,
            title = message.firstOrNull() ?: "전광표지판",
            details = listOfNotNull(
                message.drop(1).takeIf { it.isNotEmpty() }?.joinToString("\n"),
                r["routeName"]?.let { n -> "노선: $n" + (r["updownType"]?.let { d -> if (d == "E") " (하행)" else " (상행)" }.orEmpty()) },
                r["centerName"],
            ).joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }
}
