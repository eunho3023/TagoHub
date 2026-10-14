package com.eunho.tagohub.core

/**
 * 인증서의 AIA(Authority Information Access) 확장에서 caIssuers 주소(중간 인증서를 받을 수 있는 곳)를 꺼낸다.
 * 브라우저는 서버가 중간 인증서를 빠뜨려도 이 주소로 받아서 검증을 마치지만, 안드로이드는 하지 않는다.
 *
 * 입력: X509Certificate.getExtensionValue("1.3.6.1.5.5.7.1.1") 의 DER 바이트.
 * caIssuers OID = 1.3.6.1.5.5.7.48.2 (06 08 2B 06 01 05 05 07 30 02), 그 뒤 [6] uniformResourceIdentifier (0x86 길이 URL)
 */
object Aia {
    const val OID_AIA = "1.3.6.1.5.5.7.1.1"
    private val CA_ISSUERS = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

    fun caIssuersUrls(ext: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i <= ext.size - CA_ISSUERS.size) {
            if (matches(ext, i)) {
                var p = i + CA_ISSUERS.size
                if (p < ext.size && ext[p] == 0x86.toByte()) {
                    p++
                    var len = ext[p].toInt() and 0xFF
                    p++
                    if (len and 0x80 != 0) {              // 긴 길이 형식
                        val n = len and 0x7F
                        len = 0
                        repeat(n) { len = (len shl 8) or (ext[p++].toInt() and 0xFF) }
                    }
                    if (p + len <= ext.size) {
                        val url = String(ext, p, len, Charsets.US_ASCII)
                        if (url.startsWith("http://") || url.startsWith("https://")) out += url
                    }
                }
                i += CA_ISSUERS.size
            } else i++
        }
        return out
    }

    private fun matches(b: ByteArray, at: Int): Boolean {
        for (k in CA_ISSUERS.indices) if (b[at + k] != CA_ISSUERS[k]) return false
        return true
    }
}
