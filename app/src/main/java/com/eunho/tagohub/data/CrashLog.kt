package com.eunho.tagohub.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 앱이 비정상 종료되면 원인(스택)을 폰에 저장해 두고, 더보기에서 보고 공유할 수 있게 한다.
 * 가장 최근 하나만 남긴다. 주소에 인증키가 섞여 있을 수 있어서 저장 전에 지운다.
 * 원래 처리기(Crashlytics 자동 보고 → 시스템)에도 넘기는데, 오류 문구에 키가 있으면 키를 지운 사본을 넘긴다.
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
            previous?.uncaughtException(thread, runCatching { scrubbed(error) }.getOrDefault(error))
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

    /**
     * 오류 문구(원인 포함)에 인증키가 있으면, 키를 지운 문구로 바꾼 사본을 만듦 (스택 위치는 그대로라 보고서에서 같은 오류로 묶임).
     * 키가 없으면 원래 오류를 그대로 돌려줌.
     */
    internal fun scrubbed(error: Throwable): Throwable {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
        if (chain.none { (it.message ?: "") != redact(it.message ?: "") }) return error
        fun copy(t: Throwable, depth: Int): Throwable {
            val cause = t.cause?.takeIf { it !== t && depth < 9 }?.let { copy(it, depth + 1) }
            val msg = t.message?.let(::redact)
            return Scrubbed("${t.javaClass.name}${msg?.let { ": $it" } ?: ""}", cause).also { it.stackTrace = t.stackTrace }
        }
        return copy(error, 0)
    }

    /** 키를 지운 오류 사본. 문구 앞에 원래 오류 종류를 붙여 둠 */
    class Scrubbed(message: String, cause: Throwable?) : RuntimeException(message, cause)

    /** 주소의 인증키(serviceKey=…, key=…, Authorization 값)를 지움 */
    internal fun redact(text: String): String = text
        .replace(Regex("(?i)(service[_-]?key|api[_-]?key|appkey|key)=[^&\\s\"')]+"), "$1=<지움>")
        .replace(Regex("(?i)(KakaoAK|Bearer)\\s+[A-Za-z0-9._~+/=-]+"), "$1 <지움>")
}
