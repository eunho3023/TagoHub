package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 지도 레이어 정보(돌발·재난·CCTV 목록 등)의 저장본.
 * 집 PC 가 공공 API 에서 전국 단위로 미리 받아 GitHub Release(data-latest)에 `ov_*.gz` 로 올려 둔 것(pipeline/build_overlays.py)을
 * 앱이 읽는다 → 지도를 움직일 때마다 공공 API 를 부르지 않아 빠르고 요청 제한에 안 걸림.
 * 받지 못하거나 오래됐으면 null → 호출한 쪽이 예전처럼 공공 API 를 직접 부른다.
 */
object OverlaySnapshot {
    private const val TAG = "TagoOverlay"
    const val MAX_AGE_SEC = 30 * 60L
    private const val META = "ov_meta.json"
    private const val META_TTL_MS = 2 * 60_000L
    private const val FAIL_BACKOFF_MS = 60_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val lock = Mutex()
    private var meta: Map<String, Long> = emptyMap()
    private var metaAt = 0L
    private var failedUntil = 0L

    /** 파일 이름 → (저장본 시각, 해석한 결과). 시각이 같으면 다시 받지도 해석하지도 않음 */
    private val parsed = HashMap<String, Pair<Long, Any>>()

    /** 저장본을 받아 [parse] 한 결과. 없거나 오래됐으면 null */
    suspend fun <T : Any> get(name: String, maxAgeSec: Long = MAX_AGE_SEC, parse: (String) -> T): T? = withContext(Dispatchers.IO) {
        val base = BuildConfig.DATA_MANIFEST_URL.trim().substringBeforeLast('/', "")
        if (base.isEmpty()) return@withContext null
        lock.withLock {
            val now = System.currentTimeMillis()
            if (now < failedUntil) return@withLock null
            try {
                if (now - metaAt > META_TTL_MS || meta.isEmpty()) {
                    meta = parseMeta(download("$base/$META") ?: error("ov_meta 없음"))
                    metaAt = now
                }
                val updated = meta[name] ?: return@withLock null
                if (!isFresh(updated, now / 1000, maxAgeSec)) return@withLock null
                @Suppress("UNCHECKED_CAST")
                parsed[name]?.takeIf { it.first == updated }?.let { return@withLock it.second as T }
                val text = downloadGz("$base/$name.gz") ?: error("$name.gz 없음")
                val value = parse(text)
                parsed[name] = updated to value
                value
            } catch (e: Exception) {
                Log.w(TAG, "저장본 $name 사용 못 함: ${e.message}")
                failedUntil = now + FAIL_BACKOFF_MS
                null
            }
        }
    }

    private fun download(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }

    private fun downloadGz(url: String): String? =
        client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
            if (!r.isSuccessful) null
            else r.body?.byteStream()?.let { GZIPInputStream(it, 1 shl 16).bufferedReader(Charsets.UTF_8).readText() }
        }

    /** ov_meta.json: {"updated": {"파일이름": 초 단위 시각}} */
    internal fun parseMeta(json: String): Map<String, Long> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val updated = root["updated"] as? Map<*, *> ?: return emptyMap()
        return updated.entries.mapNotNull { (k, v) ->
            val t = v?.toString()?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            k.toString() to t
        }.toMap()
    }

    internal fun isFresh(updatedSec: Long, nowSec: Long, maxAgeSec: Long) = updatedSec in 1..(nowSec + 300) && nowSec - updatedSec <= maxAgeSec
}
