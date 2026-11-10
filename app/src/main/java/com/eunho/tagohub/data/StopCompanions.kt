package com.eunho.tagohub.data

import android.content.Context

/**
 * 같은 자리에 여러 시·군 정류장이 겹치는 곳의 표 (assets/stop_companions.tsv).
 *
 * `pipeline/build_stop_companions.js` 가 `bus_stops.db` 의 좌표로 30m 규칙을 미리 계산해 만들어 두므로,
 * 정류장을 열 때 DB 를 훑거나 TAGO 주변 정류장 API 를 부르지 않고 바로 타 시·군을 함께 보여 줄 수 있다.
 * (전에는 첫 화면을 한 시·군으로 그리고 나머지를 네트워크로 찾아 붙여서, 진안을 누르면 전주가 늦게 떴다.)
 *
 * 표는 정류장 좌표가 바뀔 때만 다시 만들면 된다.
 */
object StopCompanions {
    private var appContext: Context? = null
    @Volatile private var loaded = false
    private var map: Map<String, List<BusStop>> = emptyMap()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = appContext?.let { c ->
                runCatching { c.assets.open("stop_companions.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
            }
            if (text != null) map = parse(text)
            loaded = true
        }
    }

    /** 이 정류장과 같은 자리에 서는 다른 시·군 정류장 (없으면 빈 목록) */
    fun of(nodeId: String): List<BusStop> {
        ensure()
        return map[nodeId].orEmpty()
    }

    internal fun set(m: Map<String, List<BusStop>>) {
        synchronized(this) {
            map = m
            loaded = true
        }
    }

    /** "정류장ID \t id|시·군코드|번호|이름|위도|경도;…" 줄들 */
    fun parse(text: String): Map<String, List<BusStop>> {
        val out = HashMap<String, List<BusStop>>()
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val id = line.substring(0, tab).trim()
            if (id.isEmpty()) return@forEach
            val list = line.substring(tab + 1).split(';').mapNotNull { enc ->
                val f = enc.split('|')
                if (f.size < 6) return@mapNotNull null
                val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
                val lng = f[5].toDoubleOrNull() ?: return@mapNotNull null
                BusStop(f[1], f[0], f[3], f[2].ifBlank { null }, lat, lng)
            }
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }
}
