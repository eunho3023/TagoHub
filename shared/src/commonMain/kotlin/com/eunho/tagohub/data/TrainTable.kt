package com.eunho.tagohub.data

/**
 * 한국철도공사 열차 시간표 파일(train_timetable.tsv: 번호, 종류, 운행요일, "역@분;역@분…") 계산 (폰·웹·PC 공용).
 * 파일을 읽는 것은 플랫폼이 하고(폰: 앱 안 파일, 웹·PC: 서버 data/files), 여기는 해석·찾기만.
 */
object TrainTable {
    data class Train(val no: String, val kind: String, val remark: String, val stops: List<Pair<String, Int>>) {
        /** 역 이름(맞춘 것) 차례 */
        val keys: List<String> = stops.map { norm(it.first) }
    }

    /** 출발역 → 도착역 한 편 (하루 중 분, 자정 넘으면 1440 넘음) */
    data class Leg(val no: String, val kind: String, val depMin: Int, val arrMin: Int)

    /** 역 이름 맞추기: 괄호·공백 빼고, 시간표와 TAGO 이름이 다른 몇 곳 */
    fun norm(name: String): String = name.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\s+"), "").let {
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
            val minute = s.substringAfterLast('@', "").trim().toIntOrNull()
            if (name.isBlank() || minute == null) null else name to minute
        }
        if (stops.size < 2) null else Train(f[0].trim(), f[1].trim(), f[2].trim(), stops)
    }.toList()

    /** 운행요일 표기("실적평일"·"실적토"·"실적일"·"금토일"·"월화수" …)가 이 요일([isoDay]: 월 1 … 일 7)에 다니는 열차인지 */
    fun runsOn(remark: String, isoDay: Int): Boolean {
        val weekend = isoDay >= 6
        val r = remark.removePrefix("실적")
        return when {
            r == "평일" -> !weekend
            r == "토" -> isoDay == 6
            r == "일" -> isoDay == 7
            r.isNotEmpty() && r.all { it in "월화수목금토일" } -> "월화수목금토일"[isoDay - 1] in r
            else -> false
        }
    }

    /** [dep] → [arr] 그날([isoDay]) 열차, 출발 시각순 (같은 번호·같은 시각은 한 번) */
    fun between(trains: List<Train>, dep: String, arr: String, isoDay: Int): List<Leg> {
        val dk = norm(dep)
        val ak = norm(arr)
        return trains.mapNotNull { t ->
            if (!runsOn(t.remark, isoDay)) return@mapNotNull null
            val i = t.keys.indexOf(dk)
            val j = t.keys.indexOf(ak)
            if (i < 0 || j <= i) null else Leg(t.no, t.kind, t.stops[i].second, t.stops[j].second)
        }.distinctBy { it.no to it.depMin }.sortedBy { it.depMin }
    }

    /** 시간표에 나오는 역 이름들 (맞춘 이름 → 처음 나온 그대로의 이름) */
    fun stationNames(trains: List<Train>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in trains) for ((n, _) in t.stops) out.getOrPut(norm(n)) { n.replace(Regex("\\(.*?\\)"), "").trim() }
        return out
    }

    /** 분 → "05:13" (자정 넘은 분도 시각으로) */
    fun clock(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }
}
