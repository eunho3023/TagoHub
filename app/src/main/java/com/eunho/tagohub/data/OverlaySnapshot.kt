package com.eunho.tagohub.data

import android.util.Log
import com.eunho.tagohub.BuildConfig
import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 지도 레이어 정보(돌발·재난·CCTV 목록 등)의 저장본.
 * 집 PC 가 공공 API 에서 전국 단위로 미리 받아 GitHub Release(data-latest)에 올려 둔 `ov_*.gz`(pipeline/build_overlays.py)를 앱이 읽는다.
 * 받은 파일은 휴대폰에도 저장해 두어서(메모리 → 휴대폰 저장 → 네트워크 순서) 다음에는 인터넷 없이 바로 뜬다.
 * - 새로 받은 것이 [maxAgeSec] 안이면 그대로 쓴다.
 * - [staleOkSec] 를 주면(자주 안 바뀌는 CCTV 목록 등) 그 기간 안의 오래된 저장본도 먼저 보여주고, 뒤에서 새것을 받아 바꾼다.
 * 쓸 만한 저장본이 없으면 null → 호출한 쪽이 예전처럼 공공 API 를 직접 부른다.
 */
object OverlaySnapshot {
    private const val TAG = "TagoOverlay"
    const val MAX_AGE_SEC = 30 * 60L
    private const val META = "ov_meta.json"
    private const val META_TTL_MS = 2 * 60_000L
    private const val FAIL_BACKOFF_MS = 60_000L
    /** 가진 것이 이보다 오래됐으면 뒤에서 새것이 있는지 확인 */
    private const val BG_REFRESH_AFTER_SEC = 120L

    private val client = Http.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var dir: File? = null
    private var meta: Map<String, Long> = emptyMap()
    private var metaAt = 0L
    private var failedUntil = 0L

    /** 파일 이름 → (저장본 시각, 해석한 결과). 시각이 같으면 다시 받지도 해석하지도 않음 */
    private val parsed = HashMap<String, Pair<Long, Any>>()

    /** 앱 시작 때 한 번: 받은 저장본을 휴대폰에 둘 폴더 */
    fun init(root: File) {
        dir = File(root, "overlay_cache").apply { mkdirs() }
    }

    /** 저장본을 [parse] 한 결과. 쓸 만한 것이 없으면 null */
    suspend fun <T : Any> get(
        name: String,
        maxAgeSec: Long = MAX_AGE_SEC,
        staleOkSec: Long = 0L,
        parse: (String) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val base = BuildConfig.DATA_MANIFEST_URL.trim().substringBeforeLast('/', "")
        if (base.isEmpty()) return@withContext null
        val have = lock.withLock { localCopy(name, parse) }
        if (have != null) {
            val age = System.currentTimeMillis() / 1000 - have.first
            if (age in -300..maxOf(maxAgeSec, staleOkSec)) {
                if (age > BG_REFRESH_AFTER_SEC) refreshInBackground(name, base, maxAgeSec, parse)
                return@withContext have.second
            }
        }
        val limit = maxOf(maxAgeSec, staleOkSec)
        lock.withLock { refresh(name, base, maxAgeSec, parse) }
            // 새로 받지 못해 가진 것이 너무 오래됐으면 쓰지 않음
            ?.takeIf { System.currentTimeMillis() / 1000 - it.first in -300..limit }
            ?.second
    }

    private fun <T : Any> refreshInBackground(name: String, base: String, maxAgeSec: Long, parse: (String) -> T) {
        if (!refreshing.add(name)) return
        scope.launch {
            try {
                lock.withLock { refresh(name, base, maxAgeSec, parse) }
            } finally {
                refreshing.remove(name)
            }
        }
    }

    /** 메모리 → 휴대폰 저장 순서로 이미 가진 것 (lock 안에서 부름) */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> localCopy(name: String, parse: (String) -> T): Pair<Long, T>? {
        parsed[name]?.let { return it.first to it.second as T }
        val d = dir ?: return null
        return try {
            val ts = File(d, "$name.ts").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return null
            val gz = File(d, "$name.gz").takeIf { it.exists() }?.readBytes() ?: return null
            val value = parse(gunzip(gz))
            parsed[name] = ts to value
            ts to value
        } catch (e: Exception) {
            Log.w(TAG, "휴대폰에 저장한 $name 을 읽지 못함: ${e.message}")
            null
        }
    }

    /** 네트워크로 새 저장본이 있는지 보고 있으면 받아 바꿈 (lock 안에서 부름) */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> refresh(name: String, base: String, maxAgeSec: Long, parse: (String) -> T): Pair<Long, T>? {
        val now = System.currentTimeMillis()
        val current = parsed[name]?.let { it.first to it.second as T }
        if (now < failedUntil) return current
        return try {
            if (now - metaAt > META_TTL_MS || meta.isEmpty()) {
                meta = parseMeta(download("$base/$META")?.toString(Charsets.UTF_8) ?: error("ov_meta 없음"))
                metaAt = now
            }
            val updated = meta[name] ?: return current
            if (current != null && current.first >= updated) return current
            if (!isFresh(updated, now / 1000, maxAgeSec)) return current
            val gz = download("$base/$name.gz") ?: error("$name.gz 없음")
            val value = parse(gunzip(gz))
            parsed[name] = updated to value
            save(name, gz, updated)
            updated to value
        } catch (e: Exception) {
            Log.w(TAG, "저장본 $name 새로 받지 못함: ${e.message}")
            failedUntil = now + FAIL_BACKOFF_MS
            current
        }
    }

    private fun save(name: String, gz: ByteArray, updated: Long) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "$name.gz.tmp")
            tmp.writeBytes(gz)
            tmp.renameTo(File(d, "$name.gz"))
            File(d, "$name.ts").writeText(updated.toString())
        }.onFailure { Log.w(TAG, "$name 휴대폰에 저장 실패: ${it.message}") }
    }

    private fun download(url: String): ByteArray? =
        client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }

    private fun gunzip(gz: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(gz), 1 shl 16).bufferedReader(Charsets.UTF_8).use { it.readText() }

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
