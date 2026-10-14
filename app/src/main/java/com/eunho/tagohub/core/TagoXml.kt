package com.eunho.tagohub.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * <item> 하나. 키는 대소문자 무시로 조회한다.
 * TAGO 는 서비스마다 필드명 규칙이 다르다:
 *   기차  depplacename / adultcharge      (전부 소문자)
 *   고속  depPlaceNm  / charge            (camelCase)
 *   지하철 subwayStationName(검색) vs subwayStationNm(시간표)  ← 같은 의미, 다른 이름
 * 그래서 str("a", "b") 처럼 후보를 여러 개 받는다.
 */
class Row(raw: Map<String, String>) {
    private val map: Map<String, String> = raw.entries.associate { it.key.lowercase() to it.value.trim() }

    fun str(vararg keys: String): String? {
        for (k in keys) {
            val v = map[k.lowercase()]
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    fun text(vararg keys: String): String = str(*keys) ?: ""
    fun int(vararg keys: String): Int? = str(*keys)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    fun double(vararg keys: String): Double? = str(*keys)?.toDoubleOrNull()

    override fun toString(): String = map.toString()
}

data class TagoPage(val rows: List<Row>, val totalCount: Int)

class TagoApiException(val code: String, message: String) : Exception(message) {
    /** 페이지 크기를 줄여 재시도해도 소용없는 오류(키·권한·트래픽) */
    val isFatal: Boolean get() = code in FATAL

    private companion object {
        val FATAL = setOf("12", "20", "22", "30", "31", "32")
    }
}

/**
 * TAGO(1613000) 공통 XML 파서.
 * JSON(_type=json) 대신 XML 을 쓰는 이유:
 *  - JSON 은 item 이 1건이면 배열이 아닌 객체로 오고,
 *  - routeno 같은 값이 응답마다 숫자/문자열로 바뀐다.
 *  XML 은 모든 값이 문자열이고 item 은 항상 반복 요소라 이 문제가 없다.
 */
object TagoXml {
    private val gatewayCode = Regex("""returnReasonCode"?\s*[:>]\s*"?(\d+)""")
    private val gatewayMsg = Regex("""returnAuthMsg"?\s*[:>]\s*"?([A-Za-z_]+)""")

    fun parse(body: String): TagoPage {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw TagoApiException("EMPTY", "서버가 빈 응답을 보냈습니다.")

        // data.go.kr 게이트웨이 오류 (<OpenAPI_ServiceResponse> 봉투, XML/JSON 둘 다)
        gatewayCode.find(text)?.let { m ->
            val code = m.groupValues[1]
            throw TagoApiException(code, describe(code, gatewayMsg.find(text)?.groupValues?.get(1)))
        }
        if (text.startsWith("{")) return TagoJson.parse(text)
        if (!text.startsWith("<")) {
            throw TagoApiException("FORMAT", "XML/JSON 이 아닌 응답: ${text.take(160)}")
        }

        val doc = try {
            builder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw TagoApiException("FORMAT", "응답 XML을 해석하지 못했습니다: ${e.message}")
        }

        val code = doc.firstText("resultCode")
        // 00 정상, 03 NODATA(=빈 목록으로 취급)
        if (code != null && code != "00" && code != "03") {
            throw TagoApiException(code, describe(code, doc.firstText("resultMsg")))
        }

        val nodes = doc.getElementsByTagName("item")
        val rows = ArrayList<Row>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val map = HashMap<String, String>()
            val children = el.childNodes
            for (j in 0 until children.length) {
                val c = children.item(j)
                if (c.nodeType == Node.ELEMENT_NODE) map[c.nodeName] = c.textContent ?: ""
            }
            rows += Row(map)
        }
        // 등급·코드 목록 API 는 totalCount 자체가 없다 → 받은 개수가 전체
        val total = doc.firstText("totalCount")?.toIntOrNull() ?: rows.size
        return TagoPage(rows, total)
    }

    private fun Document.firstText(tag: String): String? {
        val list = getElementsByTagName(tag)
        return if (list.length > 0) list.item(0).textContent?.trim() else null
    }

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder()

    fun describe(code: String, raw: String?): String {
        val msg = when (code) {
            "01" -> "API 서버 내부 오류"
            "02" -> "API 서버 DB 오류"
            "04" -> "HTTP 오류 (요청 크기나 형식을 서버가 거부)"
            "05" -> "API 서버 응답 시간 초과"
            "10" -> "잘못된 요청 파라미터"
            "11" -> "필수 파라미터 누락"
            "12" -> "해당 OpenAPI 서비스가 없거나 폐기됨"
            "20" -> "서비스 접근 거부: 이 API의 활용신청 승인 여부를 확인하세요"
            "22" -> "일일 트래픽 한도 초과"
            "30" -> "등록되지 않은 서비스키: local.properties의 키를 확인하세요"
            "31" -> "활용기간이 만료된 서비스키"
            "32" -> "등록되지 않은 IP"
            else -> "API 오류"
        }
        return if (raw.isNullOrBlank()) "$msg (코드 $code)" else "$msg (코드 $code, $raw)"
    }
}
