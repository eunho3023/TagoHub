package com.eunho.tagohub.data

/**
 * 즐겨찾기 (정류장·버스 노선). 휴대폰 안에 저장 — 로그인 없이.
 * (안드로이드 자동 백업이 켜져 있으면 구글 계정 백업으로 새 휴대폰에도 옮겨짐)
 */
data class Favorite(
    val kind: Kind,
    val id: String,
    /** 정류장 이름 / 노선 번호 */
    val name: String,
    /** 정류장 번호 / "기점 ↔ 종점" */
    val sub: String?,
    val cityCode: String,
    val lat: Double? = null,
    val lng: Double? = null,
    /** 정류장 번호(ARS) / 노선 종류 */
    val extra: String? = null,
) {
    enum class Kind { Stop, Route, Station }

    fun toStop() = BusStop(cityCode, id, name, extra, lat, lng)

    fun toRoute(): BusRoute {
        val ends = sub?.split(" ↔ ")
        return BusRoute(id, name, extra, ends?.getOrNull(0), ends?.getOrNull(1), null, null, null, null, null, cityCode)
    }

    companion object {
        fun of(s: BusStop) = Favorite(Kind.Stop, s.nodeId, s.name, s.nodeNo, s.cityCode, s.lat, s.lng, s.nodeNo)

        /** 지하철·기차역: id = 역 묶음 id, sub = 지나는 노선들("2호선·신분당선") */
        fun of(g: StationGroup) = Favorite(Kind.Station, g.id, g.name, g.displayLines.joinToString("·"), "", g.lat, g.lng)

        fun of(routeId: String, routeNo: String, routeType: String?, cityCode: String, start: String?, end: String?) =
            Favorite(Kind.Route, routeId, routeNo, if (start != null && end != null) "$start ↔ $end" else null, cityCode, extra = routeType)
    }
}

/** 저장 형식: 한 줄에 하나, 칸은 '|' (값 안의 \ | 줄바꿈은 이스케이프) */
object FavoriteCodec {
    private fun esc(s: String?) = s?.replace("\\", "\\\\")?.replace("|", "\\p")?.replace("\n", "\\n") ?: ""
    private fun unesc(s: String): String? {
        if (s.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> sb.append('|')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** '|' 로 나누되 이스케이프된 '\p' 는 나누지 않음 (값 안의 '|' 는 이미 \p 로 바뀌어 있음) */
    private fun split(line: String): List<String> = line.split('|')

    fun encode(list: List<Favorite>): String = list.joinToString("\n") { f ->
        listOf(f.kind.name, esc(f.id), esc(f.name), esc(f.sub), esc(f.cityCode), f.lat?.toString() ?: "", f.lng?.toString() ?: "", esc(f.extra))
            .joinToString("|")
    }

    fun decode(text: String?): List<Favorite> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = split(line)
        if (p.size < 8) return@mapNotNull null
        val kind = runCatching { Favorite.Kind.valueOf(p[0]) }.getOrNull() ?: return@mapNotNull null
        Favorite(kind, unesc(p[1]) ?: return@mapNotNull null, unesc(p[2]) ?: "", unesc(p[3]), unesc(p[4]) ?: "",
            p[5].toDoubleOrNull(), p[6].toDoubleOrNull(), unesc(p[7]))
    }

    /** 있으면 빼고 없으면 맨 앞에 넣기 */
    fun toggle(list: List<Favorite>, f: Favorite): List<Favorite> =
        if (list.any { it.kind == f.kind && it.id == f.id }) list.filterNot { it.kind == f.kind && it.id == f.id } else listOf(f) + list
}
