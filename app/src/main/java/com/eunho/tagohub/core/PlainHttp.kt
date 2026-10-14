package com.eunho.tagohub.core

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 아주 작은 http GET (소켓 직접). 인증서의 중간 인증서 주소(AIA)는 보통 http 인데,
 * 안드로이드는 허용 목록 밖 http 를 HttpURLConnection·OkHttp 에서 막는다.
 * 받는 것이 서명된 공개 인증서라 위·변조돼도 검증에서 걸러지므로 http 로 받아도 안전하다.
 * HTTP/1.0 으로 요청해서 chunked 응답이 오지 않게 한다.
 */
object PlainHttp {
    fun get(url: String, timeoutMs: Int = 8_000, redirects: Int = 2): ByteArray? {
        val u = URL(url)
        if (u.protocol != "http") return null
        val port = if (u.port == -1) 80 else u.port
        val raw = Socket().use { s ->
            s.connect(InetSocketAddress(u.host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val path = u.file.ifEmpty { "/" }
            s.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\nHost: ${u.host}\r\nUser-Agent: TagoHub\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val out = ByteArrayOutputStream()
            s.getInputStream().copyTo(out)
            out.toByteArray()
        }
        val split = indexOf(raw, byteArrayOf(13, 10, 13, 10))
        if (split < 0) return null
        val head = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head.first().split(" ").getOrNull(1)?.toIntOrNull() ?: return null
        val body = raw.copyOfRange(split + 4, raw.size)
        if (status in 300..399 && redirects > 0) {
            val loc = head.drop(1).firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                ?.substringAfter(':')?.trim() ?: return null
            return get(URL(u, loc).toString(), timeoutMs, redirects - 1)
        }
        return if (status in 200..299) body else null
    }

    private fun indexOf(a: ByteArray, p: ByteArray): Int {
        outer@ for (i in 0..a.size - p.size) {
            for (k in p.indices) if (a[i + k] != p[k]) continue@outer
            return i
        }
        return -1
    }
}
