package com.eunho.tagohub.data

/**
 * 앱 안 지하철 역 목록·시간표 (assets/subway_schedule.tsv, pipeline/build_subway_schedule.py 가 TAGO 지하철 API 로 만듦).
 * 파일에 있는 역·요일·방향은 API 대신 이것을, 없으면 null → 예전처럼 API.
 *   S 역id 역이름 노선이름 노선id
 *   T 역id 요일 방향 종착역(id~이름|…) 출발(36진수 분 + 종착역 글자, 대문자 = 종착만)
 */
object SubwayScheduleFile {
    private class Data(val stations: List<SubwayStation>, val tables: Map<String, String>, val stamp: String = "")

    @Volatile private var data: Data? = null
    /** 테스트용: 파일 글자를 바로 넣기 (바꾸면 읽어 둔 것을 비움) */
    @Volatile var source: () -> String? = { AssetText.read(NAME) }
        set(v) { field = v; data = null; stampOf = { "" } }
    /** 지금 파일의 판 (노선 DB 를 새로 받으면 바뀜 → 다시 읽음) */
    @Volatile private var stampOf: () -> String = { AssetText.stamp(NAME) }
    private const val NAME = "subway_schedule.tsv"

    private fun load(): Data? {
        val now = stampOf()
        data?.takeIf { it.stamp == now }?.let { return it }
        return synchronized(this) {
            data?.takeIf { it.stamp == now } ?: source()?.let { parse(it, now) }?.takeIf { it.stations.isNotEmpty() }?.also { data = it } ?: data
        }
    }

    private fun parse(text: String, stamp: String): Data {
        val st = ArrayList<SubwayStation>()
        val tb = HashMap<String, String>()
        for (l in text.lineSequence()) {
            val p = l.trimEnd('\r').split('\t')   // Windows 에서 받은 파일은 줄 끝에 \r
            when (p[0]) {
                "S" -> if (p.size >= 5) st += SubwayStation(id = p[1], name = p[2], routeName = p[3], routeId = p[4])
                "T" -> if (p.size >= 6) tb["${p[1]}_${p[2]}_${p[3]}"] = p[4] + "\t" + p[5]
            }
        }
        return Data(st, tb, stamp)
    }

    /** 역 이름에 [q] 가 들어간 역 (API 낱말 검색과 같게). 파일이 없거나 하나도 없으면 null */
    fun stations(q: String): List<SubwayStation>? {
        val k = q.trim()
        if (k.isEmpty()) return null
        return load()?.stations?.filter { it.name.contains(k) }?.takeIf { it.isNotEmpty() }
    }

    /** 역·요일(01·02·03)·방향(U·D) 시간표 (파일에 없으면 null, 운행이 없으면 빈 목록) */
    fun schedule(stationId: String, day: String, upDown: String): List<SubwayDeparture>? {
        val row = load()?.tables?.get("${stationId}_${day}_$upDown") ?: return null
        val ends = row.substringBefore('\t').split('|').filter { it.isNotEmpty() }.map { it.substringBefore('~') to it.substringAfter('~', "") }
        return row.substringAfter('\t').split(' ').mapNotNull { e ->
            if (e.length < 2) return@mapNotNull null
            val c = e.last()
            val end = ends.getOrNull(c.lowercaseChar() - 'a') ?: return@mapNotNull null
            val min = e.dropLast(1).toIntOrNull(36) ?: return@mapNotNull null
            SubwayDeparture(serviceMinute = min, endStationId = end.first, endStationName = end.second, arrivalOnly = c.isUpperCase())
        }
    }
}
