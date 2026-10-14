package com.eunho.tagohub

import com.eunho.tagohub.core.AiaTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** 흐름 검증: 기본 검증 실패 → AIA 로 중간 인증서 보충 → 기본 검증 재시도 */
class AiaTrustTest {
    /** 실제 공인 인증서들 (JVM 신뢰 저장소에서) */
    private val real: List<X509Certificate> = AiaTrustManager.defaultTrustManager().acceptedIssuers.toList()

    @Test
    fun alreadyTrusted_passesWithoutDownload() {
        var downloads = 0
        val ok = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        AiaTrustManager(ok, fetch = { downloads++; null }).checkServerTrusted(arrayOf(real.first()), "RSA")
        assertEquals(0, downloads)
    }

    @Test
    fun untrusted_withNothingToFetch_keepsOriginalError() {
        val strict = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = throw CertificateException("Trust anchor for certification path not found.")
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        try {
            AiaTrustManager(strict, fetch = { null }).checkServerTrusted(arrayOf(real.first()), "RSA")
            throw AssertionError("should fail")
        } catch (e: CertificateException) {
            assertTrue(e.message!!.contains("Trust anchor"))
        }
    }

    private fun res(name: String) = javaClass.getResourceAsStream("/fixtures/pki/$name")!!
    private fun cert(name: String) = CertificateFactory.getInstance("X.509").generateCertificate(res(name)) as X509Certificate

    /** 신뢰 저장소에 루트만 있는 실제 PKIX 검증기 */
    private fun rootOnlyTrust(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("root", cert("root.pem")) }
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 실제 암호 검증: 서버가 자기 인증서만 보내면(중간 인증서 누락) 기본 검증은 실패하지만,
     * AIA 주소에서 중간 인증서를 받아 붙이면 같은 기본 검증으로 통과.
     */
    @Test
    fun missingIntermediate_completedViaAia_passesRealPkix() {
        val leaf = cert("leaf.pem")
        val strict = rootOnlyTrust()
        try {
            strict.checkServerTrusted(arrayOf(leaf), "RSA")
            throw AssertionError("기본 검증은 실패해야 함")
        } catch (e: CertificateException) { /* 예상: 중간 인증서 없음 */ }

        val asked = ArrayList<String>()
        val tm = AiaTrustManager(strict, fetch = { url ->
            asked += url
            if (url == "http://crt.example.test/intermediate.crt") res("intermediate.crt").readBytes() else null
        })
        tm.checkServerTrusted(arrayOf(leaf), "RSA") // 예외 없으면 통과
        assertEquals(listOf("http://crt.example.test/intermediate.crt"), asked)
    }

    /** 다른 곳의 인증서를 받아와도 루트로 이어지지 않으면 통과시키지 않음 */
    @Test
    fun wrongIntermediate_stillRejected() {
        val leaf = cert("leaf.pem")
        val tm = AiaTrustManager(rootOnlyTrust(), fetch = { _ -> res("root.pem").readBytes() }) // 엉뚱한 인증서
        try {
            tm.checkServerTrusted(arrayOf(leaf), "RSA")
            throw AssertionError("should fail")
        } catch (e: CertificateException) { /* 예상 */ }
    }

    /**
     * 안드로이드 동작 재현: 도메인별 설정이 있으면 서버 이름 없는 검증을 거부한다.
     * 연결(SSLEngine)로 불리면 서버 이름이 검증까지 넘어가서, 중간 인증서 보충 후 통과해야 함.
     */
    @Test
    fun hostnameFromConnection_passedToVerify() {
        val leaf = cert("leaf.pem")
        val strict = rootOnlyTrust()
        val hosts = ArrayList<String?>()
        val androidLike: (Array<X509Certificate>, String, String?) -> Unit = { c, a, host ->
            hosts += host
            if (host == null) throw CertificateException("Domain specific configurations require that hostname aware checkServerTrusted(X509Certificate[], String, String) is used")
            strict.checkServerTrusted(c, a)
        }
        val tm = AiaTrustManager(strict, verify = androidLike, fetch = { res("intermediate.crt").readBytes() })
        val engine = javax.net.ssl.SSLContext.getDefault().createSSLEngine("bus.jeju.example", 443)
        tm.checkServerTrusted(arrayOf(leaf), "RSA", engine) // 예외 없으면 통과
        assertTrue(hosts.isNotEmpty() && hosts.all { it == "bus.jeju.example" })
    }

    /** 실패하면 무엇이 문제인지 남김: 서버 인증서 발급자, 중간 인증서 주소와 받기 결과 */
    @Test
    fun failureReport_namesIssuerAndFetchResult() {
        val tm = AiaTrustManager(rootOnlyTrust(), fetch = { null })
        try {
            tm.checkServerTrusted(arrayOf(cert("leaf.pem")), "RSA")
            throw AssertionError("should fail")
        } catch (e: CertificateException) { }
        val r = tm.lastReport!!
        assertTrue(r, r.contains("Test Intermediate CA"))
        assertTrue(r, r.contains("http://crt.example.test/intermediate.crt 실패"))
    }
}
