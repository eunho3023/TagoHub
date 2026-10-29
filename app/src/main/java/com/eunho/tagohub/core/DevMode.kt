package com.eunho.tagohub.core

import android.content.Context
import com.eunho.tagohub.BuildConfig

/**
 * 개발자 모드: 키 설정 안내·카카오 등록 안내·모의주행 같은 개발자용 문구와 기능을 보일지.
 * 디버그 빌드는 항상 켜짐. 배포용(릴리즈) 빌드는 꺼져 있고, 설정 화면의 버전을 7번 누르면 켜짐(다시 7번 누르면 꺼짐).
 */
object DevMode {
    private const val PREFS = "dev_mode"
    private const val KEY = "on"

    /** 개발자 정보를 보일지 */
    @Volatile
    var on: Boolean = BuildConfig.DEBUG
        private set

    /** 사용자에게 보이는 기본 안내: 개발자용 자세한 이유 대신 */
    const val UNAVAILABLE = "지금은 이 정보를 불러올 수 없어요. 잠시 뒤 다시 시도해 주세요."

    fun init(context: Context) {
        on = BuildConfig.DEBUG || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    /** 릴리즈 빌드에서 개발자 모드를 켜고 끔. 바뀐 값을 돌려줌 (디버그 빌드는 늘 켜짐) */
    fun toggle(context: Context): Boolean {
        val next = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, next).apply()
        on = BuildConfig.DEBUG || next
        return on
    }

    /** 개발자 모드면 [dev](자세한 이유·설정 방법), 아니면 [user] */
    fun msg(dev: String, user: String = UNAVAILABLE): String = if (on) dev else user
}
