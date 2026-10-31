package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 야간 주행 모드: 달리는 중(길 안내·자유주행)에 지도를 어둡게 덮어 눈부심을 줄임.
 * 카카오맵에는 야간 지도가 없어서 지도 위에 어두운 막을 덮는 방식이다. 설정에서 끔 / 밤에만(자동) / 항상 중에서 고르며, **기본은 끔**.
 * 위성 지도는 원래 어두워서(글자·도로가 안 보임) 막을 덮지 않는다.
 */
enum class NightMode(val label: String, val code: String) {
    Off("끔", "off"),
    Auto("밤에만", "auto"),
    Always("항상", "always");

    companion object {
        fun of(code: String?): NightMode = entries.firstOrNull { it.code == code } ?: Off

        /** 밤으로 보는 시각: 저녁 [NIGHT_FROM] 시부터 아침 [NIGHT_TO] 시 전까지 */
        const val NIGHT_FROM = 19
        const val NIGHT_TO = 6

        fun isNightHour(hour: Int): Boolean = hour >= NIGHT_FROM || hour < NIGHT_TO

        /** 지금 지도를 어둡게 할지: 달리는 중이고 위성 지도가 아니어야 하며, 설정이 항상이거나 밤에만이면서 지금이 밤일 때 */
        fun dimNow(mode: NightMode, driving: Boolean, hour: Int, satellite: Boolean = false): Boolean =
            driving && !satellite && when (mode) {
                Off -> false
                Always -> true
                Auto -> isNightHour(hour)
            }
    }
}

/** 고른 야간 주행 모드 (기기에 저장, 로그인하면 계정에 함께 저장). 처음 값은 "끔" */
object NightSettings {
    private const val PREFS = "ui"
    const val KEY = "night_mode"

    /** 지도를 덮는 어두운 막의 짙은 정도 (0~1) */
    const val DIM_ALPHA = 0.45f

    private val _mode = MutableStateFlow(NightMode.Off)
    val mode: StateFlow<NightMode> = _mode

    fun load(context: Context) {
        _mode.value = NightMode.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun set(context: Context, m: NightMode) {
        _mode.value = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.code).apply()
    }
}
