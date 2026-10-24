package com.eunho.tagohub.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.FreeDrive
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.guidance.FreeDriveSession
import com.eunho.tagohub.ui.theme.BoardColor

/**
 * 자유주행(목적지 없이 달리기)을 지도에 띄워 보여 줌: 지도가 내 위치를 따라가고, 위에는 앞쪽 단속카메라·위험 구간 카드,
 * 왼쪽 아래에는 속도, 아래에는 방향과 종료 버튼. 경로 안내 중이 아닐 때만 그린다.
 */
@Composable
internal fun FreeDriveOverlay(vm: MapViewModel, modifier: Modifier = Modifier) {
    val active by FreeDriveSession.active.collectAsStateWithLifecycle()
    val guiding by com.eunho.tagohub.guidance.DriveGuidance.active.collectAsStateWithLifecycle()
    if (!active || guiding) return
    val snap by FreeDriveSession.state.collectAsStateWithLifecycle()
    val voiceOn by FreeDriveSession.voiceOn.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.following.value = true }
    // 위치가 바뀔 때마다 지도가 따라감 (손으로 움직이면 멈추고, 내 위치 버튼으로 다시 켬)
    LaunchedEffect(snap?.lat, snap?.lng, snap?.heading) {
        val s = snap ?: return@LaunchedEffect
        vm.followMe(s.lat, s.lng, s.speedKmh, s.heading)
    }

    Column(modifier.fillMaxSize()) {
        // 위: 앞쪽 알림
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val s = snap
            if (s == null) {
                InfoChip("위치를 찾는 중…")
            } else {
                s.zones.take(2).forEach { a -> AheadCard(a.zone.kind.label + (a.zone.limitKmh?.takeIf { a.zone.kind.isCamera }?.let { " · ${it}km/h" }.orEmpty()), a.distance, camera = a.zone.kind.isCamera) }
                s.hazards.take(2).forEach { h -> AheadCard(h.hazard.name, h.distance, camera = false) }
                if (s.heading == null) InfoChip("방향 확인 중 · 조금 움직이면 정확해져요")
            }
        }
        Spacer(Modifier.weight(1f))
        // 아래: 속도 + 방향 + 종료
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // 앞 단속카메라 제한속도가 있으면 그것, 없으면 지금 도로의 제한속도
            SpeedGauge(
                speedKmh = snap?.speedKmh ?: 0,
                limitKmh = snap?.zones?.firstOrNull { it.zone.kind.isCamera }?.zone?.limitKmh ?: snap?.roadLimitKmh,
                lanes = snap?.lanes ?: 0,
            )
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        snap?.heading?.let { "${FreeDrive.compassWord(it)}쪽 ${it.toInt()}°" } ?: "방향 --",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("음성", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = voiceOn, onCheckedChange = { FreeDriveSession.voiceOn.value = it })
                    }
                    Button(onClick = { FreeDriveSession.stop() }) { Text("자유주행 종료", maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AheadCard(label: String, meters: Double, camera: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (camera) BoardColor.AmberFill else MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = if (camera) BoardColor.OnAmber else MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "$label · ${NavText.short(meters)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (camera) BoardColor.OnAmber else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

