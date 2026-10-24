package com.eunho.tagohub.ui.navi

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.FreeDrive
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.guidance.FreeDriveSession
import com.eunho.tagohub.ui.bus.hasLocationPermission

/**
 * 내비게이션 탭: (1) 목적지를 정해 경로 안내를 받거나, (2) 목적지 없이 달리면서(자유주행)
 * 속도·방향과 앞쪽 단속카메라·결빙·위험 구간·톨게이트 등을 미리 알려 받는다. 자유주행은 지도에서도 그대로 보인다.
 * 자유주행은 앱이 화면에 보이는 동안만 동작한다 (백그라운드 위치 권한을 쓰지 않으려고).
 */
@Composable
fun NavigationScreen(onPlanRoute: () -> Unit, onShowMap: () -> Unit, onRoadInfo: () -> Unit) {
    val context = LocalContext.current
    val running by FreeDriveSession.active.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) { denied = false; FreeDriveSession.start(context); onShowMap() } else denied = true
    }
    fun startFree() {
        if (hasLocationPermission(context)) { denied = false; FreeDriveSession.start(context); onShowMap() }
        else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Text("목적지 안내", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "목적지(와 경유지)를 정하면 길 안내, 단속카메라·결빙·위험 구간·톨게이트 알림, 음성 안내를 받아요.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Button(onClick = onPlanRoute, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("길찾기 시작") }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("자유주행", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "목적지 없이 달리면서 지도에서 내 위치와 방향을 보고, 앞쪽 단속카메라·결빙·위험 구간·톨게이트를 알려 받아요.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = running, onCheckedChange = { if (it) startFree() else FreeDriveSession.stop() })
            }
            if (denied) {
                Text("위치 권한이 있어야 자유주행을 쓸 수 있어요.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            if (running) {
                Spacer(Modifier.height(12.dp))
                FreeDriveSummary()
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도에서 보기") }
            }
        }
        OutlinedButton(onClick = onRoadInfo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("도로교통 (소통·예측·고속도로)") }
        OutlinedButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도 열기") }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 이 화면에서 보는 자유주행 요약: 속도·방향·앞쪽 알림. 켜져 있는 동안 화면이 꺼지지 않음 */
@Composable
private fun FreeDriveSummary() {
    val snap by FreeDriveSession.state.collectAsStateWithLifecycle()
    val voiceOn by FreeDriveSession.voiceOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val s = snap
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text("${s?.speedKmh ?: 0}", fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp)
            Spacer(Modifier.width(6.dp))
            Text("km/h", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val h = s?.heading
            Icon(
                Icons.Filled.Navigation,
                contentDescription = "진행 방향",
                tint = if (h != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(44.dp).rotate((h ?: 0.0).toFloat()),
            )
            Text(if (h != null) "${FreeDrive.compassWord(h)} ${h.toInt()}°" else "방향 확인 중", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("음성 안내", fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = voiceOn, onCheckedChange = { FreeDriveSession.voiceOn.value = it })
    }
    Spacer(Modifier.height(8.dp))
    val rows = buildList {
        s?.zones?.take(3)?.forEach { add(it.zone.kind.label + (it.zone.limitKmh?.takeIf { _ -> it.zone.kind.isCamera }?.let { l -> " · ${l}km/h" }.orEmpty()) to it.distance) }
        s?.hazards?.take(3)?.forEach { add(it.hazard.name to it.distance) }
    }.sortedBy { it.second }
    if (rows.isEmpty()) {
        Text(
            if (s == null) "위치를 찾는 중…" else "앞쪽에 알릴 곳이 없어요.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        rows.forEach { (label, meters) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(NavText.short(meters), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
