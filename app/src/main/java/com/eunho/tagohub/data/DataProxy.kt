package com.eunho.tagohub.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 공공데이터 중계 서버 (server/tago-proxy, Cloudflare Workers) 설정.
 * 앱은 키 없이 `{주소}/dg/{기관번호}/...` 로 묻고 [TOKEN_HEADER] 에 토큰을 붙이면, 서버가 공공데이터포털 키를 붙여 묻고 잠시 저장해 나눠 준다.
 * 주소나 토큰이 비어 있으면 [of] 가 null → 예전처럼 키로 직접 물음.
 */
data class DataProxy(val url: String, val token: String) {
    /** 이 기관(예: 1613000 = TAGO)의 Retrofit 기본 주소 */
    fun base(agency: String): String = "${url.trimEnd('/')}/dg/$agency/"

    /**
     * 키를 쓰는 기관 주소면 서버 주소로 바꿈: `https://apis.data.go.kr/1613000/...?serviceKey=TAGOHUB_TAGO_SERVICE_KEY`
     * → `{서버}/h/https/apis.data.go.kr/1613000/...?serviceKey=TAGOHUB_TAGO_SERVICE_KEY`. 키 자리표시는 서버가 진짜 키로 바꿈.
     * [HOSTS] 밖이거나 기본 포트가 아니면 null (그대로 감).
     */
    fun rewrite(original: HttpUrl): HttpUrl? {
        if (original.host !in HOSTS || original.port != HttpUrl.defaultPort(original.scheme)) return null
        val b = url.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("h").addPathSegment(original.scheme).addPathSegment(original.host)
        original.encodedPathSegments.forEach { b.addEncodedPathSegment(it) }
        b.encodedQuery(original.encodedQuery)
        return b.build()
    }

    companion object {
        const val TOKEN_HEADER = "X-TagoHub-Token"

        /**
         * 서버로 돌리는 호스트 (server/tago-proxy/src/proxy.js 의 HOSTS 와 같게).
         * 뺀 것: 국가교통정보센터(9443 포트라 Cloudflare 가 못 부름), 도시교통정보센터 UTIC(등록한 IP 에서만 응답 → "허용된 IP가 아닙니다")
         */
        val HOSTS = setOf(
            "apis.data.go.kr", "dapi.kakao.com", "apis-navi.kakaomobility.com", "openapi.kric.go.kr",
            "swopenapi.seoul.go.kr", "t-data.seoul.go.kr", "data.ex.co.kr", "openapi.its.ulsan.kr",
        )

        /** 앱 설정의 서버 (local.properties 의 TAGO_PROXY_URL·TAGO_PROXY_TOKEN). 없으면 null */
        val current: DataProxy? by lazy { of(com.eunho.tagohub.BuildConfig.TAGO_PROXY_URL, com.eunho.tagohub.BuildConfig.TAGO_PROXY_TOKEN) }

        fun of(url: String?, token: String?): DataProxy? {
            val u = url?.trim().orEmpty()
            val t = token?.trim().orEmpty()
            return if (u.startsWith("https://") && t.isNotEmpty()) DataProxy(u, t) else null
        }
    }
}
