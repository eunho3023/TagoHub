package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import java.net.URLEncoder

/**
 * CCTV 영상 주소를 집 PC 중계 서버(tools/cctv_proxy.py)를 거치도록 바꾼다.
 * 공공 영상 서버는 http://IP:포트 로 와서 그대로 쓰면 IP 가 노출되고 https 가 아니다.
 * local.properties 의 CCTV_PROXY_URL·CCTV_PROXY_KEY 가 없으면 원래 주소 그대로 (기존 동작).
 */
object CctvProxy {
    fun wrap(
        streamUrl: String,
        proxyUrl: String = BuildConfig.CCTV_PROXY_URL,
        key: String = BuildConfig.CCTV_PROXY_KEY,
    ): String {
        val base = proxyUrl.trim().trimEnd('/')
        if (base.isEmpty() || key.isBlank()) return streamUrl
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
        return "$base/p?u=${enc(streamUrl)}&k=${enc(key.trim())}"
    }
}
