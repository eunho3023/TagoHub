package com.eunho.tagohub.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * 막차·출근 알림의 알람 예약. 정확한 알람 권한(스케줄 정확 알람)은 쓰지 않아서
 * 절전 상태에서는 몇 분 늦게 울릴 수 있다 — 그래서 알림 문구와 여유 시간에 이를 반영해 둠.
 */
object ReminderScheduler {
    const val ACTION_LAST_BUS = "com.eunho.tagohub.reminder.LAST_BUS"
    const val ACTION_COMMUTE = "com.eunho.tagohub.reminder.COMMUTE"
    const val EXTRA_ROUTE_ID = "route_id"
    private const val COMMUTE_CODE = 7001

    fun store(context: Context) = ReminderStore(context.applicationContext.getSharedPreferences("reminders", Context.MODE_PRIVATE))

    private fun alarms(context: Context) = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun intent(context: Context, action: String, routeId: String? = null): Intent =
        Intent(context.applicationContext, ReminderReceiver::class.java).setAction(action).apply {
            routeId?.let { putExtra(EXTRA_ROUTE_ID, it) }
        }

    private fun pending(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context.applicationContext, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun set(context: Context, code: Int, intent: Intent, at: ZonedDateTime) {
        runCatching {
            alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending(context, code, intent))
        }
    }

    private fun cancel(context: Context, code: Int, intent: Intent) {
        alarms(context).cancel(pending(context, code, intent))
    }

    private fun lastBusCode(routeId: String) = 8000 + (routeId.hashCode() and 0xFFFF)

    /** 막차 [r] 의 다음 알림 예약 (울린 뒤에도 같은 함수로 다음 날 것을 다시 예약) */
    fun scheduleLastBus(context: Context, r: LastBusReminder, now: ZonedDateTime = ZonedDateTime.now()) {
        val fire = ReminderLogic.nextFire(now, ReminderLogic.lastBusFireMinutes(r.lastMinutes, r.leadMin))
        set(context, lastBusCode(r.routeId), intent(context, ACTION_LAST_BUS, r.routeId), fire)
    }

    fun cancelLastBus(context: Context, routeId: String) = cancel(context, lastBusCode(routeId), intent(context, ACTION_LAST_BUS, routeId))

    /**
     * 출근 알림의 첫 확인을 예약. 도착 시각의 2시간 반 전(평일)이고,
     * 오늘 이미 그 시각이 지났지만 아직 도착 시각 전이면 곧바로(30초 뒤) 확인한다 ([sameDay] 가 true 일 때만:
     * 켠 직후에만 쓰고, 알림을 울린 뒤에는 false 로 다음 평일로 넘김).
     */
    fun scheduleCommute(context: Context, c: CommuteReminder, now: ZonedDateTime = ZonedDateTime.now(), sameDay: Boolean = true) {
        val first = ReminderLogic.firstCheckMinutes(c.arriveByMin)
        val todayFirst = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(first.toLong())
        val todayArrive = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(c.arriveByMin.toLong())
        val weekday = now.dayOfWeek != DayOfWeek.SATURDAY && now.dayOfWeek != DayOfWeek.SUNDAY
        val at = if (sameDay && weekday && now.isAfter(todayFirst) && now.isBefore(todayArrive.minusMinutes(10))) now.plusSeconds(30)
        else ReminderLogic.nextFire(now, first, weekdaysOnly = true)
        set(context, COMMUTE_CODE, intent(context, ACTION_COMMUTE), at)
    }

    /** [minuteOfDay](오늘 자정 기준)에 다음 확인 */
    fun scheduleCommuteAt(context: Context, minuteOfDay: Int, now: ZonedDateTime = ZonedDateTime.now()) {
        val at = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minuteOfDay.toLong())
        set(context, COMMUTE_CODE, intent(context, ACTION_COMMUTE), if (at.isAfter(now)) at else now.plusMinutes(1))
    }

    fun cancelCommute(context: Context) = cancel(context, COMMUTE_CODE, intent(context, ACTION_COMMUTE))

    /** 재부팅·앱 업데이트 뒤 저장된 알림을 모두 다시 예약 */
    fun rescheduleAll(context: Context) {
        val s = store(context)
        s.lastBus().forEach { scheduleLastBus(context, it) }
        s.commute()?.takeIf { it.enabled }?.let { scheduleCommute(context, it) }
    }
}
