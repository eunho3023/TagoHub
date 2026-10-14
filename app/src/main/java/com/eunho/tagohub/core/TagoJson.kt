package com.eunho.tagohub.core

/**
 * 서버가 _type=xml 을 무시하고 JSON 을 줄 때를 위한 폴백.
 * 숫자는 원문 그대로 문자열로 보존한다 (0600 → 600 처럼 변형되는 문제 방지, 좌표 정밀도 유지).
 * item 이 1건이면 배열 대신 객체로 오고, 0건이면 items 가 "" 로 오는 TAGO JSON 특성을 처리한다.
 */
internal object TagoJson {

    fun parse(text: String): TagoPage {
        val root = MiniJson.parse(text)
        val response = (root as? Map<*, *>)?.get("response") as? Map<*, *>
            ?: throw TagoApiException("FORMAT", "JSON 응답에 response 가 없습니다: ${text.take(120)}")

        val header = response["header"] as? Map<*, *>
        val code = header?.get("resultCode")?.toString()
        if (code != null && code != "00" && code != "03") {
            throw TagoApiException(code, TagoXml.describe(code, header["resultMsg"]?.toString()))
        }

        val body = response["body"] as? Map<*, *>
        val rawItem = (body?.get("items") as? Map<*, *>)?.get("item")
        val list: List<*> = when (rawItem) {
            is List<*> -> rawItem
            is Map<*, *> -> listOf(rawItem)
            else -> emptyList<Any>()
        }
        val rows = list.mapNotNull { it as? Map<*, *> }.map { m ->
            Row(m.entries.associate { (k, v) -> k.toString() to (v?.toString() ?: "") })
        }
        val total = body?.get("totalCount")?.toString()?.toIntOrNull() ?: rows.size
        return TagoPage(rows, total)
    }
}
