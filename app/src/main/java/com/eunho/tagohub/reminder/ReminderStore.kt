package com.eunho.tagohub.reminder

import android.content.SharedPreferences

/**
 * 막차·첫차 알림: 이 노선의 막차(또는 [first] 면 첫차) [lastMinutes] 분(기점 출발, 운행일 자정 기준, 24시 넘을 수 있음)에
 * 기점에서 [stopName] 정류장까지 걸리는 [offsetMin] 분을 더한 "내 정류장 도착 시각"의 [leadMin] 분 전에 알림.
 * 정류장을 모르면(노선 검색에서 켠 경우) [offsetMin] 0 · [stopName] 빈칸이라 기점 기준.
 * 노선마다 막차 하나, 첫차 하나씩 둘 수 있다.
 */
data class LastBusReminder(
    val routeId: String,
    val routeNo: String,
    val cityCode: String,
    val startName: String,
    val lastMinutes: Int,
    val leadMin: Int,
    val first: Boolean = false,
    val offsetMin: Int = 0,
    val stopName: String = "",
) {
    /** 내 정류장에 오는 시각(분, 운행일 자정 기준) */
    val stopMinutes: Int get() = lastMinutes + offsetMin
}

/** 출근 알림: [arriveByMin] (자정 기준 분) 까지 회사에 도착하려면 나갈 때 알려줌. 평일만 */
data class CommuteReminder(val enabled: Boolean, val arriveByMin: Int)

object ReminderCodec {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(
                    when (s[i + 1]) {
                        'p' -> '|'
                        'n' -> '\n'
                        else -> s[i + 1]
                    },
                )
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun encodeLastBus(list: List<LastBusReminder>): String =
        list.joinToString("\n") { r ->
            listOf(esc(r.routeId), esc(r.routeNo), esc(r.cityCode), esc(r.startName), r.lastMinutes, r.leadMin, if (r.first) "f" else "l", r.offsetMin, esc(r.stopName)).joinToString("|")
        }

    fun decodeLastBus(text: String?): List<LastBusReminder> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('|')
        // 7번째 칸 "f" 는 첫차, 8·9번째는 기점→내 정류장 분·정류장 이름 (예전에 저장한 알림은 6칸·7칸)
        if (p.size !in 6..9 || p.size == 8) return@mapNotNull null
        val last = p[4].toIntOrNull() ?: return@mapNotNull null
        val lead = p[5].toIntOrNull() ?: return@mapNotNull null
        LastBusReminder(
            com.eunho.tagohub.data.TagoIds.current(unesc(p[0])), unesc(p[1]), unesc(p[2]), unesc(p[3]), last, lead, first = p.getOrNull(6) == "f",
            offsetMin = p.getOrNull(7)?.toIntOrNull() ?: 0, stopName = p.getOrNull(8)?.let(::unesc).orEmpty(),
        )
    }

    fun encodeCommute(c: CommuteReminder?): String = if (c == null) "" else "${if (c.enabled) 1 else 0}|${c.arriveByMin}"

    fun decodeCommute(text: String?): CommuteReminder? {
        val p = text.orEmpty().split('|')
        if (p.size != 2) return null
        val at = p[1].toIntOrNull()?.takeIf { it in 0..(24 * 60 - 1) } ?: return null
        return CommuteReminder(p[0] == "1", at)
    }
}

/** 휴대폰 안에 저장 (로그인 없이) */
class ReminderStore(private val prefs: SharedPreferences) {
    fun lastBus(): List<LastBusReminder> = ReminderCodec.decodeLastBus(prefs.getString(KEY_LAST_BUS, null))

    fun lastBusFor(routeId: String, first: Boolean = false): LastBusReminder? = lastBus().firstOrNull { it.routeId == routeId && it.first == first }

    /** 같은 노선·같은 종류(막차/첫차)가 있으면 바꾸고 없으면 추가. [r] 이 null 이면 그 알림을 지움 */
    fun setLastBus(routeId: String, r: LastBusReminder?, first: Boolean = r?.first ?: false) {
        val next = lastBus().filterNot { it.routeId == routeId && it.first == first } + listOfNotNull(r)
        prefs.edit().putString(KEY_LAST_BUS, ReminderCodec.encodeLastBus(next)).apply()
    }

    fun commute(): CommuteReminder? = ReminderCodec.decodeCommute(prefs.getString(KEY_COMMUTE, null))

    fun setCommute(c: CommuteReminder?) {
        prefs.edit().putString(KEY_COMMUTE, ReminderCodec.encodeCommute(c)).apply()
    }

    private companion object {
        const val KEY_LAST_BUS = "last_bus"
        const val KEY_COMMUTE = "commute"
    }
}
