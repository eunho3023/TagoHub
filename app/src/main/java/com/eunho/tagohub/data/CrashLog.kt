package com.eunho.tagohub.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 비정상 종료되면 원인(스택)을 폰에 저장해 두고, 더보기에서 보고 공유할 수 있게 한다 (오류 수집 서비스 없이).
 * 가장 최근 하나만 남긴다. 주소에 인증키가 섞여 있을 수 있어서 저장 전에 지운다.
 */
object CrashLog {
    private const val MAX_CHARS = 8000
    @Volatile private var file: File? = null

    fun install(context: Context) {
        file = File(context.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file?.writeText(format(version, thread.name, error, System.currentTimeMillis()), Charsets.UTF_8) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(): String? = runCatching { file?.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() } }.getOrNull()

    fun clear() {
        runCatching { file?.delete() }
    }

    internal fun format(version: String, thread: String, error: Throwable, now: Long): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(now))
        return redact("시각: $time\n앱 버전: $version\n기기: ${android.os.Build.MODEL} (안드로이드 ${android.os.Build.VERSION.RELEASE})\n스레드: $thread\n\n$trace").take(MAX_CHARS)
    }

    /** 주소의 인증키(serviceKey=…, key=…, Authorization 값)를 지움 */
    internal fun redact(text: String): String = text
        .replace(Regex("(?i)(service[_-]?key|api[_-]?key|appkey|key)=[^&\\s\"')]+"), "$1=<지움>")
        .replace(Regex("(?i)(KakaoAK|Bearer)\\s+[A-Za-z0-9._~+/=-]+"), "$1 <지움>")
}
