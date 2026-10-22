package com.eunho.tagohub.reminder

import android.content.SharedPreferences

/** 막차 알림: 이 노선의 막차 [lastMinutes] 분(운행일 자정 기준, 24시 넘을 수 있음) 의 [leadMin] 분 전에 알림 */
data class LastBusReminder(
    val routeId: String,
    val routeNo: String,
    val cityCode: String,
    val startName: String,
    val lastMinutes: Int,
    val leadMin: Int,
)

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
        list.joinToString("\n") { listOf(esc(it.routeId), esc(it.routeNo), esc(it.cityCode), esc(it.startName), it.lastMinutes, it.leadMin).joinToString("|") }

    fun decodeLastBus(text: String?): List<LastBusReminder> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 6) return@mapNotNull null
        val last = p[4].toIntOrNull() ?: return@mapNotNull null
        val lead = p[5].toIntOrNull() ?: return@mapNotNull null
        LastBusReminder(unesc(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]), last, lead)
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

    fun lastBusFor(routeId: String): LastBusReminder? = lastBus().firstOrNull { it.routeId == routeId }

    /** 같은 노선이 있으면 바꾸고 없으면 추가. [r] 이 null 이면 [routeId] 의 알림을 지움 */
    fun setLastBus(routeId: String, r: LastBusReminder?) {
        val next = lastBus().filterNot { it.routeId == routeId } + listOfNotNull(r)
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
