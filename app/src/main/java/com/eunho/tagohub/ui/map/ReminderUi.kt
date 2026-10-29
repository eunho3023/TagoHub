package com.eunho.tagohub.ui.map

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.reminder.CommuteReminder
import com.eunho.tagohub.reminder.LastBusReminder
import com.eunho.tagohub.reminder.ReminderLogic
import com.eunho.tagohub.reminder.ReminderScheduler
import com.eunho.tagohub.ui.theme.ModeColor

/** 안드로이드 13+ 에서 알림 권한이 없으면 요청 (거부해도 예약은 하지만 알림이 보이지 않음) */
@Composable
private fun rememberNotificationAsk(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { then ->
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = then
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else then()
    }
}

/**
 * 막차 알림 칩: "막차 15분 전 / 30분 전 / 60분 전". 켜 둔 칩을 다시 누르면 끔.
 * 막차 시각은 노선의 기점 출발 기준이라 중간 정류장은 더 늦을 수 있어서 알림 문구에 그렇게 적는다.
 */
@Composable
internal fun LastBusChips(routeId: String, routeNo: String, cityCode: String, startName: String?, lastTimeRaw: String?, modifier: Modifier = Modifier) {
    val last = TagoTime.minutesOfDay(lastTimeRaw) ?: return
    val context = LocalContext.current
    val store = remember { ReminderScheduler.store(context) }
    var current by remember(routeId) { mutableStateOf(store.lastBusFor(routeId)) }
    val ask = rememberNotificationAsk()
    Row(modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("막차 알림 · ${ReminderLogic.clock(last)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(15, 30, 60).forEach { lead ->
            FilterChip(
                selected = current?.leadMin == lead,
                onClick = {
                    if (current?.leadMin == lead) {
                        store.setLastBus(routeId, null)
                        ReminderScheduler.cancelLastBus(context, routeId)
                        current = null
                    } else {
                        val r = LastBusReminder(routeId, routeNo, cityCode, startName.orEmpty(), last, lead)
                        ask {
                            store.setLastBus(routeId, r)
                            ReminderScheduler.scheduleLastBus(context, r)
                            current = r
                        }
                    }
                },
                label = { Text("${lead}분 전") },
            )
        }
    }
}

/** 출근 알림 칩 (집과 회사를 둘 다 저장했을 때만): 누르면 "몇 시까지 도착"을 고르고, 평일에 나갈 때를 알려줌 */
@Composable
internal fun CommuteReminderChip() {
    val context = LocalContext.current
    val store = remember { ReminderScheduler.store(context) }
    var current by remember { mutableStateOf(store.commute()) }
    val ask = rememberNotificationAsk()
    fun pick() {
        val start = current?.arriveByMin ?: (9 * 60)
        val dialog = TimePickerDialog(
            context,
            { _, h, m ->
                val c = CommuteReminder(true, h * 60 + m)
                ask {
                    store.setCommute(c)
                    ReminderScheduler.scheduleCommute(context, c)
                    current = c
                }
            },
            start / 60, start % 60, true,
        )
        dialog.setTitle("몇 시까지 회사에 도착해야 하나요?")
        if (current?.enabled == true) {
            dialog.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "끄기") { _, _ ->
                val off = CommuteReminder(false, current?.arriveByMin ?: start)
                store.setCommute(off)
                ReminderScheduler.cancelCommute(context)
                current = off
            }
        }
        dialog.show()
    }
    val on = current?.enabled == true
    Surface(
        onClick = ::pick,
        shape = RoundedCornerShape(50),
        color = ModeColor.Bus.copy(alpha = if (on) 0.18f else 0.08f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = ModeColor.Bus, modifier = Modifier.size(16.dp))
            Text(
                "  " + if (on) "출근 ${ReminderLogic.clock(current!!.arriveByMin)} 도착" else "출근 알림",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ModeColor.Bus,
            )
        }
    }
}
