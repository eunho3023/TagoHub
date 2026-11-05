package com.eunho.tagohub.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 이 기기에만 남기는 작은 값 (웹은 브라우저 localStorage, PC 는 사용자 설정). 못 쓰면 null·무시 */
expect fun storeGet(key: String): String?
expect fun storeSet(key: String, value: String)

/** 화면 밝기: auto(기기 설정 따라) · light · dark */
object ThemePref {
    var mode by mutableStateOf(storeGet("theme")?.takeIf { it in setOf("auto", "light", "dark") } ?: "auto")
        private set

    fun next() {
        mode = when (mode) { "auto" -> "dark"; "dark" -> "light"; else -> "auto" }
        storeSet("theme", mode)
    }

    val label: String get() = when (mode) { "dark" -> "어둡게"; "light" -> "밝게"; else -> "화면 자동" }
}

/** 최근 본 버스 정류장·지하철역 (각 10개, 최근 것 먼저) */
object Recent {
    private const val MAX = 10
    var stops by mutableStateOf(load("recentStops").mapNotNull(::stopOf))
        private set
    var stations by mutableStateOf(load("recentStations").mapNotNull(::stationOf))
        private set

    private fun load(key: String) = storeGet(key).orEmpty().split('\n').filter { it.isNotBlank() }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun encodeStop(s: WebStop) = listOf(s.id, s.name, s.lat.toString(), s.lng.toString(), s.no.orEmpty(), s.city).joinToString("\t") { clean(it) }

    fun stopOf(line: String): WebStop? {
        val f = line.split('\t')
        if (f.size < 6) return null
        return WebStop(f[0], f[1], f[2].toDoubleOrNull() ?: return null, f[3].toDoubleOrNull() ?: return null, f[4].ifEmpty { null }, f[5], emptyList())
    }

    fun encodeStation(s: SubwayStationW) = listOf(s.id, s.name, s.line).joinToString("\t") { clean(it) }

    fun stationOf(line: String): SubwayStationW? {
        val f = line.split('\t')
        return if (f.size < 3) null else SubwayStationW(f[0], f[1], f[2])
    }

    fun addStop(s: WebStop) {
        stops = (listOf(s) + stops.filterNot { it.id == s.id }).take(MAX)
        storeSet("recentStops", stops.joinToString("\n", transform = ::encodeStop))
    }

    fun addStation(s: SubwayStationW) {
        stations = (listOf(s) + stations.filterNot { it.id == s.id }).take(MAX)
        storeSet("recentStations", stations.joinToString("\n", transform = ::encodeStation))
    }

    fun clearStops() { stops = emptyList(); storeSet("recentStops", "") }
    fun clearStations() { stations = emptyList(); storeSet("recentStations", "") }
}
