package com.eunho.tagohub.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 지도에서 기차역·터미널·공항을 누르고 '출발/도착'을 고르면 → 그 탭으로 가서 출발지·도착지를 채움.
 * (탭마다 ViewModel 이 따로라 이 객체로 전달)
 */
object ModeJump {
    enum class Kind(val label: String) { Train("기차"), Coach("고속·시외"), Air("항공"), Utic("UTIC 교통정보") }

    data class Jump(val kind: Kind, val name: String, val asOrigin: Boolean)

    val request = MutableStateFlow<Jump?>(null)

    /** 지도 오른쪽 버튼으로 기차·고속시외·항공 패널 열기 */
    val open = MutableStateFlow<Kind?>(null)

    /** 왼쪽 메뉴 열기 (검색창의 ☰) */
    val drawer = MutableStateFlow(false)

    /** 지도 POI 이름으로 종류 판별 (지하철역·버스정류장은 먼저 걸러진 뒤) */
    fun kindOf(name: String): Kind? {
        val n = name.trim()
        return when {
            n.contains("공항") -> Kind.Air
            n.contains("터미널") -> Kind.Coach
            n.endsWith("역") || n.contains("역 ") -> Kind.Train
            else -> null
        }
    }
}
