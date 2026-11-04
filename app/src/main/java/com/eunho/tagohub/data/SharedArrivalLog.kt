package com.eunho.tagohub.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 지난 운행 기록을 중계 서버(server/tago-proxy, /log)에 모아서 함께 쓰기.
 * 이 폰이 본 "버스가 정류장을 지난 시각"(노선 ID·정류장 ID·시각만, 개인 정보 없음)을 모아 올리고,
 * 시간표를 볼 때 다른 사용자들이 올린 기록을 받아 이 폰의 기록과 합친다. 서버를 안 쓰면 아무것도 안 함.
 */
object SharedArrivalLog {
    /** 모았다가 한 번에 올리는 간격 */
    private const val FLUSH_MS = 60_000L
    private const val MAX_PER_UPLOAD = 300
    /** 같은 정류장에서 이만큼(초) 안의 기록은 같은 버스로 봄 (여러 폰이 같은 버스를 올린 경우) */
    const val SAME_BUS_SEC = 120L

    private val pending = ConcurrentHashMap<String, MutableList<ArrivalLog.Hit>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var flushing = false

    private val client: OkHttpClient by lazy {
        Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** [ArrivalLog] 가 새 기록을 남길 때 부름 */
    fun enqueue(routeId: String, hits: List<ArrivalLog.Hit>) {
        if (hits.isEmpty() || DataProxy.current == null) return
        pending.getOrPut(routeId) { java.util.Collections.synchronizedList(ArrayList()) }.addAll(hits)
        if (flushing) return
        flushing = true
        scope.launch {
            delay(FLUSH_MS)
            flushing = false
            flush()
        }
    }

    private fun flush() {
        val proxy = DataProxy.current ?: return
        for (routeId in pending.keys.toList()) {
            val list = pending.remove(routeId) ?: continue
            val hits = synchronized(list) { list.toList() }
            hits.chunked(MAX_PER_UPLOAD).forEach { chunk ->
                val body = JSONObject()
                    .put("routeId", routeId)
                    .put("hits", JSONArray().apply { chunk.forEach { put(JSONArray().put(it.nodeId).put(it.epochSec)) } })
                    .toString()
                runCatching {
                    client.newCall(
                        Request.Builder().url(proxy.url.trimEnd('/') + "/log/hits")
                            .header(DataProxy.TOKEN_HEADER, proxy.token)
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute().close()
                }   // 못 올리면 버림 (이 폰의 기록은 따로 남아 있음)
            }
        }
    }

    /** 서버에 모인 이 노선·정류장 기록 (최근 6주). 서버를 안 쓰거나 실패하면 빈 목록 */
    suspend fun fetch(routeId: String, nodeId: String): List<ArrivalLog.Hit> = withContext(Dispatchers.IO) {
        val proxy = DataProxy.current ?: return@withContext emptyList()
        runCatching {
            val url = proxy.url.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegments("log/stop")
                .addQueryParameter("route", routeId).addQueryParameter("node", nodeId)
                .build()
            client.newCall(Request.Builder().url(url).header(DataProxy.TOKEN_HEADER, proxy.token).build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                parseMinutes(nodeId, r.body.string())
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseMinutes(nodeId: String, json: String): List<ArrivalLog.Hit> {
        val arr = JSONObject(json).optJSONArray("minutes") ?: return emptyList()
        return (0 until arr.length()).map { ArrivalLog.Hit(nodeId, arr.getLong(it) * 60 + 30) }
    }

    /**
     * 이 폰의 기록과 서버 기록 합치기. 같은 정류장에서 [SAME_BUS_SEC] 안의 기록은 같은 버스라 하나만 남김
     * (이 폰이 올린 것이 서버에도 있으므로). 이 폰의 기록을 먼저 남김.
     */
    fun merge(local: List<ArrivalLog.Hit>, remote: List<ArrivalLog.Hit>): List<ArrivalLog.Hit> {
        if (remote.isEmpty()) return local
        val kept = HashMap<String, java.util.TreeSet<Long>>()
        val out = ArrayList<ArrivalLog.Hit>(local.size + remote.size)
        for (h in local + remote) {
            val set = kept.getOrPut(h.nodeId) { java.util.TreeSet() }
            val near = set.ceiling(h.epochSec - SAME_BUS_SEC)
            if (near != null && near <= h.epochSec + SAME_BUS_SEC) continue
            set += h.epochSec
            out += h
        }
        return out
    }
}
