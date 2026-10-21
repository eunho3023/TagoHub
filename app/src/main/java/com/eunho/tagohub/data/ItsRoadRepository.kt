package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class ItsRoadFeature(
    val id: String,
    val layer: String,
    val title: String,
    val details: String,
    val lat: Double,
    val lng: Double,
)

data class ItsTrafficRow(
    val id: String,
    val roadName: String,
    val direction: String?,
    val speedKmh: Double?,
    val travelTimeSeconds: Double?,
    val updatedAt: String?,
)

data class ItsForecastRow(
    val id: String,
    val sectionId: String,
    val sectionType: String?,
    val detourId: String?,
    val speedKmh: Double?,
    val lengthKm: Double?,
    val forecastDate: String?,
    val forecastHour: String?,
)

data class ItsMainlineSection(
    val id: String,
    val roadType: String,
    val roadName: String,
    val startName: String,
    val endName: String,
    val details: String,
)

data class ItsFogSection(
    val roadName: String,
    val startKm: Double?,
    val endKm: Double?,
    val description: String,
)

object ItsRoadParser {
    private val coordinatePattern = Regex("-?\\d+(?:\\.\\d+)?")

    fun events(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "돌발") { item ->
            val lat = item.text("coordY")?.toDoubleOrNull() ?: return@parseItems null
            val lng = item.text("coordX")?.toDoubleOrNull() ?: return@parseItems null
            feature(
                layer = "its_events",
                id = item.text("occrrncId") ?: item.text("linkId") ?: "event-$lat-$lng",
                title = item.text("eventType")?.let { "돌발 · $it" } ?: "교통 돌발",
                details = listOfNotNull(
                    item.text("roadName"),
                    item.text("roadDrcType"),
                    item.text("message"),
                    item.text("startDate")?.let { "시작 $it" },
                    item.text("endDate")?.let { "종료 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun disasters(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "재난") { item ->
            val geometry = item.text("locationGeometry")
                ?: item.text("locationInfo")?.takeIf { Regex("(?i)POINT|LINESTRING|POLYGON").containsMatchIn(it) }
                ?: return@parseItems null
            val points = coordinatePattern.findAll(geometry.replace(Regex("(?i)SRID=\\d+;"), ""))
                .mapNotNull { it.value.toDoubleOrNull() }.toList()
            if (points.size < 2) return@parseItems null
            val coordinates = points.chunked(2).filter { it.size == 2 }
            if (coordinates.isEmpty()) return@parseItems null
            val lng = coordinates.map { it[0] }.average()
            val lat = coordinates.map { it[1] }.average()
            feature(
                layer = "its_disasters",
                id = item.text("eventId") ?: "disaster-$lat-$lng",
                title = item.text("socName") ?: item.text("eventType") ?: "재난 상황",
                details = listOfNotNull(
                    item.text("status"),
                    item.text("roadName"),
                    item.text("roadDrcType"),
                    item.text("message"),
                    item.text("startDate")?.let { "시작 $it" },
                    item.text("endDate")?.let { "종료 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun vsl(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "VSL") { item ->
            val x = item.text("coordX")?.toDoubleOrNull() ?: return@parseItems null
            val y = item.text("coordY")?.toDoubleOrNull() ?: return@parseItems null
            val (lat, lng) = if (x in -90.0..90.0 && y !in -90.0..90.0) x to y else y to x
            feature(
                layer = "its_vsl",
                id = item.text("vslId") ?: "vsl-$lat-$lng",
                title = "VSL · ${item.text("limitSpeed") ?: "속도 정보 없음"} km/h",
                details = listOfNotNull(
                    item.text("vslId"),
                    item.text("roadNo")?.let { "노선 $it" },
                    item.text("defLmtSpeed")?.let { "기본 제한속도 $it km/h" },
                    item.text("createdDate")?.let { "갱신 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun vms(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "VMS") { item ->
            val lat = item.text("coordY")?.toDoubleOrNull() ?: return@parseItems null
            val lng = item.text("coordX")?.toDoubleOrNull() ?: return@parseItems null
            feature(
                layer = "its_vms",
                id = "${item.text("vmsId") ?: "vms-$lat-$lng"}:${item.text("messageNo").orEmpty()}",
                title = item.text("roadName")?.takeIf(String::isNotBlank)?.let { "VMS · $it" } ?: "도로전광표지",
                details = listOfNotNull(
                    item.text("message")?.replace('|', '\n')?.takeIf(String::isNotBlank),
                    item.text("roadDrcType"),
                    item.text("createdDate")?.let { "표출 갱신 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun safeDriving(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "주의운전") { item ->
            val lat = item.text("startY")?.toDoubleOrNull() ?: return@parseItems null
            val lng = item.text("startX")?.toDoubleOrNull() ?: return@parseItems null
            feature(
                layer = "its_safe_driving",
                id = item.text("occrrncId") ?: "${item.text("startStdLinkId")}:${item.text("outbrkType")}",
                title = item.text("outbrkType")?.let { "주의운전 · $it" } ?: "주의운전 구간",
                details = listOfNotNull(
                    item.text("message"),
                    item.text("routeName")?.takeIf(String::isNotBlank) ?: item.text("revRouteName"),
                    item.text("roadDrcType") ?: item.text("revRoadDrcType"),
                    item.text("priority")?.let { "우선순위 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun dangerousCars(xml: String): List<ItsRoadFeature> =
        parseItems(xml, "위험물 사고") { item ->
            val lng = item.text("xcrdnt")?.toDoubleOrNull() ?: return@parseItems null
            val lat = item.text("ycrdnt")?.toDoubleOrNull() ?: return@parseItems null
            feature(
                layer = "its_dangerous_cars",
                id = item.text("sntcManageNo") ?: "danger-$lat-$lng",
                title = "위험물 운송차량 사고",
                details = listOfNotNull(
                    item.text("acdntOccrrncDt")?.let { "발생 $it" },
                    item.text("acdntAt")?.let { "사고 ${if (it == "Y") "발생" else "미발생"}" },
                    item.text("acdntEndAt")?.let { "종료 ${if (it == "Y") "완료" else "진행 중"}" },
                    item.text("streDt")?.let { "접수 $it" },
                ),
                lat = lat,
                lng = lng,
            )
        }

    fun vdsFacilities(text: String): List<ItsRoadFeature> =
        text.lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7) return@mapNotNull null
            val lng = fields[5].toDoubleOrNull() ?: return@mapNotNull null
            val lat = fields[6].toDoubleOrNull() ?: return@mapNotNull null
            if (!validCoordinates(lat, lng)) return@mapNotNull null
            val name = fields[2].takeIf(String::isNotBlank) ?: return@mapNotNull null
            feature(
                layer = "its_vds",
                id = name,
                title = "VDS · $name",
                details = listOfNotNull(
                    fields[0].takeIf(String::isNotBlank),
                    fields[1].takeIf(String::isNotBlank),
                    fields[3].takeIf(String::isNotBlank),
                    fields[4].takeIf(String::isNotBlank),
                ),
                lat = lat,
                lng = lng,
            )
        }.toList()

    fun iceSections(text: String): List<ItsRoadFeature> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val fields = line.split(',')
            if (fields.size < 10) return@mapNotNull null
            val startLat = fields[6].toDoubleOrNull() ?: return@mapNotNull null
            val startLng = fields[7].toDoubleOrNull() ?: return@mapNotNull null
            val endLat = fields[8].toDoubleOrNull() ?: return@mapNotNull null
            val endLng = fields[9].toDoubleOrNull() ?: return@mapNotNull null
            if (!validCoordinates(startLat, startLng) || !validCoordinates(endLat, endLng)) return@mapNotNull null
            feature(
                layer = "its_ice_sections",
                id = fields[0],
                title = "상습 결빙 · ${fields[4]}",
                details = listOfNotNull(
                    fields[1],
                    fields[2],
                    fields[3],
                    fields[5].takeIf(String::isNotBlank)?.let { "$it km 구간" },
                ),
                lat = (startLat + endLat) / 2.0,
                lng = (startLng + endLng) / 2.0,
            )
        }.toList()

    fun traffic(xml: String): List<ItsTrafficRow> =
        parseRows(xml, "교통소통") { item ->
            val linkId = item.text("linkId") ?: return@parseRows null
            ItsTrafficRow(
                id = linkId,
                roadName = item.text("roadName") ?: "도로 구간",
                direction = item.text("roadDrcType")?.takeIf(String::isNotBlank),
                speedKmh = item.text("speed")?.toDoubleOrNull(),
                travelTimeSeconds = item.text("travelTime")?.toDoubleOrNull(),
                updatedAt = item.text("createdDate")?.takeIf(String::isNotBlank),
            )
        }

    fun forecasts(xml: String): List<ItsForecastRow> =
        parseRows(xml, "교통예측") { item ->
            val sectionId = item.text("sectionId") ?: return@parseRows null
            val linkId = item.text("linkId") ?: return@parseRows null
            ItsForecastRow(
                id = "$sectionId:$linkId",
                sectionId = sectionId,
                sectionType = item.text("sectionType")?.takeIf(String::isNotBlank),
                detourId = item.text("detourId")?.takeIf(String::isNotBlank),
                speedKmh = item.text("speed")?.toDoubleOrNull(),
                lengthKm = item.text("length")?.toDoubleOrNull(),
                forecastDate = item.text("fcastDate")?.takeIf(String::isNotBlank),
                forecastHour = item.text("fcastHour")?.takeIf(String::isNotBlank),
            )
        }

    fun fogSections(text: String): List<ItsFogSection> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val fields = line.split(',')
            if (fields.size < 3 || fields[0].isBlank()) return@mapNotNull null
            val km = Regex("\\d+(?:\\.\\d+)?").findAll(fields[1]).mapNotNull { it.value.toDoubleOrNull() }.toList()
            ItsFogSection(fields[0], km.firstOrNull(), km.getOrNull(1), fields[2])
        }.toList()

    private fun feature(
        layer: String,
        id: String,
        title: String,
        details: List<String>,
        lat: Double,
        lng: Double,
    ): ItsRoadFeature? {
        if (!validCoordinates(lat, lng)) return null
        return ItsRoadFeature(
            id = "$layer:$id",
            layer = layer,
            title = title,
            details = details.filter(String::isNotBlank).distinct().joinToString("\n"),
            lat = lat,
            lng = lng,
        )
    }

    private fun parseItems(xml: String, source: String, parse: (Element) -> ItsRoadFeature?): List<ItsRoadFeature> =
        parseRows(xml, source, parse).distinctBy(ItsRoadFeature::id)

    private fun <T> parseRows(xml: String, source: String, parse: (Element) -> T?): List<T> {
        val document = parseDocument(xml, source)
        val errorCode = document.getElementsByTagName("errorCode").item(0)?.textContent?.trim()
        val errorMessage = document.getElementsByTagName("errorMessage").item(0)?.textContent?.trim().orEmpty()
        if (!errorCode.isNullOrBlank() || errorMessage.isNotBlank()) {
            throw IOException("ITS $source 오류${errorCode?.let { " ($it)" }.orEmpty()}: ${errorMessage.ifBlank { "알 수 없는 응답" }}")
        }
        val responseCode = document.getElementsByTagName("resultCode").item(0)?.textContent?.trim()
        val responseMessage = document.getElementsByTagName("resultMsg").item(0)?.textContent?.trim().orEmpty()
        if (!responseCode.isNullOrBlank() && responseCode !in setOf("0", "00", "200")) {
            throw IOException("ITS $source 오류 ($responseCode): ${responseMessage.ifBlank { "알 수 없는 응답" }}")
        }
        val items = document.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { index ->
            parse(items.item(index) as? Element ?: return@mapNotNull null)
        }
    }

    private fun parseDocument(xml: String, source: String) = try {
        DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(xml.trim().removePrefix("\uFEFF"))))
    } catch (e: Exception) {
        throw IOException("ITS $source XML을 읽지 못했습니다: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)

    private fun validCoordinates(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0
}

class ItsRoadRepository(
    private val key: String = BuildConfig.ITS_API_KEY.trim(),
) {
    suspend fun features(layer: String, south: Double, west: Double, north: Double, east: Double): List<ItsRoadFeature> {
        validateBounds(south, west, north, east)
        // 한국도로공사 위치 데이터(휴게소·영업소·IC·전광표지판): 전국 목록을 받아 지도 범위만
        if (layer in ExMapLayers.IDS) {
            return ExMapLayers.features(layer).filter { it.lat in south..north && it.lng in west..east }
        }
        // 집 PC 가 전국 단위로 받아 둔 저장본이 있으면 그 중 지도 범위만 (공공 API 를 직접 부르지 않음)
        snapshotFeatures(layer)?.let { all ->
            return all.filter { it.lat in south..north && it.lng in west..east }
        }
        requireKey()
        val request = when (layer) {
            "its_events" -> request("eventInfo", south, west, north, east) {
                addQueryParameter("type", "all")
                addQueryParameter("eventType", "all")
            }
            "its_disasters" -> request("disasterInfo", south, west, north, east) {
                addQueryParameter("category", "D")
                addQueryParameter("eventType", "all")
                addQueryParameter("startDate", java.time.LocalDate.now().minusDays(7).toString().replace("-", ""))
                addQueryParameter("endDate", java.time.LocalDate.now().toString().replace("-", ""))
            }
            "its_vsl" -> request("vslInfo")
            "its_vms" -> request("vmsInfo")
            "its_safe_driving" -> request("posIncidentInfo", south, west, north, east)
            "its_dangerous_cars" -> request("dangerousCarInfo")
            else -> throw IOException("지원하지 않는 ITS 지도 레이어입니다: $layer")
        }
        val xml = UticHttp.get(request, providerName = "ITS $layer")
        return withContext(Dispatchers.Default) {
            when (layer) {
                "its_events" -> ItsRoadParser.events(xml)
                "its_disasters" -> ItsRoadParser.disasters(xml)
                "its_vsl" -> ItsRoadParser.vsl(xml)
                "its_vms" -> ItsRoadParser.vms(xml)
                "its_safe_driving" -> ItsRoadParser.safeDriving(xml)
                "its_dangerous_cars" -> ItsRoadParser.dangerousCars(xml)
                else -> emptyList()
            }
        }
    }

    private suspend fun snapshotFeatures(layer: String): List<ItsRoadFeature>? = when (layer) {
        "its_events" -> OverlaySnapshot.get("ov_its_events.xml") { ItsRoadParser.events(it) }
        "its_disasters" -> OverlaySnapshot.get("ov_its_disasters.xml") { ItsRoadParser.disasters(it) }
        "its_safe_driving" -> OverlaySnapshot.get("ov_its_safe_driving.xml") { ItsRoadParser.safeDriving(it) }
        else -> null
    }

    suspend fun traffic(south: Double, west: Double, north: Double, east: Double): List<ItsTrafficRow> {
        requireKey()
        validateBounds(south, west, north, east)
        val request = request("trafficInfo", south, west, north, east) {
            addQueryParameter("type", "all")
            addQueryParameter("routeNo", "all")
            addQueryParameter("drcType", "all")
        }
        val xml = UticHttp.get(request, providerName = "ITS 교통소통")
        return withContext(Dispatchers.Default) { ItsRoadParser.traffic(xml).take(MAX_ROWS) }
    }

    suspend fun forecast(sectionId: String, date: String, hour: Int): List<ItsForecastRow> {
        requireKey()
        if (sectionId.isBlank() || !Regex("\\d{8}").matches(date) || hour !in 0..23) {
            throw IOException("ITS 교통예측 조회 조건이 올바르지 않습니다.")
        }
        val request = request("bypassFCastInfo") {
            addQueryParameter("sectionId", sectionId)
            addQueryParameter("fCastDate", date)
            addQueryParameter("fCastHour", hour.toString().padStart(2, '0'))
        }
        val xml = UticHttp.get(request, providerName = "ITS 교통예측")
        return withContext(Dispatchers.Default) { ItsRoadParser.forecasts(xml).take(MAX_ROWS) }
    }

    fun mainlineSections(text: String): List<ItsMainlineSection> =
        text.lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 8 || fields[0].toIntOrNull() == null) return@mapNotNull null
            ItsMainlineSection(fields[0], fields[1], fields[4], fields[5], fields[6], fields[7])
        }.toList()

    private fun request(
        endpoint: String,
        south: Double? = null,
        west: Double? = null,
        north: Double? = null,
        east: Double? = null,
        configure: HttpUrl.Builder.() -> Unit = {},
    ): Request {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("openapi.its.go.kr")
            .port(9443)
            .addPathSegment(endpoint)
            .addQueryParameter("apiKey", key)
            .apply {
                if (south != null && west != null && north != null && east != null) {
                    addQueryParameter("minX", west.toString())
                    addQueryParameter("maxX", east.toString())
                    addQueryParameter("minY", south.toString())
                    addQueryParameter("maxY", north.toString())
                }
                configure()
            }
            .addQueryParameter("getType", "xml")
            .build()
        return Request.Builder().url(url).build()
    }

    private fun requireKey() {
        if (key.isBlank()) throw IOException("local.properties에 ITS_API_KEY를 설정하세요.")
    }

    private fun validateBounds(south: Double, west: Double, north: Double, east: Double) {
        if (south !in -90.0..90.0 || north !in -90.0..90.0 ||
            west !in -180.0..180.0 || east !in -180.0..180.0 ||
            south > north || west > east
        ) throw IOException("ITS 지도 범위가 올바르지 않습니다.")
    }

    private companion object {
        const val MAX_ROWS = 500
    }
}
