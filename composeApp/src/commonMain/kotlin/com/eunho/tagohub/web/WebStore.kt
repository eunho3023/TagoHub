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

    /** 최근 길찾기 목적지 (10개) */
    var places by mutableStateOf(load("recentPlaces").mapNotNull(HomeWork::placeOf))
        private set

    fun addPlace(p: WebPlace) {
        places = (listOf(p) + places.filterNot { it.name == p.name && it.lat == p.lat && it.lng == p.lng }).take(MAX)
        storeSet("recentPlaces", places.joinToString("\n", transform = HomeWork::encode))
    }

    fun clearStops() { stops = emptyList(); storeSet("recentStops", "") }
    fun clearStations() { stations = emptyList(); storeSet("recentStations", "") }
}

/** 즐겨찾기 버스 정류장·지하철역 (이 기기에만, 넣은 순서대로) */
object Favorites {
    private const val MAX = 30
    var stops by mutableStateOf(load("favStops").mapNotNull(Recent::stopOf))
        private set
    var stations by mutableStateOf(load("favStations").mapNotNull(Recent::stationOf))
        private set

    private fun load(key: String) = storeGet(key).orEmpty().split('\n').filter { it.isNotBlank() }

    fun has(s: WebStop) = stops.any { it.id == s.id }
    fun has(s: SubwayStationW) = stations.any { it.id == s.id }

    fun toggle(s: WebStop) {
        stops = if (has(s)) stops.filterNot { it.id == s.id } else (stops + s.copy(routes = emptyList())).takeLast(MAX)
        storeSet("favStops", stops.joinToString("\n", transform = Recent::encodeStop))
    }

    fun toggle(s: SubwayStationW) {
        stations = if (has(s)) stations.filterNot { it.id == s.id } else (stations + s).takeLast(MAX)
        storeSet("favStations", stations.joinToString("\n", transform = Recent::encodeStation))
    }
}

/** 자주 가는 곳: 집·회사 (길찾기에서 한 번에 고름, 이 기기에만) */
object HomeWork {
    var home by mutableStateOf(storeGet("home")?.let(::placeOf))
        private set
    var work by mutableStateOf(storeGet("work")?.let(::placeOf))
        private set

    fun encode(p: WebPlace) = listOf(p.name, p.lat.toString(), p.lng.toString(), p.address.orEmpty()).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    fun placeOf(line: String): WebPlace? {
        val f = line.split('\t')
        if (f.size < 3) return null
        return WebPlace(f[0], f.getOrNull(3)?.ifEmpty { null }, f[1].toDoubleOrNull() ?: return null, f[2].toDoubleOrNull() ?: return null, null)
    }

    fun saveHome(p: WebPlace) { home = p; storeSet("home", encode(p)) }
    fun saveWork(p: WebPlace) { work = p; storeSet("work", encode(p)) }
}
