package com.eunho.tagohub.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eunho.tagohub.MainActivity
import com.eunho.tagohub.R
import com.eunho.tagohub.data.HomeWorkStore
import com.eunho.tagohub.data.KakaoRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** 알람이 울렸을 때: 막차 알림을 띄우고 다음 날 것을 예약, 출근 알림은 소요시간을 확인해 나갈 때를 알려줌 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ReminderScheduler.ACTION_LAST_BUS -> {
                val id = intent.getStringExtra(ReminderScheduler.EXTRA_ROUTE_ID) ?: return
                val r = ReminderScheduler.store(app).lastBusFor(id) ?: return
                notify(app, 5000 + (id.hashCode() and 0xFFF), "${r.routeNo}번 막차 알림", ReminderLogic.lastBusText(r.routeNo, r.leadMin, r.lastMinutes))
                ReminderScheduler.scheduleLastBus(app, r)
            }
            ReminderScheduler.ACTION_COMMUTE -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        commuteCheck(app)
                    } catch (e: Exception) {
                        Log.w(TAG, "출근 알림 확인 실패: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReminderScheduler.rescheduleAll(app)
        }
    }

    private suspend fun commuteCheck(context: Context) {
        val store = ReminderScheduler.store(context)
        val c = store.commute()?.takeIf { it.enabled } ?: return
        val now = ZonedDateTime.now()
        val weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend) { ReminderScheduler.scheduleCommute(context, c, now, sameDay = false); return }
        val hw = HomeWorkStore(context.getSharedPreferences("bus", Context.MODE_PRIVATE)).state.value
        val home = hw.home
        val work = hw.work
        if (home == null || work == null) return // 집이나 회사가 지워졌으면 다음에 다시 설정할 때까지 쉼
        val nowMin = now.hour * 60 + now.minute
        val (_, plans) = KakaoRoutes.transit(home.lat, home.lng, work.lat, work.lng)
        val travel = plans.minOfOrNull { it.minutes }
        if (travel == null) {
            // 길찾기 실패: 도착 시각 전이면 10분 뒤 다시, 아니면 다음 평일
            if (nowMin < c.arriveByMin) ReminderScheduler.scheduleCommuteAt(context, nowMin + 10, now) else ReminderScheduler.scheduleCommute(context, c, now, sameDay = false)
            return
        }
        when (val d = ReminderLogic.decide(nowMin, c.arriveByMin, travel)) {
            is ReminderLogic.Decision.CheckAt -> ReminderScheduler.scheduleCommuteAt(context, d.minuteOfDay, now)
            is ReminderLogic.Decision.Notify -> {
                notify(context, NOTIFY_COMMUTE, "출근 알림", ReminderLogic.departText(d.inMinutes, travel, c.arriveByMin))
                ReminderScheduler.scheduleCommute(context, c, now, sameDay = false)
            }
            is ReminderLogic.Decision.Late -> {
                notify(context, NOTIFY_COMMUTE, "출근 알림", ReminderLogic.lateText(d.minutesLate, travel, c.arriveByMin))
                ReminderScheduler.scheduleCommute(context, c, now, sameDay = false)
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "막차·출근 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "막차 시각과 출근 때 나갈 시각을 알려줍니다."
            })
        }
        val open = PendingIntent.getActivity(
            context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private companion object {
        const val TAG = "TagoReminder"
        const val CHANNEL = "reminders"
        const val NOTIFY_COMMUTE = 5100
    }
}
