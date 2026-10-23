package com.eunho.tagohub.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 정류장·노선 데이터 자동 업데이트.
 * 하루 한 번, 무제한 네트워크(와이파이)일 때 안내 파일(DATA_MANIFEST_URL — GitHub Release data-latest 의 manifest.json)을 보고 새 버전이면 받아 바꾼다.
 * 압축 파일 그대로의 바이트로 SHA-256 을 확인하려고 Accept-Encoding: identity (서버·OkHttp 가 따로 압축·해제하지 않게).
 * 받으면서 SHA-256 확인 → 압축 풀기 → SQLite 로 열어 버전 확인 → bus_stops_dl.db 로 바꿔 넣고 StopDb 다시 열기.
 * 어느 단계든 실패하면 지금 데이터 그대로.
 */
object DataUpdater {
    private const val TAG = "TagoData"
    private const val PREF = "data_update"
    private val client = Http.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** 바꿔 넣었으면 새 버전, 아니면 null */
    suspend fun check(ctx: Context, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val url = BuildConfig.DATA_MANIFEST_URL.trim()
        if (url.isEmpty()) return@withContext null
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last", 0) < 20 * 3600_000L) return@withContext null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || cm.activeNetwork == null || cm.isActiveNetworkMetered) return@withContext null // 데이터 요금 걱정 없을 때만 (약 25MB)
        prefs.edit().putLong("last", now).apply()
        runCatching {
            val manifest = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
                if (!r.isSuccessful) error("안내 파일 HTTP ${r.code}")
                DataManifest.parse(r.body?.string().orEmpty()) ?: error("안내 파일 형식 오류")
            }
            val current = StopDb.get(ctx)?.version()
            if (!DataManifest.isNewer(manifest.version, current)) return@runCatching null
            Log.i(TAG, "새 데이터 ${manifest.version} (지금 $current) 받는 중")
            val dir = ctx.getDatabasePath("x").parentFile ?: error("DB 폴더 없음")
            dir.mkdirs()
            val tmp = File(dir, "bus_stops_dl.db.tmp")
            val md = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(DataManifest.fileUrl(url, manifest.file)).header("Accept-Encoding", "identity").build()).execute().use { r ->
                if (!r.isSuccessful) error("데이터 파일 HTTP ${r.code}")
                val body = r.body ?: error("빈 응답")
                GZIPInputStream(DigestInputStream(body.byteStream(), md), 1 shl 16).use { input ->
                    tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != manifest.sha256) { tmp.delete(); error("검증값이 다릅니다") }
            val v = StopDb.versionOf(tmp)
            if (v != manifest.version) { tmp.delete(); error("데이터 버전이 다릅니다 ($v)") }
            val target = File(dir, StopDb.DOWNLOADED)
            if (!tmp.renameTo(target)) { tmp.delete(); error("바꿔 넣기 실패") }
            StopDb.reload(ctx)
            Log.i(TAG, "정류장·노선 데이터를 ${manifest.version} 으로 바꿨습니다")
            manifest.version
        }.onFailure { Log.w(TAG, "데이터 업데이트 실패: ${it.message}") }.getOrNull()
    }
}
