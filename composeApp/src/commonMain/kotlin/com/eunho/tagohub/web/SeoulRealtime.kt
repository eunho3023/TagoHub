package com.eunho.tagohub.web

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient

/** 서울 실시간 지하철 도착 한 줄 (서울 열린데이터광장) */
data class SeoulArrival(
    val lineId: String, val upDown: String, val dest: String?, val text: String, val seconds: Int?,
    val lineText: String, val express: Boolean, val last: Boolean, val order: String?,
)

/**
 * 웹 버전 서울(수도권) 지하철 실시간 도착: 서버 웹 통로 /w/seoul/arrival/<역 이름> (10초 저장).
 * 국토교통부 역(노선 이름)이 서울 실시간 API 노선이면만 묻고, 같은 이름 다른 노선은 거름.
 */
class SeoulRealtime(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"

    suspend fun arrivals(station: SubwayStationW): List<SeoulArrival> {
        val line = lineIdOf(station) ?: return emptyList()
        var rows = emptyList<SeoulArrival>()
        for (n in queryNames(station.name)) {
            rows = parse(http.getText(base + "w/seoul/arrival/", path = n))
            if (rows.isNotEmpty()) break
        }
        return rows.filter { it.lineId == line }.sortedWith(compareBy({ it.upDown }, { it.order ?: "" }, { it.seconds ?: Int.MAX_VALUE }))
    }

    companion object {
        /** 지방 도시철도(부산·대구·광주·대전·인천 등)는 서울 API 에 없음 */
        private val OTHER_CITY = listOf("MTRBS", "MTRDG", "MTRGJ", "MTRDJ", "MTRIC", "MTRGMG", "MTRUL")

        /** 국토교통부 노선 이름 → 서울 실시간 노선 번호 */
        private val LINE_IDS = mapOf(
            "1호선" to "1001", "2호선" to "1002", "3호선" to "1003", "4호선" to "1004", "5호선" to "1005",
            "6호선" to "1006", "7호선" to "1007", "8호선" to "1008", "9호선" to "1009",
            "경의중앙" to "1063", "공항" to "1065", "경춘" to "1067", "수인분당" to "1075", "신분당" to "1077",
            "경강" to "1081", "우이신설" to "1092", "서해" to "1093", "신림" to "1094", "GTX-A" to "1032",
        )

        fun lineIdOf(s: SubwayStationW): String? {
            if (OTHER_CITY.any { s.id.startsWith(it) }) return null
            val name = s.line.replace(" ", "").removeSuffix("선").let { if (it.endsWith("호")) it + "선" else it }
            return LINE_IDS[name] ?: LINE_IDS[s.line.trim()]
        }

        /** "서울역" → ["서울"], "총신대입구(이수)" → ["총신대입구(이수)", "총신대입구"] */
        fun queryNames(name: String): List<String> {
            val n = name.trim().let { if (it.length > 2) it.removeSuffix("역") else it }
            return listOf(n, n.substringBefore('(')).filter { it.isNotBlank() }.distinct()
        }

        fun parse(body: String): List<SeoulArrival> {
            val root = MiniJson.parse(body) as? Map<*, *> ?: return emptyList()
            val list = root["realtimeArrivalList"] as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val r = (item as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v?.toString()?.trim().orEmpty() } ?: return@mapNotNull null
                val line = r["subwayId"].orEmpty().ifEmpty { return@mapNotNull null }
                SeoulArrival(
                    lineId = line,
                    upDown = r["updnLine"].orEmpty(),
                    dest = r["bstatnNm"]?.ifEmpty { null },
                    text = mainText(r["arvlMsg2"].orEmpty(), r["arvlCd"]?.toIntOrNull()),
                    seconds = r["barvlDt"]?.toIntOrNull()?.takeIf { it > 0 },
                    lineText = r["trainLineNm"].orEmpty(),
                    express = r["btrainSttus"]?.let { it.isNotEmpty() && it != "일반" } ?: false,
                    last = r["lstcarAt"] == "1",
                    order = r["ordkey"]?.ifEmpty { null },
                )
            }
        }

        /** 앱 SubwayText.main 과 같은 규칙: "[4]번째 전역 (효창공원앞)" → "4번째 전역", "12분 후 (광명사거리)" → "12분 후" */
        fun mainText(msg: String, code: Int?): String {
            val m = msg.trim()
            Regex("\\[(\\d+)]번째 전역").find(m)?.let { return "${it.groupValues[1]}번째 전역" }
            Regex("(\\d+)분\\s*후").find(m)?.let { return "${it.groupValues[1]}분 후" }
            when (code) {
                0 -> return "진입"
                1 -> return "도착"
                2 -> return "출발"
                3 -> return "전역 출발"
                4 -> return "전역 진입"
                5 -> return "전역 도착"
            }
            return m.ifBlank { "정보 없음" }
        }
    }
}
