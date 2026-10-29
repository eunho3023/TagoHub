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
    ULSAN("GGB"),
    GWANGJU("KJB"),
    YEOSU("YEB"),
}

/** 지역 API 로 물어볼 정류장 (stdId = TAGO ID 에서 접두사를 뗀 지역 API 의 정류장 ID) */
data class RegionalStop(val kind: RegionalKind, val stdId: String)

object RegionalBus {
    /** 울산은 TAGO ID 접두사(GGB)가 경기와 같아서 도시코드(26)로 구분 */
    private const val ULSAN_CITY = "26"

    fun of(s: BusStop): RegionalStop? = of(s.nodeId, s.cityCode)

    fun of(nodeId: String, cityCode: String): RegionalStop? {
        val kind = when {
            nodeId.startsWith("BSB") -> RegionalKind.BUSAN
            nodeId.startsWith("GGB") && cityCode == ULSAN_CITY -> RegionalKind.ULSAN
            nodeId.startsWith("KJB") && cityCode == "24" -> RegionalKind.GWANGJU
            nodeId.startsWith("YEB") -> RegionalKind.YEOSU
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

    /** 울산: ROUTENM = "527(태화강역방면)" → 노선 527, 방면 태화강역. 도착시간은 초 */
    fun ulsan(rows: List<Row>): List<BusArrival> = rows.mapNotNull { r ->
        val name = r.str("ROUTENM") ?: return@mapNotNull null
        val m = ulsanRoute.find(name)
        val no = m?.groupValues?.get(1)?.ifBlank { null } ?: name
        BusArrival(
            routeId = "",
            routeNo = no,
            routeType = null,
            vehicleType = null,
            prevStops = r.int("PREVSTOPCNT"),
            arrSeconds = r.int("ARRIVALTIME"),
            atStop = r.str("PRESENTSTOPNM"),
            towards = m?.groupValues?.get(2)?.removeSuffix("방면")?.trim()?.ifBlank { null },
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
        val text = body.trim().removePrefix("﻿")
        if (text.isEmpty()) throw IOException("$label 버스 API 응답이 비어 있습니다.")
        gateway.find(text)?.let { throw IOException("$label 버스 API 게이트웨이 오류 ${it.groupValues[1]}") }
        if (!text.startsWith("<")) throw IOException("$label 버스 API 가 XML 이 아닌 응답을 보냈습니다: ${text.take(120)}")
        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw IOException("$label 버스 API XML 을 해석하지 못했습니다: ${e.message}", e)
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
            RegionalMappers.xmlRows(get("http://openapi.its.ulsan.kr/UlsanAPI/getBusArrivalInfo.xo", "stopid" to stop.stdId, "pageNo" to "1", "numOfRows" to "50"), "울산"))
        RegionalKind.GWANGJU -> RegionalMappers.gwangju(
            RegionalMappers.gwangjuRows(get("https://apis.data.go.kr/6290000/gj_bis/arriveInfo", "BUSSTOP_ID" to stop.stdId, "resultType" to "json")))
        RegionalKind.YEOSU -> RegionalMappers.yeosu(
            RegionalMappers.xmlRows(get("https://apis.data.go.kr/4810000/arrive/getArrInfoByStopID", "busStopID" to stop.stdId, "pageNo" to "1", "numOfRows" to "50"), "여수"))
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
