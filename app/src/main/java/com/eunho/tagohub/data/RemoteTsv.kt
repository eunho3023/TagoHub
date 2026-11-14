package com.eunho.tagohub.data

import android.content.Context
import com.eunho.tagohub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 관리자 페이지가 D1 에 저장한 자료(/w/layouts.tsv, /w/shapes.tsv)를 폰에 받아 두는 공통 뼈대.
 *
 * - 앱을 켜면 저장해 둔 파일을 먼저 읽어 화면이 비지 않게 한다 ([read]).
 * - 새로 받을 때는 지난번 ETag 를 보내, 바뀐 것이 없으면 본문 없이 304 만 받는다 ([fetch]).
 * - 받은 본문은 임시 파일에 쓴 뒤 바꿔치기해서, 쓰다가 앱이 꺼져도 반쯤 쓴 파일이 남지 않는다 ([write]).
 */
internal object RemoteTsv {
    /** 받은 결과. [notModified] 면 저장본을 그대로 쓰면 된다 */
    data class Fetched(
        val ok: Boolean,
        val notModified: Boolean = false,
        val text: String = "",
        val etag: String? = null,
        val version: String? = null,
    )

    private fun dir(context: Context): File = File(context.filesDir, "remote_edits").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun read(context: Context, name: String): String =
        runCatching { file(context, name).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "" }.getOrDefault("")

    fun write(context: Context, name: String, text: String) {
        runCatching {
            val f = file(context, name)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    /**
     * [path] (예: "/w/shapes.tsv") 를 받는다. 서버 주소가 없으면 ok=false.
     * [versionHeader] 는 서버가 붙여 주는 버전 헤더 이름 (없으면 null).
     */
    suspend fun fetch(context: Context, path: String, etag: String?, versionHeader: String?): Fetched = withContext(Dispatchers.IO) {
        val base = BuildConfig.TAGO_PROXY_URL.trim().trimEnd('/')
        if (!base.startsWith("https://")) return@withContext Fetched(false)
        val req = Request.Builder()
            .url(base + path)
            .header("Accept", "text/plain")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()
        // 연결 풀은 앱 전체가 같이 쓴다 (Http.builder). 제한 시간만 이 요청에 맞게
        val client = Http.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        runCatching {
            client.newCall(req).execute().use { res ->
                val version = versionHeader?.let { res.header(it) }
                when {
                    res.code == 304 -> Fetched(true, notModified = true, etag = res.header("ETag"), version = version)
                    !res.isSuccessful -> Fetched(false)
                    else -> Fetched(true, text = res.body?.string().orEmpty(), etag = res.header("ETag"), version = version)
                }
            }
        }.getOrDefault(Fetched(false))
    }
}
