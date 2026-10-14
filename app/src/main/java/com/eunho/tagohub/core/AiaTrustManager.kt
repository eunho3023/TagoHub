package com.eunho.tagohub.core

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 서버가 중간 인증서를 빠뜨렸을 때 브라우저처럼 보충해서 검증하는 TrustManager.
 *
 * 1) 기기의 기본 검증 (서버 이름과 함께)
 * 2) 실패하면 인증서의 AIA(caIssuers) 주소에서 빠진 중간 인증서를 받아 체인에 붙이고
 * 3) 다시 같은 기본 검증 (신뢰할 수 있는 루트까지 이어져야만 통과)
 *
 * verify: 실제 검증. 안드로이드는 앱에 도메인별 보안 설정(network_security_config)이 있으면
 * 서버 이름을 함께 넘기는 검증(X509TrustManagerExtensions)만 허용하므로, 연결에서 서버 이름을 꺼내 넘긴다.
 */
class AiaTrustManager(
    private val system: X509TrustManager = defaultTrustManager(),
    private val verify: (Array<X509Certificate>, String, String?) -> Unit = { c, a, _ -> system.checkServerTrusted(c, a) },
    private val fetch: (String) -> ByteArray? = ::download,
) : X509ExtendedTrustManager() {

    private val cache = ConcurrentHashMap<String, X509Certificate>()

    /** 마지막 실패 진단 (서버 인증서 발급자, 중간 인증서 주소·받기 결과). 화면 오류 문구에 붙인다 */
    @Volatile var lastReport: String? = null
        private set
    private val fetchLog = StringBuilder()

    // ── 서버 인증서: 연결(소켓·엔진)에서 서버 이름을 꺼내 함께 검증 ──

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
        val host = (socket as? SSLSocket)?.handshakeSession?.peerHost ?: socket?.inetAddress?.hostName
        check(chain, authType, host)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        check(chain, authType, engine?.handshakeSession?.peerHost ?: engine?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = check(chain, authType, null)

    private fun check(chain: Array<out X509Certificate>, authType: String, host: String?) {
        val c = arrayOf(*chain)
        try {
            verify(c, authType, host)
        } catch (e: CertificateException) {
            synchronized(fetchLog) { fetchLog.setLength(0) }
            val completed = complete(chain)
            if (completed == null) {
                lastReport = report(chain, null)
                throw e
            }
            try {
                verify(completed, authType, host)
            } catch (e2: CertificateException) {
                lastReport = report(chain, completed)
                throw e2
            }
        }
    }

    private fun short(dn: String): String =
        dn.split(",").map { it.trim() }.filter { it.startsWith("CN=") || it.startsWith("O=") || it.startsWith("OU=") }.joinToString(", ").ifEmpty { dn }

    private fun report(sent: Array<out X509Certificate>, completed: Array<X509Certificate>?): String {
        val last = (completed ?: sent).last()
        return buildString {
            append("서버가 보낸 인증서 ${sent.size}장, 마지막 발급자: ${short(sent.last().issuerX500Principal.name)}")
            synchronized(fetchLog) { if (fetchLog.isNotEmpty()) append(" / 중간 인증서: ").append(fetchLog) }
            if (completed != null) append(" / 보충 후 최상위: ${short(last.issuerX500Principal.name)}")
            else append(" / 보충할 중간 인증서 없음")
        }
    }

    // ── 클라이언트 인증서는 쓰지 않음: 기본 검증에 그대로 ──

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    /** 빠진 중간 인증서를 AIA 로 최대 3단계까지 받아 붙인 체인. 받을 게 없으면 null */
    fun complete(chain: Array<out X509Certificate>): Array<X509Certificate>? {
        val out = chain.toMutableList()
        repeat(3) {
            val last = out.last()
            if (last.subjectX500Principal == last.issuerX500Principal) return@repeat // 자체 서명 루트
            val ext = last.getExtensionValue(Aia.OID_AIA) ?: return@repeat
            val next = Aia.caIssuersUrls(ext).firstNotNullOfOrNull { url ->
                cache[url] ?: fetchCert(url)?.also { cache[url] = it }
            } ?: return@repeat
            if (out.any { it == next }) return@repeat
            out += next
        }
        return if (out.size > chain.size) out.toTypedArray() else null
    }

    private fun fetchCert(url: String): X509Certificate? {
        val tries = if (url.startsWith("http://")) listOf(url, "https://" + url.removePrefix("http://")) else listOf(url)
        for (u in tries) {
            val got = runCatching { fetch(u) }
            synchronized(fetchLog) {
                if (fetchLog.isNotEmpty()) fetchLog.append(", ")
                fetchLog.append(u).append(if (got.getOrNull() != null) " 받음" else " 실패(${got.exceptionOrNull()?.javaClass?.simpleName ?: "응답 없음"})")
            }
            val bytes = got.getOrNull() ?: continue
            val certs = runCatching {
                CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
            }.getOrNull()
            certs?.firstOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        /** 기기의 기본 검증기 (안드로이드에서는 앱의 network_security_config 를 따르는 것) */
        fun defaultTrustManager(): X509TrustManager {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            return f.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /** http 는 소켓 직접(안드로이드 http 차단 우회, 받는 건 서명된 공개 인증서), https 는 일반 연결 */
        private fun download(url: String): ByteArray? {
            if (url.startsWith("http://")) return PlainHttp.get(url)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            return try {
                if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
            } finally {
                c.disconnect()
            }
        }
    }
}
