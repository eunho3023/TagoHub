package com.eunho.tagohub.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * 앱이 지금 화면에 보이는지·지도 화면이 보이는지. 주기적으로 새로 받는 반복문(도착정보 15초, 버스 위치 10초 …)이
 * 홈 화면 뒤나 앱을 내려놓은 동안에도 계속 호출하던 것을 멈추기 위한 신호다 (배터리·데이터·공공 API 하루 호출 한도 절약).
 * 반복문은 매번 호출하기 전에 [awaitForeground] 또는 [awaitMapVisible] 로 기다린다. 보이게 되면 곧바로 이어서 받는다.
 */
object AppState {
    /** 앱 화면이 시작된 상태(MainActivity onStart~onStop) */
    val foreground = MutableStateFlow(true)

    /** 지도 화면이 맨 앞에 보이는 상태 (홈·기차 같은 화면이 지도를 덮고 있으면 false) */
    val mapVisible = MutableStateFlow(false)

    /** 앱이 화면에 보일 때까지 기다림 (이미 보이면 바로 돌아옴) */
    suspend fun awaitForeground() {
        foreground.first { it }
    }

    /** 앱이 보이고 지도 화면이 맨 앞일 때까지 기다림 */
    suspend fun awaitMapVisible() {
        combine(foreground, mapVisible) { f, m -> f && m }.first { it }
    }
}
