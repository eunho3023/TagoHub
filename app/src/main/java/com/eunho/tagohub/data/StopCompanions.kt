package com.eunho.tagohub.data

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.util.Log
import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 같은 자리에 여러 시·군 정류장이 겹치는 곳의 표 (assets/stop_companions.tsv).
 *
 * `pipeline/build_stop_companions.js` 가 `bus_stops.db` 의 좌표로 30m 규칙을 미리 계산해 만들어 두므로,
 * 정류장을 열 때 DB 를 훑거나 TAGO 주변 정류장 API 를 부르지 않고 바로 타 시·군을 함께 보여 줄 수 있다.
 * (전에는 첫 화면을 한 시·군으로 그리고 나머지를 네트워크로 찾아 붙여서, 진안을 누르면 전주가 늦게 떴다.)
 *
 * 표는 정류장 좌표가 바뀔 때만 다시 만들면 된다.
 */
object StopCompanions {
    private const val TAG = "TagoCompanions"
    private const val PREF = "companions_update"
    private const val NAME = "stop_companions.tsv"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private var appContext: Context? = null
    @Volatile private var loaded = false
    private var map: Map<String, List<BusStop>> = emptyMap()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // 서버(Firebase 등)에서 받아 둔 파일이 있으면 그것부터, 없으면 앱에 넣은 표
            val cached = appContext?.let { c -> File(c.filesDir, NAME).takeIf { it.isFile && it.length() > 1024 } }
            val text = cached?.let { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
                ?: appContext?.let { c ->
                    runCatching { c.assets.open(NAME).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
                }
            if (text != null) map = parse(text)
            loaded = true
        }
    }

    /**
     * 서버에 올린 표가 앱에 넣은 것보다 새로우면 받아서 다음부터 그 파일을 쓴다.
     * 하루 한 번, 와이파이에서만, ETag 가 그대로면(304) 내려받지 않는다. 실패하면 앱에 넣은 표를 그대로 씀.
     * 주소는 local.properties 의 STOP_COMPANIONS_URL (Firebase Storage·Hosting 주소).
     */
    suspend fun refresh(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val url = runCatching { BuildConfig.STOP_COMPANIONS_URL.trim() }.getOrDefault("")
        if (url.isEmpty()) return@withContext
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last", 0) < 20 * 3600_000L) return@withContext
        val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || cm.activeNetwork == null || cm.isActiveNetworkMetered) return@withContext
        prefs.edit().putLong("last", now).apply()
        runCatching {
            val target = File(ctx.filesDir, NAME)
            val etagFile = File(ctx.filesDir, "$NAME.etag")
            val etag = etagFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
            val req = Request.Builder().url(url).apply {
                if (etag.isNotEmpty() && target.isFile) header("If-None-Match", etag)
            }.build()
            client.newCall(req).execute().use { r ->
                if (r.code == 304) return@runCatching
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body ?: error("빈 응답")
                val tmp = File(ctx.filesDir, "$NAME.tmp")
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() < 1024) { tmp.delete(); error("파일이 너무 작음") }
                if (!tmp.renameTo(target)) { tmp.delete(); error("바꿔 쓰기 실패") }
                etagFile.writeText(r.header("ETag").orEmpty())
                synchronized(this@StopCompanions) {
                    loaded = false
                    map = emptyMap()
                }
                Log.i(TAG, "정류장 동반 표를 서버 것으로 바꿈 (${target.length()} bytes)")
            }
        }.onFailure { Log.w(TAG, "정류장 동반 표 갱신 실패: ${it.message}") }
    }

    /** 이 정류장과 같은 자리에 서는 다른 시·군 정류장 (없으면 빈 목록) */
    fun of(nodeId: String): List<BusStop> {
        ensure()
        return map[nodeId].orEmpty()
    }

    internal fun set(m: Map<String, List<BusStop>>) {
        synchronized(this) {
            map = m
            loaded = true
        }
    }

    /** "정류장ID \t id|시·군코드|번호|이름|위도|경도;…" 줄들 */
    fun parse(text: String): Map<String, List<BusStop>> {
        val out = HashMap<String, List<BusStop>>()
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val id = line.substring(0, tab).trim()
            if (id.isEmpty()) return@forEach
            val list = line.substring(tab + 1).split(';').mapNotNull { enc ->
                val f = enc.split('|')
                if (f.size < 6) return@mapNotNull null
                val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
                val lng = f[5].toDoubleOrNull() ?: return@mapNotNull null
                BusStop(f[1], f[0], f[3], f[2].ifBlank { null }, lat, lng)
            }
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }
}
