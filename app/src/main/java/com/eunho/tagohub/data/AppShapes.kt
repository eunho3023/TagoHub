package com.eunho.tagohub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 앱이 만든 노선 모양을 모두가 같이 씀 (중계 서버 /appshape, server/tago-proxy/src/appshape.js).
 * 폰이 노선 선을 만들면(카카오 경로·지역 공식 모양) 서버에 올리고, 다른 폰과 웹 버전은 그 모양을 받아 그림 → 앱과 웹의 노선 선이 같음.
 * 서버에는 처음 올린 것 하나만 남음 (틀어진 선은 지도 고치기 [MapEdits] 로 고침).
 */
object AppShapes {
    private val client: OkHttpClient by lazy { Http.builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    private fun url(routeId: String): String? = DataProxy.current?.let { "${it.url.trimEnd('/')}/appshape/${TagoIds.legacy(routeId)}" }

    /** 서버에 있는 앱 모양 (없거나 못 받으면 null) */
    suspend fun fetch(routeId: String): List<LatLng>? {
        val u = url(routeId) ?: return null
        val token = DataProxy.current?.token ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(u).header(DataProxy.TOKEN_HEADER, token).build()).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body.string()).optString("poly").takeIf { it.isNotEmpty() }?.let { Polyline.decode(it, 5) }
                }
            }.getOrNull()?.takeIf { it.size >= 2 }
        }
    }

    /** 이 폰이 만든 모양을 서버에 올림 (이미 있으면 서버가 그대로 둠). 실패해도 그만 */
    suspend fun upload(routeId: String, pts: List<LatLng>) {
        if (pts.size < 2) return
        val u = url(routeId) ?: return
        val token = DataProxy.current?.token ?: return
        val body = JSONObject().put("poly", encode(pts)).toString()
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(u).header(DataProxy.TOKEN_HEADER, token).put(body.toRequestBody("application/json".toMediaType())).build()).execute().close()
            }
        }
    }

    /** 점 → 폴리라인 글자 (정밀도 5, 웹·서버와 같음) */
    fun encode(pts: List<LatLng>): String {
        val sb = StringBuilder()
        var pl = 0L
        var pg = 0L
        fun num(v: Long) {
            var n = if (v < 0) (-2 * v - 1) else 2 * v
            while (n >= 0x20) { sb.append(((0x20 or (n and 0x1f).toInt()) + 63).toChar()); n = n shr 5 }
            sb.append((n + 63).toInt().toChar())
        }
        for (p in pts) {
            val a = Math.round(p.lat * 1e5)
            val b = Math.round(p.lng * 1e5)
            num(a - pl); num(b - pg)
            pl = a; pg = b
        }
        return sb.toString()
    }
}
