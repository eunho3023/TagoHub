package com.eunho.tagohub

import com.eunho.tagohub.core.PlainHttp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 로컬 테스트 서버로 실제 소켓 통신 */
class PlainHttpTest {
    private fun serve(vararg responses: ByteArray): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            for (r in responses) ss.accept().use { c ->
                val buf = ByteArray(4096); c.getInputStream().read(buf)
                c.getOutputStream().write(r); c.getOutputStream().flush()
            }
            ss.close()
        }
        return ss.localPort
    }

    @Test
    fun downloadsBinaryBody() {
        val body = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00, 0x0D, 0x0A, 0x0D, 0x0A, 0x7F) // 바이너리(DER 처럼)
        val port = serve("HTTP/1.1 200 OK\r\nContent-Type: application/pkix-cert\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
        assertEquals(body.toList(), PlainHttp.get("http://127.0.0.1:$port/int.crt")!!.toList())
    }

    @Test
    fun followsRedirect() {
        val ss = ServerSocket(0); val port = ss.localPort; ss.close()
        val p2 = serve("HTTP/1.1 200 OK\r\n\r\nCERT".toByteArray())
        val p1 = serve("HTTP/1.1 301 Moved\r\nLocation: http://127.0.0.1:$p2/real.crt\r\n\r\n".toByteArray())
        assertEquals("CERT", String(PlainHttp.get("http://127.0.0.1:$p1/old.crt")!!))
    }

    @Test
    fun notFound_null() {
        val port = serve("HTTP/1.1 404 Not Found\r\n\r\nnope".toByteArray())
        assertEquals(null, PlainHttp.get("http://127.0.0.1:$port/x.crt"))
    }
}
