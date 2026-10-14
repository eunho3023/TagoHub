package com.eunho.tagohub.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 서울 열린데이터광장 실시간 지하철(swopenapi) XML 파서.
 * TAGO 와 다른 점: 반복 요소가 <row>, 정상 코드가 INFO-000, 데이터 없음이 INFO-200.
 * 결과 코드 위치가 API 마다 달라(<RESULT> / <errorMessage>) 문서에서 첫 <code> 를 쓴다.
 */
object SeoulXml {
    fun parse(body: String): List<Row> {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw TagoApiException("EMPTY", "서울 API가 빈 응답을 보냈습니다.")
        if (!text.startsWith("<")) throw TagoApiException("FORMAT", "서울 API 응답 형식 오류: ${text.take(160)}")

        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw TagoApiException("FORMAT", "서울 API 응답을 해석하지 못했습니다: ${e.message}")
        }

        fun first(tag: String): String? {
            val l = doc.getElementsByTagName(tag)
            return if (l.length > 0) l.item(0).textContent?.trim() else null
        }

        val code = first("code")
        if (code != null && code != "INFO-000") {
            if (code == "INFO-200") return emptyList() // 해당 데이터 없음 (운행 종료 등)
            throw TagoApiException(code, "서울 실시간 API 오류 ($code) ${first("message").orEmpty()}".trim())
        }

        val nodes = doc.getElementsByTagName("row")
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
        return rows
    }
}
