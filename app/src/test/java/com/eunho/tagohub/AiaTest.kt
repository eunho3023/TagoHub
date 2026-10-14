package com.eunho.tagohub

import com.eunho.tagohub.core.Aia
import org.junit.Assert.assertEquals
import org.junit.Test

class AiaTest {
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val len = body.size
        val lenBytes = if (len < 128) byteArrayOf(len.toByte()) else byteArrayOf(0x81.toByte(), len.toByte())
        return byteArrayOf(tag.toByte()) + lenBytes + body
    }
    private fun desc(oidLast: Int, url: String) = der(0x30,
        byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, oidLast.toByte()) + der(0x86, url.toByteArray()))

    /** 실제 인증서와 같은 구조: OCTET STRING { SEQUENCE { OCSP, caIssuers } } */
    @Test
    fun extractsCaIssuersOnly() {
        val aia = der(0x04, der(0x30,
            desc(0x01, "http://ocsp.example.com") +
            desc(0x02, "http://crt.example.com/ExampleRSAOrganizationValidationSecureServerCA.crt")))
        assertEquals(listOf("http://crt.example.com/ExampleRSAOrganizationValidationSecureServerCA.crt"), Aia.caIssuersUrls(aia))
    }

    @Test
    fun longUrl_longLengthForm() {
        val url = "http://" + "a".repeat(140) + ".crt"
        val aia = der(0x04, der(0x30, desc(0x02, url)))
        assertEquals(listOf(url), Aia.caIssuersUrls(aia))
    }

    @Test
    fun noAia_empty() {
        assertEquals(emptyList<String>(), Aia.caIssuersUrls(byteArrayOf(0x04, 0x02, 0x30, 0x00)))
    }
}
