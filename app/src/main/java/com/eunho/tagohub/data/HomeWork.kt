package com.eunho.tagohub.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 자주 가는 곳 두 가지: 집과 회사 (출퇴근 한 번 길찾기용) */
enum class SavedKind(val label: String) { Home("집"), Work("회사") }

data class SavedPlace(val name: String, val lat: Double, val lng: Double)

data class HomeWork(val home: SavedPlace? = null, val work: SavedPlace? = null) {
    operator fun get(kind: SavedKind): SavedPlace? = if (kind == SavedKind.Home) home else work

    fun with(kind: SavedKind, place: SavedPlace?): HomeWork =
        if (kind == SavedKind.Home) copy(home = place) else copy(work = place)

    companion object {
        /**
         * 지금 시각에 어디로 갈 가능성이 큰지: 새벽 4시~낮 1시 59분은 회사(출근), 그 밖은 집(퇴근).
         * 칩을 이 순서로 보여줘서 한 번에 누를 수 있게 한다.
         */
        fun suggested(hourOfDay: Int): SavedKind = if (hourOfDay in 4..13) SavedKind.Work else SavedKind.Home
    }
}

/** 저장 형식: "이름|위도|경도" (이름 안의 \ | 는 이스케이프) */
object SavedPlaceCodec {
    fun encode(p: SavedPlace?): String =
        if (p == null) "" else "${p.name.replace("\\", "\\\\").replace("|", "\\p")}|${p.lat}|${p.lng}"

    fun decode(text: String?): SavedPlace? {
        val parts = text.orEmpty().split('|')
        if (parts.size != 3) return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lng = parts[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val name = buildString {
            var i = 0
            val s = parts[0]
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    append(if (s[i + 1] == 'p') '|' else s[i + 1])
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
        return SavedPlace(name, lat, lng)
    }
}

/** 휴대폰 안에 저장 (로그인 없이). 즐겨찾기와 같은 설정 파일을 씀 */
class HomeWorkStore(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(
        HomeWork(SavedPlaceCodec.decode(prefs.getString(KEY_HOME, null)), SavedPlaceCodec.decode(prefs.getString(KEY_WORK, null))),
    )
    val state: StateFlow<HomeWork> = _state

    fun set(kind: SavedKind, place: SavedPlace?) {
        _state.value = _state.value.with(kind, place)
        prefs.edit().putString(if (kind == SavedKind.Home) KEY_HOME else KEY_WORK, SavedPlaceCodec.encode(place)).apply()
    }

    private companion object {
        const val KEY_HOME = "saved_home"
        const val KEY_WORK = "saved_work"
    }
}
