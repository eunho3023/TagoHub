package com.eunho.tagohub.data

import android.content.Context

/**
 * 한국철도공사 열차시간표 (assets/train_timetable.tsv: KTX·일반열차 시간표를 미리 풀어 둔 것).
 * TAGO 로는 "열차별 정차역"을 알 수 없어서 수백 번 조회해야 했는데, 시간표 파일에는 열차마다 서는 역이 다 있어서
 * 정차역은 인터넷 없이 바로 찾고, 환승도 이어지는 역만 골라 조회한다.
 *
 * 한 줄 = 열차 한 편: 번호 \t 종별 \t 운행요일 \t 역@분;역@분... (분 = 0시부터의 분, 자정을 넘으면 1440 이상)
 * 시각은 중간 역은 출발, 종착역은 도착 기준이다.
 * 만든 곳: 한국철도공사 열차시간표(xlsx)와 여객열차 운행정보 API(B551457/run/v2, 평일·토·일 하루씩의 운행기록).
 */
object TrainTimetable {
    data class Train(val no: String, val kind: String, val remark: String, val stops: List<Pair<String, Int>>) {
        /** 정규화한 역 이름 */
        val keys: List<String> = stops.map { norm(it.first) }
    }

    /** 시간표와 조회한 열차의 출발 시각 차이가 이만큼(분) 넘으면 같은 열차로 보지 않음 */
    private const val MAX_GAP_MIN = 60

    private var byNo: Map<String, List<Train>> = emptyMap()
    private var trains: List<Train> = emptyList()
    private var appContext: Context? = null
    @Volatile private var loaded = false

    /** 앱 시작 때는 컨텍스트만 기억하고, 시간표는 처음 쓸 때 읽음 (앱 여는 시간을 늘리지 않으려고) */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = appContext?.let { c -> runCatching { c.assets.open("train_timetable.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() }
            if (text != null) apply(parse(text))
            loaded = true
        }
    }

    private fun apply(list: List<Train>) {
        trains = list
        byNo = list.groupBy { it.no }
    }

    internal fun set(list: List<Train>) {
        synchronized(this) {
            apply(list)
            loaded = true
        }
    }

    val isLoaded: Boolean get() { ensure(); return trains.isNotEmpty() }

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
        ensure()
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
        // 계획(국토교통부)과 실제 운행기록은 1~2분 다를 수 있어서 출발역 시각이 가장 가까운 편을 고르고, 너무 다르면 다른 열차로 봄
        fun gap(minute: Int): Int = Math.abs(minute % 1440 - depMinuteOfDay).let { minOf(it, 1440 - it) }
        val best = candidates.minByOrNull { (t, i, _) -> gap(t.stops[i].second) }?.takeIf { (t, i, _) -> gap(t.stops[i].second) <= MAX_GAP_MIN } ?: return null
        val (t, i, j) = best
        val base = t.stops[i].second
        return (i..j).map { k ->
            val name = if (k == i) trip.depName else if (k == j) trip.arrName else t.stops[k].first
            StopTime(name, dep.plusMinutes((t.stops[k].second - base).toLong()))
        }
    }

    /** 운행요일 표기("실적평일"·"실적토"·"실적일"·"금토일"·"월화수" …)가 이 날짜에 다니는 열차인지 */
    fun runsOn(remark: String, date: java.time.LocalDate): Boolean {
        val dow = date.dayOfWeek
        val weekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
        val r = remark.removePrefix("실적")
        return when {
            r == "평일" -> !weekend
            r == "토" -> dow == java.time.DayOfWeek.SATURDAY
            r == "일" -> dow == java.time.DayOfWeek.SUNDAY
            r.isNotEmpty() && r.all { it in "월화수목금토일" } -> "월화수목금토일"[dow.value - 1] in r
            else -> false
        }
    }

    /**
     * 한국철도공사 시간표만으로 만든 [depName]→[arrName] 그날 열차 (출발 시각순). 국토교통부(TAGO) 조회가 비는 날(오늘 등)을 채우는 용도라
     * 요금·열차 종류는 모르고, 실제 운행기록 기준 시간표라 임시 증편·운휴는 반영되지 않는다.
     */
    fun tripsBetween(depName: String, arrName: String, date: java.time.LocalDate): List<Trip> {
        ensure()
        val dk = norm(depName)
        val ak = norm(arrName)
        val start = date.atStartOfDay()
        return trains.mapNotNull { t ->
            if (!runsOn(t.remark, date)) return@mapNotNull null
            val i = t.keys.indexOf(dk)
            val j = t.keys.indexOf(ak)
            if (i < 0 || j <= i) return@mapNotNull null
            Trip(
                kind = t.kind.ifBlank { TrainKinds.get(t.no) ?: TrainKinds.UNKNOWN }, number = t.no, depName = depName, arrName = arrName,
                dep = start.plusMinutes(t.stops[i].second.toLong()), arr = start.plusMinutes(t.stops[j].second.toLong()),
                prices = emptyList(),
            )
        }.distinctBy { it.number to it.dep }.sortedBy { it.dep }
    }

    /** 시간표에 이 역이 나오는지 */
    fun knows(name: String): Boolean = run { ensure(); norm(name) }.let { key -> trains.any { key in it.keys } }

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
