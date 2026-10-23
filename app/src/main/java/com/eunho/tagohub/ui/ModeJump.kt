package com.eunho.tagohub.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 지도에서 기차역·터미널·공항을 누르고 '출발/도착'을 고르면 → 그 탭으로 가서 출발지·도착지를 채움.
 * (탭마다 ViewModel 이 따로라 이 객체로 전달)
 */
object ModeJump {
    enum class Kind(val label: String) {
        Train("기차"),
        Coach("고속·시외"),
        Air("항공"),
        Road("도로교통"),
    }

    data class Jump(val kind: Kind, val name: String, val asOrigin: Boolean)

    val request = MutableStateFlow<Jump?>(null)

    /** 지도 오른쪽 버튼으로 기차·고속시외·항공 패널 열기 */
    val open = MutableStateFlow<Kind?>(null)

    /** 왼쪽 메뉴 열기 (검색창의 ☰) */
    val drawer = MutableStateFlow(false)

    private val AIRPORT = Regex("""공항(\s*(제?\d\s*)?(여객\s*)?터미널|\s*(국내선|국제선).*)?$""")
    private val NOT_A_TERMINAL = Regex("화물|물류|택배|철도|지하철|초등|중학|고등|대학|병원|약국|주차|카페|식당")

    /**
     * 지도 POI 이름으로 종류 판별 (지하철역·버스정류장은 먼저 걸러진 뒤).
     * 이름 "끝"이 공항·터미널·역일 때만: 예전에는 이름에 들어 있기만 해도 맞다고 해서
     * "서울역 맛집"·"공항초등학교"·"터미널 약국" 같은 장소가 기차·항공·버스 화면으로 잘못 연결됐다.
     */
    fun kindOf(name: String): Kind? {
        val n = name.replace(Regex("""\s*\(.*?\)\s*$"""), "").trim()   // 끝의 "(서울)" 같은 덧붙임은 뗌
        return when {
            AIRPORT.containsMatchIn(n) && !NOT_A_TERMINAL.containsMatchIn(n) -> Kind.Air
            n.endsWith("터미널") && !NOT_A_TERMINAL.containsMatchIn(n) -> Kind.Coach
            n.endsWith("역") && !NOT_A_TERMINAL.containsMatchIn(n) -> Kind.Train
            else -> null
        }
    }
}
