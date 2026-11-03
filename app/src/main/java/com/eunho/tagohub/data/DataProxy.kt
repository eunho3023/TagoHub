package com.eunho.tagohub.data

/**
 * 공공데이터 중계 서버 (server/tago-proxy, Cloudflare Workers) 설정.
 * 앱은 키 없이 `{주소}/dg/{기관번호}/...` 로 묻고 [TOKEN_HEADER] 에 토큰을 붙이면, 서버가 공공데이터포털 키를 붙여 묻고 잠시 저장해 나눠 준다.
 * 주소나 토큰이 비어 있으면 [of] 가 null → 예전처럼 키로 직접 물음.
 */
data class DataProxy(val url: String, val token: String) {
    /** 이 기관(예: 1613000 = TAGO)의 Retrofit 기본 주소 */
    fun base(agency: String): String = "${url.trimEnd('/')}/dg/$agency/"

    companion object {
        const val TOKEN_HEADER = "X-TagoHub-Token"

        fun of(url: String?, token: String?): DataProxy? {
            val u = url?.trim().orEmpty()
            val t = token?.trim().orEmpty()
            return if (u.startsWith("https://") && t.isNotEmpty()) DataProxy(u, t) else null
        }
    }
}
