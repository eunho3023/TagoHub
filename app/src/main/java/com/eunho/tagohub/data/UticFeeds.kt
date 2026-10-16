package com.eunho.tagohub.data

import okhttp3.HttpUrl.Companion.toHttpUrl

data class UticFeed(
    val title: String,
    val description: String,
    private val endpoint: String,
    private val keyParameter: String = "key",
    private val format: String? = null,
) {
    fun url(key: String): String = endpoint.toHttpUrl().newBuilder()
        .addQueryParameter(keyParameter, key)
        .apply { format?.let { addQueryParameter("type", it) } }
        .build()
        .toString()
}

object UticFeeds {
    val incidents = UticFeed("돌발정보", "도로 공사·사고 등 돌발 상황", "http://www.utic.go.kr/guide/imsOpenData.do")

    val all = listOf(
        UticFeed("소통정보", "UTIC 소통정보 데이터", "http://www.utic.go.kr/etc/telMap.do"),
        incidents,
        UticFeed("교통안전", "교통안전 데이터", "http://www.utic.go.kr/guide/tsdmsOpenData.do"),
        UticFeed("CCTV 데이터", "UTIC CCTV 데이터 원본", "http://www.utic.go.kr/guide/cctvOpenData.do"),
        UticFeed("도로위험상황예보", "도로 위험 상황 예보", "http://www.utic.go.kr/guide/getRoadAccJson.do"),
        UticFeed(
            "신호개방데이터",
            "교차로 신호 운영 정보 (JSON)",
            "http://tsihub.utic.go.kr/tsi/api/PlanCrossRoadInfoService/getPlanCRRSInfo",
            keyParameter = "serviceKey",
            format = "json",
        ),
        UticFeed("TCS 공사정보", "TCS 도로 공사 정보", "http://www.utic.go.kr/guide/tcsOpenData.do"),
    )
}
