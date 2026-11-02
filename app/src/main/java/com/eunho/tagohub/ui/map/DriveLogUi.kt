package com.eunho.tagohub.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.DriveLog
import com.eunho.tagohub.data.NavText

/** 속도 색: 느림(초록) → 보통(노랑) → 빠름(주황) → 아주 빠름(빨강) */
internal fun speedColor(kmh: Int): Color = when {
    kmh < 30 -> Color(0xFF2E9D58)
    kmh < 60 -> Color(0xFF8DBF2E)
    kmh < 80 -> Color(0xFFF2B01E)
    kmh < 100 -> Color(0xFFF07B1F)
    else -> Color(0xFFD7262E)
}

/** 자유주행·경로 안내가 끝나면 주행 기록(지나온 길의 속도, 단속카메라 앞 속도)을 띄움 */
@Composable
fun DriveLogHost() {
    val summary by DriveLog.last.collectAsStateWithLifecycle()
    val history by DriveLog.history.collectAsStateWithLifecycle()
    val s = summary ?: return
    AlertDialog(
        onDismissRequest = { DriveLog.last.value = null },
        confirmButton = { TextButton(onClick = { DriveLog.last.value = null }) { Text("닫기") } },
        // 저장된 주행이면 여기서 지울 수 있음
        dismissButton = {
            if (history.any { it.startMs == s.startMs }) {
                TextButton(onClick = { DriveLog.delete(s); DriveLog.last.value = null }) { Text("기록 지우기", color = MaterialTheme.colorScheme.error) }
            }
        },
        title = {
            Column {
                Text("${s.title} 기록", fontWeight = FontWeight.ExtraBold)
                Text(driveDate(s.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = { DriveLogBody(s) },
    )
}

@Composable
private fun DriveLogBody(s: DriveLog.Summary) {
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        val minutes = ((s.endMs - s.startMs) / 60_000L).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("거리", NavText.short(s.meters))
            Stat("시간", "${minutes}분")
            Stat("평균", "${s.avgKmh}km/h")
            Stat("최고", "${s.maxKmh}km/h")
        }
        Spacer(Modifier.height(12.dp))
        Text("지나온 길의 속도", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "색이 그 자리에서 달린 속도예요. 동그라미는 단속카메라(초록 지킴, 빨강 과속).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SpeedPath(s)
        SpeedLegend()
        Spacer(Modifier.height(12.dp))
        val cams = s.cameras
        Text(
            if (cams.isEmpty()) "단속카메라 부근을 지나지 않았어요."
            else if (s.speedingCount == 0) "단속카메라 ${cams.size}곳 모두 제한속도를 지켰어요."
            else "단속카메라 ${cams.size}곳 중 ${s.speedingCount}곳에서 제한속도를 넘었어요.",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (s.speedingCount > 0) Color(0xFFD7262E) else MaterialTheme.colorScheme.onSurface,
        )
        cams.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (c.speeding) Color(0xFFD7262E) else Color(0xFF2E9D58)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name.ifBlank { "단속카메라" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(
                        "제한 ${c.limitKmh}km/h · 통과 ${c.speedKmh}km/h · ${clock(c.atMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (c.speeding) "+${c.overBy} 과속" else "지킴",
                    fontWeight = FontWeight.Bold,
                    color = if (c.speeding) Color(0xFFD7262E) else Color(0xFF2E9D58),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "GPS 속도라 계기판·단속 장비와 몇 km/h 다를 수 있어요. 기록은 이 휴대폰에만 최근 ${DriveLog.KEEP}개까지 남아요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
    }
}

/** "11월 3일 (화) 08:12" */
internal fun driveDate(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).let {
        val dow = "월화수목금토일"[it.dayOfWeek.value - 1]
        "${it.monthValue}월 ${it.dayOfMonth}일 ($dow) %02d:%02d".format(it.hour, it.minute)
    }

private fun clock(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).let { "%02d:%02d".format(it.hour, it.minute) }

/** 지나온 길을 위에서 본 모양으로 그리고, 구간마다 그때 속도의 색을 칠함 (지도 없이, 북쪽이 위) */
@Composable
private fun SpeedPath(s: DriveLog.Summary) {
    val pts = s.points
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Canvas(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(bg)) {
        if (pts.size < 2) return@Canvas
        val lat0 = pts.minOf { it.lat }
        val lat1 = pts.maxOf { it.lat }
        val lng0 = pts.minOf { it.lng }
        val lng1 = pts.maxOf { it.lng }
        val kx = Math.cos(Math.toRadians((lat0 + lat1) / 2))
        val w = ((lng1 - lng0) * kx).coerceAtLeast(1e-6)
        val h = (lat1 - lat0).coerceAtLeast(1e-6)
        val pad = 16.dp.toPx()
        val scale = minOf((size.width - pad * 2) / w, (size.height - pad * 2) / h).toFloat()
        val ox = (size.width - (w * scale).toFloat()) / 2
        val oy = (size.height - (h * scale).toFloat()) / 2
        fun at(lat: Double, lng: Double) = Offset(ox + ((lng - lng0) * kx * scale).toFloat(), oy + ((lat1 - lat) * scale).toFloat())
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            drawLine(speedColor(b.kmh), at(a.lat, a.lng), at(b.lat, b.lng), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(Color(0xFF1F6FEB), 5.dp.toPx(), at(pts.first().lat, pts.first().lng))
        drawCircle(Color.Black, 5.dp.toPx(), at(pts.last().lat, pts.last().lng))
        s.cameras.forEach { c ->
            val p = at(c.lat, c.lng)
            drawCircle(Color.White, 7.dp.toPx(), p)
            drawCircle(if (c.speeding) Color(0xFFD7262E) else Color(0xFF2E9D58), 5.dp.toPx(), p)
        }
    }
}

@Composable
private fun SpeedLegend() {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        listOf(0 to "~30", 30 to "~60", 60 to "~80", 80 to "~100", 100 to "100+").forEach { (k, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(speedColor(k)))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF1F6FEB)))
        Text(" 출발   ", style = MaterialTheme.typography.labelSmall)
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.Black))
        Text(" 끝 (km/h)", style = MaterialTheme.typography.labelSmall)
    }
}
