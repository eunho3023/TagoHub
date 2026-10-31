package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 지도에서 내 위치를 나타내는 아이콘. [Default] 는 파란 점(방향 부채꼴 포함), 나머지는 진행 방향을 보는 탈것 */
enum class VehicleIcon(val label: String, val code: String) {
    Default("기본", "default"),
    Sedan("세단", "sedan"),
    Truck("트럭", "truck"),
    Bus("버스", "bus"),
    Motorcycle("오토바이", "motorcycle"),
    Bicycle("자전거", "bicycle");

    /** 방향을 가진 탈것 아이콘인지 (기본 점은 아님) */
    val isVehicle: Boolean get() = this != Default

    companion object {
        /** 저장된 코드를 아이콘으로. 모르는 값은 기본 */
        fun of(code: String?): VehicleIcon = entries.firstOrNull { it.code == code } ?: Default
    }
}

/** 고른 내 위치 아이콘 (기기에 저장, 로그인하면 계정에 함께 저장) */
object VehicleSettings {
    private const val PREFS = "ui"
    const val KEY = "vehicle_icon"

    private val _selected = MutableStateFlow(VehicleIcon.Default)
    val selected: StateFlow<VehicleIcon> = _selected

    fun load(context: Context) {
        _selected.value = VehicleIcon.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun set(context: Context, v: VehicleIcon) {
        _selected.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v.code).apply()
    }
}
