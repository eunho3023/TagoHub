package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 지역 자체 버스 도착 API (공공데이터포털 서비스키 사용).
 * TAGO 도착정보에 방면·현재 정류장을 덧붙이거나, TAGO 가 비었을 때 대신 쓴다 ([ArrivalMerge]).
 * 동해시는 격자별 교통시설 지수 API 라서 버스 도착정보가 없어 포함하지 않는다.
 */
enum class RegionalKind(val prefix: String) {
    BUSAN("BSB"),
    /** 울산 정류장 ID 는 USB + 울산 BIS 정류장 ID 9자리 (예전엔 GGB 로 잘못 알아 울산 자료가 한 번도 붙지 않았음) */
    ULSAN("USB"),
    GWANGJU("KJB"),
    YEOSU("YEB"),
    /** 대전 정류장 ID 는 DJB + 대전 BIS 정류장 ID 7자리, 노선 ID 는 DJB + 8자리 (TAGO 와 같은 번호) */
    DAEJEON("DJB"),
}

/** 지역 API 로 물어볼 정류장 (stdId = TAGO ID 에서 접두사를 뗀 지역 API 의 정류장 ID) */
data class RegionalStop(val kind: RegionalKind, val stdId: String)

object RegionalBus {
    fun of(s: BusStop): RegionalStop? = of(s.nodeId, s.cityCode)

    fun of(nodeId: String, cityCode: String): RegionalStop? {
        val kind = when {
            nodeId.startsWith("BSB") -> RegionalKind.BUSAN
            nodeId.startsWith("USB") -> RegionalKind.ULSAN
            nodeId.startsWith("KJB") && cityCode == "24" -> RegionalKind.GWANGJU
            nodeId.startsWith("YEB") -> RegionalKind.YEOSU
            nodeId.startsWith("DJB") -> RegionalKind.DAEJEON
            else -> return null
        }
        val std = nodeId.removePrefix(kind.prefix)
        return if (std.isBlank()) null else RegionalStop(kind, std)
    }
}

/** 응답 해석 (네트워크 없이 시험할 수 있게 분리) */
object RegionalMappers {
    /** 부산: 한 줄에 앞차(1)·뒷차(2) 두 대. "운행대기" 같은 숫자 아닌 값은 도착 정보 없음 */
    fun busan(rows: List<Row>): List<BusArrival> = rows.flatMap { r ->
        (1..2).mapNotNull { n ->
            val min = r.int("min$n") ?: return@mapNotNull null
            BusArrival(
                routeId = "BSB" + r.text("lineid"),
                routeNo = r.text("lineno"),
                routeType = r.str("bustype"),
                vehicleType = if (r.str("lowplate$n") == "1") "저상버스" else null,
                prevStops = r.int("station$n"),
                arrSeconds = min * 60,
            )
        }
    }.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }

    private val ulsanRoute = Regex("""^(.*?)\s*\((.*?)\)\s*$""")

    /**
     * 울산: ROUTENM = "527(태화강역방면)" → 노선 527, 방면 태화강역. 도착시간은 초.
     * 새 주소(공공데이터포털 6310000/bis, 2026)는 ROUTENM = "725" 에 방면을 REMARK("처용마을입구 방면")로 따로 줌
     */
    fun ulsan(rows: List<Row>): List<BusArrival> = rows.mapNotNull { r ->
        val name = r.str("ROUTENM") ?: return@mapNotNull null
        val m = ulsanRoute.find(name)
        val no = m?.groupValues?.get(1)?.ifBlank { null } ?: name.trim()
        BusArrival(
            // 새 주소는 ROUTEID 를 줌: TAGO 와 같은 번호 (TAGO 는 앞에 USB)
            routeId = r.str("ROUTEID")?.let { "USB$it" } ?: "",
            routeNo = no,
            routeType = null,
            vehicleType = null,
            prevStops = r.int("PREVSTOPCNT"),
            arrSeconds = r.int("ARRIVALTIME"),
            atStop = r.str("PRESENTSTOPNM"),
            towards = (m?.groupValues?.get(2) ?: r.str("REMARK"))?.trim()?.removeSuffix("방면")?.trim()?.ifBlank { null },
        )
    }.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }

    /** 광주: REMAIN_MIN 분, REMAIN_STOP 남은 정류장, CURR_STOP 현재 정류장, DIR_END 종점(방면) */
    fun gwangju(rows: List<Row>): List<BusArrival> = rows.mapNotNull { r ->
        val no = r.str("LINE_NAME", "SHORT_LINE_NAME") ?: return@mapNotNull null
        BusArrival(
            routeId = "KJB" + r.text("LINE_ID"),
            routeNo = no,
            routeType = null,
            vehicleType = if (r.str("LOW_BUS") == "1") "저상버스" else null,
            prevStops = r.int("REMAIN_STOP"),
            arrSeconds = r.int("REMAIN_MIN")?.times(60),
            atStop = r.str("BUSSTOP_NAME"),
            towards = r.str("DIR_END"),
        )
    }.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }

    private val minutes = Regex("""(\d+)\s*분""")

    /**
     * 여수: RSTOP 남은 정류장 수(차량이 없으면 "기점" 같은 글자), LAST_STOP_NAME 최근 정류장,
     * PROVIDE_TYPE 도착 예정 시간(차량이 없으면 "15시 35분 출발 예정" 같은 시간표 글자 → 도착 초 없음).
     */
    fun yeosu(rows: List<Row>): List<BusArrival> = rows.mapNotNull { r ->
        val no = r.str("ROUTE_NAME") ?: return@mapNotNull null
        val provide = r.str("PROVIDE_TYPE")
        val min = provide?.takeIf { "출발" !in it }?.let { minutes.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        BusArrival(
            routeId = "YEB" + r.text("ROUTE_ID"),
            routeNo = no,
            routeType = null,
            vehicleType = null,
            prevStops = r.int("RSTOP"),
            arrSeconds = min?.times(60),
            atStop = r.str("LAST_STOP_NAME"),
            towards = r.str("ED_STOP_NAME"),
        )
    }.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }

    /** 대전 ROUTE_TP → TAGO 와 같은 이름 (2026-11-17 같은 노선 TAGO routetp 와 맞춰 봄: 2 간선, 3·7 광역, 4 지선, 5 마을) */
    fun daejeonType(code: String?): String? = when (code?.trim()) {
        "1" -> "급행버스"; "2" -> "간선버스"; "3", "7" -> "광역버스"; "4" -> "지선버스"; "5" -> "마을버스"
        else -> null
    }

    /**
     * 대전 정류장 도착 (6300000/arrive/getArrInfoByStopID): EXTIME_SEC 남은 초, STATUS_POS 남은 정류장 수, DESTINATION 방면(종점).
     * MSG_TP 07 은 아직 차고지에서 출발하지 않은 차: EXTIME_SEC 에 남은 초가 아니라 출발 시각(1440 = 14:40)이 들어 있어
     * 남은 시간·정류장을 비움 (TAGO 는 이 값을 1440초로 잘못 보여 줌)
     */
    fun daejeon(rows: List<Row>): List<BusArrival> = rows.mapNotNull { r ->
        val no = r.str("ROUTE_NO") ?: return@mapNotNull null
        val waiting = r.str("MSG_TP") == "07"
        BusArrival(
            routeId = r.str("ROUTE_CD")?.let { "DJB$it" } ?: "",
            routeNo = no,
            routeType = daejeonType(r.str("ROUTE_TP")),
            vehicleType = null,
            prevStops = if (waiting) null else r.int("STATUS_POS"),
            arrSeconds = if (waiting) null else r.int("EXTIME_SEC"),
            towards = r.str("DESTINATION"),
        )
    }.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }

    /**
     * 대전 노선 버스 위치 (6300000/busposinfo/getBusPosByRtid): BUS_NODE_ID 지금(또는 막 지난) 정류장, DIR 0 가는 길·1 오는 길,
     * GPS_LATI·GPS_LONG 은 그 정류장 좌표 (TAGO 와 같음, 대신 한 정류장쯤 새로움).
     * 순번이 없어서 [stops] 에서 정류장 ID 로 찾고, 같은 정류장이 두 번 나오면(오가는 길이 같은 정류장) DIR 로 앞·뒤를 고름
     */
    fun daejeonBuses(rows: List<Row>, stops: List<RouteStop>): List<BusLocation> {
        val ordOf = stops.groupBy { it.nodeId }.mapValues { e -> e.value.map { it.ord }.sorted() }
        return rows.mapNotNull { r ->
            val plate = r.str("PLATE_NO") ?: return@mapNotNull null
            val node = r.str("BUS_NODE_ID")?.let { "DJB$it" }
            val cands = node?.let { ordOf[it] }.orEmpty()
            BusLocation(
                nodeId = node,
                nodeName = null,
                ord = if (r.str("DIR") == "1") cands.lastOrNull() else cands.firstOrNull(),
                vehicleNo = plate,
                lat = r.double("GPS_LATI"),
                lng = r.double("GPS_LONG"),
            )
        }
    }

    // ───────── 응답 본문 → 행 ─────────

    /** 광주: JSON {"RESULT":{...},"BUSSTOP_LIST":[...]} */
    fun gwangjuRows(body: String): List<Row> {
        val root = MiniJson.parse(body) as? Map<*, *> ?: throw IOException("광주 버스 API 응답 형식이 올바르지 않습니다.")
        // 실제 응답: {"RESPONSE":{"RESULT":{...},"ARRIVE_LIST":{"ITEM":[...]}}} (가이드 샘플은 RESULT 가 바로 위에 있음)
        val result = ((root["RESPONSE"] as? Map<*, *>)?.get("RESULT") ?: root["RESULT"]) as? Map<*, *>
        val code = result?.get("RESULT_CODE")?.toString()
        if (code != null && code != "SUCCESS") {
            throw IOException("광주 버스 API 오류 $code: ${result["RESULT_MSG"] ?: ""}")
        }
        return MiniJson.findRows(root, "LINE_ID")
    }

    private val rowTags = setOf("item", "row", "itemlist")
    private val gateway = Regex("""returnReasonCode\s*>\s*(\d+)""")

    /** 부산·울산·여수: XML 에서 item/row 한 줄씩. 결과코드가 정상(0·00·200)이 아니면 오류 */
    fun xmlRows(body: String, label: String): List<Row> {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw IOException("$label 버스 API 응답이 비어 있습니다.")
        gateway.find(text)?.let { throw IOException("$label 버스 API 게이트웨이 오류 ${it.groupValues[1]}") }
        if (!text.startsWith("<")) throw IOException("$label 버스 API 가 XML 이 아닌 응답을 보냈습니다: ${text.take(120)}")
        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw IOException("$label 버스 API XML 을 해석하지 못했습니다: ${e.message}", e)
        }
        // 대전 BIS: <msgHeader><headerCd>0</headerCd> (4 = 결과 없음, 그 밖은 오류)
        doc.getElementsByTagName("headerCd").item(0)?.textContent?.trim()?.let { hc ->
            if (hc != "0" && hc != "4") {
                val msg = doc.getElementsByTagName("headerMsg").item(0)?.textContent?.trim().orEmpty()
                throw IOException("$label 버스 API 오류 $hc: $msg")
            }
        }
        val code = doc.getElementsByTagName("resultCode").item(0)?.textContent?.trim()
        // 03 = 데이터 없음(빈 목록)
        if (code != null && code !in setOf("0", "00", "200", "03", "3")) {
            val msg = doc.getElementsByTagName("resultMsg").item(0)?.textContent?.trim().orEmpty()
            throw IOException("$label 버스 API 오류 $code: $msg")
        }
        val rows = ArrayList<Row>()
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val el = all.item(i) as? Element ?: continue
            if (el.tagName.lowercase() !in rowTags) continue
            val map = HashMap<String, String>()
            val children = el.childNodes
            for (j in 0 until children.length) {
                val c = children.item(j)
                if (c.nodeType == org.w3c.dom.Node.ELEMENT_NODE) map[c.nodeName] = c.textContent ?: ""
            }
            if (map.isNotEmpty()) rows += Row(map)
        }
        return rows
    }
}

class RegionalBusRepository(
    private val serviceKey: String = BuildConfig.TAGO_SERVICE_KEY.trim().ifBlank { BuildConfig.KLID_BUS_KEY.trim() },
    private val client: OkHttpClient = httpClient,
) {
    suspend fun arrivals(stop: RegionalStop): TagoResult<List<BusArrival>> = try {
        TagoResult.Success(withContext(Dispatchers.IO) { fetch(stop) })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        TagoResult.Error(e.message ?: "${stop.kind} 버스 API 오류")
    }

    private fun fetch(stop: RegionalStop): List<BusArrival> = when (stop.kind) {
        RegionalKind.BUSAN -> RegionalMappers.busan(
            RegionalMappers.xmlRows(get("https://apis.data.go.kr/6260000/BusanBIMS/stopArrByBstopid", "bstopid" to stop.stdId), "부산"))
        RegionalKind.ULSAN -> RegionalMappers.ulsan(
            // 예전 주소(openapi.its.ulsan.kr)는 2026-11 지금 응답이 없음 → 공공데이터포털 "울산광역시_BIS 정보_GW".
            // _type 을 붙여야 한글이 UTF-8 로 옴 (안 붙이면 한글이 '?' 로 바뀌어 옴, 2026-11-16 확인)
            RegionalMappers.xmlRows(get("https://apis.data.go.kr/6310000/bis/getBusArrivalInfo", "stopid" to stop.stdId, "pageNo" to "1", "numOfRows" to "100", "_type" to "xml"), "울산"))
        RegionalKind.GWANGJU -> RegionalMappers.gwangju(
            RegionalMappers.gwangjuRows(get("https://apis.data.go.kr/6290000/gj_bis/arriveInfo", "BUSSTOP_ID" to stop.stdId, "resultType" to "json")))
        RegionalKind.YEOSU -> RegionalMappers.yeosu(
            RegionalMappers.xmlRows(get("https://apis.data.go.kr/4810000/arrive/getArrInfoByStopID", "busStopID" to stop.stdId, "pageNo" to "1", "numOfRows" to "50"), "여수"))
        // 대전 BIS: 같은 순간 TAGO 보다 같은 버스가 40~130초 새롭고 버스가 더 많음 (2026-11-17 실측). 웹 /w/bus/daejeon 과 같은 요청(저장 공유)
        RegionalKind.DAEJEON -> RegionalMappers.daejeon(
            RegionalMappers.xmlRows(get("https://apis.data.go.kr/6300000/arrive/getArrInfoByStopID", "BusStopID" to stop.stdId), "대전"))
    }

    /** 대전 노선 버스 위치 ([routeId] = DJB + 노선 ID 8자리). 정류장 순번은 [stops] 에서 정류장 ID 로 맞춤 */
    suspend fun daejeonBuses(routeId: String, stops: List<RouteStop>): TagoResult<List<BusLocation>> = try {
        TagoResult.Success(withContext(Dispatchers.IO) {
            RegionalMappers.daejeonBuses(
                RegionalMappers.xmlRows(get("https://apis.data.go.kr/6300000/busposinfo/getBusPosByRtid", "busRouteId" to routeId.removePrefix("DJB")), "대전"),
                stops,
            )
        })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        TagoResult.Error(e.message ?: "대전 버스 위치 API 오류")
    }

    private fun get(base: String, vararg params: Pair<String, String>): String {
        if (serviceKey.isBlank()) throw IOException(com.eunho.tagohub.core.DevMode.msg("local.properties에 TAGO_SERVICE_KEY를 설정하세요."))
        val encoded = if ('%' in serviceKey) serviceKey else URLEncoder.encode(serviceKey, Charsets.UTF_8.name())
        val url = base.toHttpUrl().newBuilder().apply {
            addEncodedQueryParameter("serviceKey", encoded)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("지역 버스 API HTTP ${res.code}: ${text.replace(Regex("\\s+"), " ").take(160)}")
            return text
        }
    }

    private companion object {
        val httpClient: OkHttpClient = Http.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
