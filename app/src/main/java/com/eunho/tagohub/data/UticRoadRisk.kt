package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import java.io.IOException
import okhttp3.Request

data class UticRoadRisk(
    val id: String,
    val title: String,
    val address: String?,
    val grade: String?,
    val precipitation: String?,
    val temperature: String?,
    val lat: Double,
    val lng: Double,
)

object UticRoadRiskParser {
    @Throws(IOException::class)
    fun parse(json: String): List<UticRoadRisk> {
        val root = MiniJson.parse(json) as? Map<*, *>
            ?: throw IOException("UTIC 도로위험예보 JSON 형식이 올바르지 않습니다.")
        val code = root["resultCode"]?.toString()
        val message = root["resultMsg"]?.toString().orEmpty()
        val rows = root["items"] as? List<*>
        if (code == "01" && rows.isNullOrEmpty() && message.contains("데이터가 존재하지 않습니다")) return emptyList()
        if (code !in setOf("00", "04")) {
            throw IOException("UTIC 도로위험예보 오류${code?.let { " ($it)" }.orEmpty()}: ${message.ifBlank { "알 수 없는 응답" }}")
        }
        if (rows == null) return emptyList()
        return rows.mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            val lng = row["LOCATION_X"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = row["LOCATION_Y"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val grade = row["GRADE"]?.toString()?.takeIf(String::isNotBlank)
            UticRoadRisk(
                id = row["SAFEDATA_ID"]?.toString()?.takeIf(String::isNotBlank) ?: "risk-$lat-$lng",
                title = "도로 위험 예보${grade?.let { " ($it)" }.orEmpty()}",
                address = row["ADDRESS_NEW"]?.toString()?.takeIf(String::isNotBlank)
                    ?: row["ADDRESS_JIBUN"]?.toString()?.takeIf(String::isNotBlank),
                grade = grade,
                precipitation = row["RN1"]?.toString()?.takeIf(String::isNotBlank),
                temperature = row["TH1"]?.toString()?.takeIf(String::isNotBlank),
                lat = lat,
                lng = lng,
            )
        }.distinctBy(UticRoadRisk::id)
    }
}

class UticRoadRiskRepository(
    private val key: String = BuildConfig.UTIC_API_KEY.trim(),
) {
    suspend fun roadRisks(): List<UticRoadRisk> {
        OverlaySnapshot.get("ov_utic_road_risk.json") { UticRoadRiskParser.parse(it) }?.let { return it }
        if (key.isBlank()) throw IOException("local.properties에 UTIC_API_KEY를 설정하세요.")
        val request = Request.Builder().url(UticFeeds.roadRisks.url(key)).build()
        return UticHttp.get(request).let(UticRoadRiskParser::parse)
    }
}
