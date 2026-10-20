package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import javax.xml.parsers.DocumentBuilderFactory

data class ItsCctv(
    val id: String,
    val name: String,
    val roadType: String,
    val streamUrl: String,
    val format: String?,
    val resolution: String?,
    val lat: Double,
    val lng: Double,
)

object ItsCctvParser {
    @Throws(IOException::class)
    fun parse(xml: String, roadType: String): List<ItsCctv> {
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(xml.trim().removePrefix("\uFEFF"))))
        } catch (e: Exception) {
            throw IOException("ITS CCTV XML을 읽지 못했습니다: ${e.message ?: e.javaClass.simpleName}", e)
        }

        fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)

        val data = document.getElementsByTagName("data")
        if (data.length == 0) {
            val code = document.getElementsByTagName("errorCode").item(0)?.textContent?.trim()
            val message = document.getElementsByTagName("errorMessage").item(0)?.textContent?.trim()
            if (!code.isNullOrBlank() || !message.isNullOrBlank()) {
                throw IOException("ITS CCTV 응답 오류${code?.let { " ($it)" }.orEmpty()}: ${message.orEmpty()}")
            }
        }

        return (0 until data.length).mapNotNull { index ->
            val item = data.item(index) as? Element ?: return@mapNotNull null
            val lat = item.text("coordy")?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = item.text("coordx")?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            val streamUrl = item.text("cctvurl") ?: return@mapNotNull null
            if (!streamUrl.startsWith("https://", ignoreCase = true) &&
                !streamUrl.startsWith("http://", ignoreCase = true)
            ) return@mapNotNull null
            val type = item.text("cctvtype")
            if (type !in setOf("1", "4")) return@mapNotNull null
            val name = item.text("cctvname") ?: "ITS CCTV"
            ItsCctv(
                id = "$roadType:${item.text("roadsectionid").orEmpty()}:$lat:$lng",
                name = name,
                roadType = roadType,
                streamUrl = streamUrl,
                format = item.text("cctvformat"),
                resolution = item.text("cctvresolution"),
                lat = lat,
                lng = lng,
            )
        }.distinctBy(ItsCctv::id)
    }
}

class ItsCctvRepository(
    private val key: String = BuildConfig.ITS_API_KEY.trim(),
) {
    suspend fun cctvs(south: Double, west: Double, north: Double, east: Double): List<ItsCctv> {
        if (south !in -90.0..90.0 || north !in -90.0..90.0 ||
            west !in -180.0..180.0 || east !in -180.0..180.0 ||
            south > north || west > east
        ) throw IOException("ITS CCTV 지도 범위가 올바르지 않습니다.")

        return coroutineScope {
            listOf("ex", "its").map { type ->
                async {
                    // 집 PC 가 전국 CCTV 목록을 받아 둔 저장본이 있으면 그 중 지도 범위만 (공공 API 를 직접 부르지 않음)
                    OverlaySnapshot.get("ov_its_cctv_$type.xml") { ItsCctvParser.parse(it, type) }?.let { all ->
                        return@async all.filter { it.lat in south..north && it.lng in west..east }
                    }
                    if (key.isBlank()) throw IOException("local.properties에 ITS_API_KEY를 설정하세요.")
                    val url = HttpUrl.Builder()
                        .scheme("https")
                        .host("openapi.its.go.kr")
                        .port(9443)
                        .addPathSegment("cctvInfo")
                        .addQueryParameter("apiKey", key)
                        .addQueryParameter("type", type)
                        .addQueryParameter("cctvType", "1")
                        .addQueryParameter("minX", west.toString())
                        .addQueryParameter("maxX", east.toString())
                        .addQueryParameter("minY", south.toString())
                        .addQueryParameter("maxY", north.toString())
                        .addQueryParameter("getType", "xml")
                        .build()
                    val request = Request.Builder().url(url).build()
                    UticHttp.get(request, providerName = "ITS CCTV").let { xml ->
                        ItsCctvParser.parse(xml, type)
                    }
                }
            }.map { it.await() }.flatten().distinctBy(ItsCctv::id).take(MAX_CCTVS)
        }
    }

    private companion object {
        const val MAX_CCTVS = 500
    }
}
