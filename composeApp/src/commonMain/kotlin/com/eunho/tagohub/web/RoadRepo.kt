package com.eunho.tagohub.web

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient

/** 고속도로 돌발 (사고·작업·통제 등 문자정보) */
data class RoadIncident(
    val id: String, val kind: String, val road: String?, val text: String, val place: String?,
    val dir: String?, val status: String?, val time: String?, val jamMeters: Int?, val lat: Double?, val lng: Double?,
)

/** 고속도로 정체 구간 (속도가 가장 느린 검지기 하나) */
data class RoadJam(val id: String, val route: String, val zone: String, val speed: Int?, val dir: String?, val time: String?, val lat: Double?, val lng: Double?)

/** 휴게소 주유소 가격 (원/L) */
data class GasStation(val name: String, val route: String?, val dir: String?, val company: String?, val gasoline: Int?, val diesel: Int?, val lpg: Int?)

/**
 * 웹·PC 버전 도로: 한국도로공사 자료를 서버 웹 통로(/w/ex/, 돌발·정체 2분, 주유 가격 6시간 저장)로.
 * 국가교통정보센터·경찰청(UTIC) 자료는 서버에서 부를 수 없어(포트·IP 제한) 폰 앱에만 있음.
 */
class RoadRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private var vds: Map<String, Pair<Double, Double>>? = null

    /** 모든 쪽 모으기 (한 쪽 99건, 최대 20쪽) */
    private suspend fun rows(path: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        for (page in 1..20) {
            val (rows, total) = parse(http.getText(base + "w/ex/" + path, query = listOf("pageNo" to page.toString())))
            if (rows.isEmpty()) break
            out += rows
            if (total == null || out.size >= total || rows.size < 99) break
        }
        return out
    }

    /** 검지기(VDS) 위치 (서버 data/files/ex_vds.json, 한 번만) */
    private suspend fun vdsCoords(): Map<String, Pair<Double, Double>> = vds ?: runCatching {
        parseVds(http.getText(base + WebData.filePath("ex_vds.json")))
    }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) vds = it }

    suspend fun incidents(): List<RoadIncident> = incidents(rows("burstInfo/realTimeSms"))
    suspend fun jams(): List<RoadJam> = jams(rows("odtraffic/trafficAmountByCongest"), vdsCoords())
    suspend fun gas(): List<GasStation> = gas(rows("business/curStateStation"))

    companion object {
        /** 응답 JSON → (행 목록, 전체 수). code 가 SUCCESS 가 아니면 오류 */
        fun parse(body: String): Pair<List<Map<String, String>>, Int?> {
            val root = MiniJson.parse(body) as? Map<*, *> ?: error("응답 형식이 올바르지 않아요")
            val code = root["code"]?.toString()
            if (code != null && !code.equals("SUCCESS", ignoreCase = true)) error(root["message"]?.toString() ?: code)
            val rows = root.values.firstNotNullOfOrNull { v -> (v as? List<*>)?.takeIf { l -> l.isNotEmpty() && l.all { it is Map<*, *> } } }
                .orEmpty().map { r ->
                    (r as Map<*, *>).entries.mapNotNull { (k, v) ->
                        val s = v?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                        k.toString() to s
                    }.toMap()
                }
            return rows to root["count"]?.toString()?.toDoubleOrNull()?.toInt()
        }

        fun parseVds(json: String): Map<String, Pair<Double, Double>> =
            (MiniJson.parse(json) as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val l = v as? List<*> ?: return@mapNotNull null
                val la = l.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                val lo = l.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                k.toString() to (la to lo)
            }.toMap()

        private fun lat(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 30.0..40.0 }
        private fun lng(v: String?) = v?.toDoubleOrNull()?.takeIf { it in 123.0..133.0 }
        private fun dir(code: String?) = when (code) { "S" -> "기점 방향"; "E" -> "종점 방향"; else -> null }

        /** 돌발 문자정보 (위도는 latitude, 경도는 altitude — 이름과 다름). 이벤트·홍보 문구는 뺌, 최근 것 먼저 */
        fun incidents(rows: List<Map<String, String>>): List<RoadIncident> = rows.mapNotNull { r ->
            if (r["accType"] == "이벤트/홍보") return@mapNotNull null
            val text = r["smsText"]?.replace("*", "")?.trim().orEmpty()
            val lat = lat(r["latitude"])
            val lng = lng(r["altitude"])
            RoadIncident(
                id = listOf(r["nosunNM"], r["seriesNM"], r["accDate"], r["accHour"], text.take(20)).joinToString("|"),
                kind = r["accType"] ?: "돌발",
                road = r["roadNM"],
                text = text,
                place = r["accPointNM"]?.takeIf { it.isNotBlank() },
                dir = r["startEndTypeCode"],
                status = r["accProcessNM"],
                time = listOfNotNull(r["accDate"], r["accHour"]?.take(5)).joinToString(" ").ifBlank { null },
                jamMeters = r["lateLength"]?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
                lat = if (lat != null && lng != null) lat else null,
                lng = if (lat != null && lng != null) lng else null,
            )
        }.distinctBy { it.id }.sortedByDescending { it.time.orEmpty() }

        /** 정체 구간: 같은 구간(콘존·방향)은 가장 느린 검지기 하나, 느린 순 */
        fun jams(rows: List<Map<String, String>>, coords: Map<String, Pair<Double, Double>>): List<RoadJam> =
            rows.groupBy { (it["conzoneId"] ?: it["vdsId"]) + ":" + it["updownTypeCode"].orEmpty() }.values.mapNotNull { g ->
                val r = g.minByOrNull { it["speed"]?.toDoubleOrNull() ?: Double.MAX_VALUE } ?: return@mapNotNull null
                val pos = coords[r["vdsId"]] ?: g.firstNotNullOfOrNull { coords[it["vdsId"]] }
                RoadJam(
                    id = (r["conzoneId"] ?: r["vdsId"] ?: return@mapNotNull null) + ":" + r["updownTypeCode"].orEmpty(),
                    route = r["routeName"].orEmpty(),
                    zone = r["conzoneName"].orEmpty(),
                    speed = r["speed"]?.toDoubleOrNull()?.toInt(),
                    dir = dir(r["updownTypeCode"]),
                    time = r["stdHour"]?.takeIf { it.length >= 4 }?.let { "${it.take(2)}:${it.substring(2, 4)}" },
                    lat = pos?.first, lng = pos?.second,
                )
            }.sortedBy { it.speed ?: Int.MAX_VALUE }

        /** 주유소 가격 ("1,689원" 같은 글자 → 숫자), 이름순 */
        fun gas(rows: List<Map<String, String>>): List<GasStation> = rows.mapNotNull { r ->
            val name = r["serviceAreaName"] ?: return@mapNotNull null
            fun won(v: String?) = v?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }
            GasStation(
                name, r["routeName"], r["direction"], r["oilCompany"],
                won(r["gasolinePrice"]), won(r["diselPrice"]), won(r["lpgPrice"])?.takeIf { r["lpgYn"] != "N" },
            )
        }.distinctBy { it.name + "|" + it.dir }.sortedBy { it.name }
    }
}
