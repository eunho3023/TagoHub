package com.eunho.tagohub.data

import android.content.Context

/**
 * 역 편의시설: 엘리베이터·에스컬레이터·휠체어리프트·장애인경사로 (한국철도공사 승강설비 현황, 2019-12-20 기준).
 * assets/rail_facilities.tsv: 역 이름 \t 엘리베이터 \t 에스컬레이터 \t 휠체어리프트 \t 장애인경사로(Y/N)
 * 자료가 오래돼서 화면에도 기준 연도를 같이 보여준다. 파일에 없는 역은 아무것도 보여주지 않는다.
 */
object RailFacilities {
    const val AS_OF = "2019년 12월"

    data class Facility(val elevators: Int, val escalators: Int, val wheelchairLifts: Int, val ramp: Boolean)

    private var byName: Map<String, Facility> = emptyMap()
    private var appContext: Context? = null
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = appContext?.let { c -> runCatching { c.assets.open("rail_facilities.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }
            if (text != null) byName = parse(text)
            loaded = true
        }
    }

    internal fun set(map: Map<String, Facility>) {
        synchronized(this) {
            byName = map
            loaded = true
        }
    }

    fun parse(text: String): Map<String, Facility> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 5 || f[0].isBlank()) return@mapNotNull null
        TrainTimetable.norm(f[0]) to Facility(f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4].trim().equals("Y", ignoreCase = true))
    }.toMap()

    fun of(station: String): Facility? {
        ensure()
        return byName[TrainTimetable.norm(station)]
    }

    /** "엘리베이터 2 · 에스컬레이터 6" 같은 한 줄. 하나도 없거나 자료에 없으면 null */
    fun summary(station: String): String? {
        val f = of(station) ?: return null
        val parts = buildList {
            if (f.elevators > 0) add("엘리베이터 ${f.elevators}")
            if (f.escalators > 0) add("에스컬레이터 ${f.escalators}")
            if (f.wheelchairLifts > 0) add("휠체어리프트 ${f.wheelchairLifts}")
            if (f.ramp) add("장애인경사로")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
