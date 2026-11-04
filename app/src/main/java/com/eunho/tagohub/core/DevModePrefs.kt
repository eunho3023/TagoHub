package com.eunho.tagohub.core

import android.content.Context
import com.eunho.tagohub.BuildConfig

// 폰에서 개발자 모드 켜고 끄기 ([DevMode] 는 공용 코드). 디버그 빌드는 항상 켜짐. 릴리즈는 설정 화면의 버전을 7번 누르면 켜짐(다시 7번 누르면 꺼짐).
private const val PREFS = "dev_mode"
private const val KEY = "on"

fun DevMode.init(context: Context) {
    on = BuildConfig.DEBUG || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
}

/** 릴리즈 빌드에서 개발자 모드를 켜고 끔. 바뀐 값을 돌려줌 (디버그 빌드는 늘 켜짐) */
fun DevMode.toggle(context: Context): Boolean {
    val next = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, next).apply()
    on = BuildConfig.DEBUG || next
    return on
}
