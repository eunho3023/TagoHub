package com.eunho.tagohub.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 전주시 버스정보 API(apis.data.go.kr/4641000) 공통 응답:
 *   <RFC30><code>000</code><msg>OK</msg><routeList><list class="object">…</list>…</routeList></RFC30>
 */
object JeonjuXml {
    fun parse(body: String): List<Row> {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw TagoApiException("EMPTY", "전주시 버스 API가 빈 응답을 보냈습니다.")
        // data.go.kr 게이트웨이 오류(키 등)는 TAGO 와 같은 형식
        if ("returnReasonCode" in text) TagoXml.parse(text)
        if (!text.startsWith("<")) throw TagoApiException("FORMAT", "전주시 버스 API 응답 형식 오류: ${text.take(160)}")
        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw TagoApiException("FORMAT", "전주시 버스 API 응답을 해석하지 못했습니다: ${e.message}")
        }
        fun first(tag: String): String? {
            val l = doc.getElementsByTagName(tag)
            return if (l.length > 0) l.item(0).textContent?.trim() else null
        }
        val code = first("code")
        if (code != null && code != "000") {
            throw TagoApiException("J$code", "전주시 버스 API 오류 ($code) ${first("msg").orEmpty()}".trim())
        }
        val nodes = doc.getElementsByTagName("list")
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
