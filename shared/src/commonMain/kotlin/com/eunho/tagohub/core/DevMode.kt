package com.eunho.tagohub.core

/**
 * 개발자 모드: 키 설정 안내·카카오 등록 안내·모의주행 같은 개발자용 문구와 기능을 보일지 (폰·웹·PC 공용).
 * 켜고 끄는 것은 각 플랫폼이 함 (폰: 디버그 빌드는 늘 켜짐, 설정 화면의 버전을 7번 누르면 켜짐 → DevModePrefs).
 */
object DevMode {
    /** 개발자 정보를 보일지 */
    @kotlin.concurrent.Volatile
    var on: Boolean = false

    /** 사용자에게 보이는 기본 안내: 개발자용 자세한 이유 대신 */
    const val UNAVAILABLE = "지금은 이 정보를 불러올 수 없어요. 잠시 뒤 다시 시도해 주세요."

    /** 개발자 모드면 [dev](자세한 이유·설정 방법), 아니면 [user] */
    fun msg(dev: String, user: String = UNAVAILABLE): String = if (on) dev else user
}
