package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class UticIncident(
    val id: String,
    val title: String,
    val address: String?,
    val startDate: String?,
    val endDate: String?,
    val lat: Double,
    val lng: Double,
)

object UticIncidentParser {
    @Throws(IOException::class)
    fun parse(xml: String): List<UticIncident> {
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw IOException("UTIC 돌발정보 XML을 읽지 못했습니다.", e)
        }

        val records = document.getElementsByTagName("record")
        if (records.length == 0) {
            val code = document.getElementsByTagName("resultCode").item(0)?.textContent?.trim()
            val message = document.getElementsByTagName("resultMsg").item(0)?.textContent?.trim()
            if (!message.isNullOrBlank() && code !in setOf("00", "04")) {
                throw IOException("UTIC 응답 오류${code?.let { " ($it)" }.orEmpty()}: $message")
            }
        }

        fun Element.text(tag: String): String? =
            getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)

        return (0 until records.length).mapNotNull { index ->
            val record = records.item(index) as? Element ?: return@mapNotNull null
            val lng = record.text("locationDataX")?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = record.text("locationDataY")?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            UticIncident(
                id = record.text("incidentId") ?: record.text("linkId") ?: "incident-$index",
                title = record.text("incidentTitle") ?: "교통 돌발정보",
                address = record.text("addressNew") ?: record.text("addressJibun"),
                startDate = record.text("startDate"),
                endDate = record.text("endDate"),
                lat = lat,
                lng = lng,
            )
        }.distinctBy(UticIncident::id)
    }
}

class UticIncidentRepository(
    private val key: String = BuildConfig.UTIC_API_KEY.trim(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun incidents(): List<UticIncident> {
        if (key.isBlank()) throw IOException("local.properties에 UTIC_API_KEY를 설정하세요.")
        val request = Request.Builder().url(UticFeeds.incidents.url(key)).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val incidents = response.use {
                            if (!it.isSuccessful) throw IOException("UTIC 돌발정보 요청 실패 (HTTP ${it.code}).")
                            val body = it.body?.string() ?: throw IOException("UTIC 응답 본문이 비어 있습니다.")
                            UticIncidentParser.parse(body)
                        }
                        continuation.resumeWith(Result.success(incidents))
                    } catch (e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }
    }
}
