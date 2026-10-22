package com.eunho.tagohub.data

import android.content.Context

/**
 * 한국철도공사 열차시간표 (assets/train_timetable.tsv: KTX·일반열차 시간표를 미리 풀어 둔 것).
 * TAGO 로는 "열차별 정차역"을 알 수 없어서 수백 번 조회해야 했는데, 시간표 파일에는 열차마다 서는 역이 다 있어서
 * 정차역은 인터넷 없이 바로 찾고, 환승도 이어지는 역만 골라 조회한다.
 *
 * 한 줄 = 열차 한 편: 번호 \t 종별 \t 운행요일 \t 역@분;역@분... (분 = 0시부터의 분, 자정을 넘으면 1440 이상)
 * 시각은 중간 역은 출발, 종착역은 도착 기준이다.
 */
object TrainTimetable {
    data class Train(val no: String, val kind: String, val remark: String, val stops: List<Pair<String, Int>>) {
        /** 정규화한 역 이름 */
        val keys: List<String> = stops.map { norm(it.first) }
    }

    @Volatile private var byNo: Map<String, List<Train>> = emptyMap()
    @Volatile private var trains: List<Train> = emptyList()

    fun init(context: Context) {
        if (trains.isNotEmpty()) return
        val text = runCatching { context.assets.open("train_timetable.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
        set(parse(text))
    }

    internal fun set(list: List<Train>) {
        trains = list
        byNo = list.groupBy { it.no }
    }

    val isLoaded: Boolean get() = trains.isNotEmpty()

    /** 역 이름 비교용: 괄호 안 설명과 공백을 뺌 ("울산(통도사)" = "울산") */
    fun norm(name: String): String = name.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\s+"), "").let {
        // 시간표 이름과 TAGO 이름이 다른 몇 곳
        when (it) {
            "여수엑스포", "여수EXPO" -> "여수EXPO"
            "평택지제" -> "지제"
            else -> it
        }
    }

    fun parse(text: String): List<Train> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0].isBlank()) return@mapNotNull null
        val stops = f[3].split(';').mapNotNull { s ->
            val name = s.substringBeforeLast('@', "")
            val minute = s.substringAfterLast('@', "").toIntOrNull()
            if (name.isBlank() || minute == null) null else name to minute
        }
        if (stops.size < 2) null else Train(f[0].trim(), f[1].trim(), f[2].trim(), stops)
    }.toList()

    /**
     * [trip] 열차가 출발역→도착역 사이에 서는 역과 시각. 시간표에 없으면 null.
     * 같은 번호가 요일마다 따로 있을 수 있어서 출발역 시각이 같은 것을 우선 고른다.
     */
    fun stopsFor(trip: Trip): List<StopTime>? {
        val number = trip.number ?: return null
        val dep = trip.dep ?: return null
        val depKey = norm(trip.depName)
        val arrKey = norm(trip.arrName)
        val depMinuteOfDay = dep.hour * 60 + dep.minute
        val candidates = byNo[number].orEmpty().mapNotNull { t ->
            val i = t.keys.indexOf(depKey)
            val j = t.keys.indexOf(arrKey)
            if (i < 0 || j <= i) null else Triple(t, i, j)
        }
        val best = candidates.firstOrNull { (t, i, _) -> t.stops[i].second % 1440 == depMinuteOfDay }
            ?: candidates.firstOrNull() ?: return null
        val (t, i, j) = best
        val base = t.stops[i].second
        return (i..j).map { k ->
            val name = if (k == i) trip.depName else if (k == j) trip.arrName else t.stops[k].first
            StopTime(name, dep.plusMinutes((t.stops[k].second - base).toLong()))
        }
    }

    /** 시간표에 이 역이 나오는지 */
    fun knows(name: String): Boolean = norm(name).let { key -> trains.any { key in it.keys } }

    /**
     * [dep]→[arr] 을 한 번 갈아타서 갈 수 있는 역(환승역) 후보, 많이 이어지는 순으로 [limit] 곳.
     * 출발역이나 도착역이 시간표에 없으면 null (그때는 호출하는 쪽이 정해 둔 환승역을 씀), 이어지는 곳이 없으면 빈 목록.
     */
    fun hubsBetween(dep: String, arr: String, limit: Int = 12): List<String>? {
        val d = norm(dep)
        val a = norm(arr)
        if (!knows(dep) || !knows(arr)) return null
        val after = HashMap<String, Int>()    // 출발역 다음에 서는 역 → 그런 열차 수
        val before = HashMap<String, Int>()   // 도착역 앞에 서는 역 → 그런 열차 수
        val names = HashMap<String, String>()
        for (t in trains) {
            val i = t.keys.indexOf(d)
            if (i >= 0) for (k in i + 1 until t.keys.size) { after.merge(t.keys[k], 1, Int::plus); names.putIfAbsent(t.keys[k], t.stops[k].first) }
            val j = t.keys.indexOf(a)
            if (j >= 0) for (k in 0 until j) { before.merge(t.keys[k], 1, Int::plus); names.putIfAbsent(t.keys[k], t.stops[k].first) }
        }
        return after.keys.filter { it in before && it != d && it != a }
            .sortedWith(compareByDescending<String> { minOf(after.getValue(it), before.getValue(it)) }.thenBy { it })
            .take(limit).map { names.getValue(it) }
    }
}
