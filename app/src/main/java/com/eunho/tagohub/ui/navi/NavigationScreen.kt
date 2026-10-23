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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.eunho.tagohub.core.HeadingMath
import com.eunho.tagohub.data.FreeDrive
import com.eunho.tagohub.data.FreeDriveAnnouncer
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.SafetyZones
import com.eunho.tagohub.guidance.GuidanceVoice
import com.eunho.tagohub.ui.bus.hasLocationPermission
import com.eunho.tagohub.ui.bus.startLocationUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 내비게이션 탭: (1) 목적지를 정해 경로 안내를 받거나, (2) 목적지 없이 달리면서(자유주행)
 * 속도·방향과 앞쪽 단속카메라·어린이 보호구역을 미리 알려 받는다.
 * 자유주행은 이 화면이 켜져 있는 동안만 동작한다 (화면을 끄거나 다른 앱으로 가면 멈춤 — 백그라운드 위치 권한을 쓰지 않으려고).
 */
@Composable
fun NavigationScreen(onPlanRoute: () -> Unit, onShowMap: () -> Unit) {
    var freeDrive by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Text("목적지 안내", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "목적지(와 경유지)를 정하면 길 안내, 단속카메라·결빙구간·톨게이트 알림, 음성 안내를 받아요.",
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
                        "목적지 없이 달리면서 속도·방향, 앞쪽 단속카메라·어린이 보호구역을 알려 줘요.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = freeDrive, onCheckedChange = { freeDrive = it })
            }
            if (freeDrive) {
                Spacer(Modifier.height(12.dp))
                FreeDrivePanel()
            }
        }
        OutlinedButton(onClick = onShowMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("지도에서 보기") }
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

/** 자유주행 화면: 속도·방위·앞쪽 지점. 켜져 있는 동안 화면이 꺼지지 않음 */
@Composable
private fun FreeDrivePanel() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle = owner.lifecycle
    val scope = rememberCoroutineScope()
    val zones by produceState<List<SafetyZone>>(emptyList()) { value = SafetyZones.load(context) }
    val voice = remember { GuidanceVoice(context) }
    val announcer = remember { FreeDriveAnnouncer() }
    var voiceOn by remember { mutableStateOf(true) }
    var speedKmh by remember { mutableIntStateOf(0) }
    var heading by remember { mutableStateOf<Double?>(null) }
    var ahead by remember { mutableStateOf<List<FreeDrive.Ahead>>(emptyList()) }
    var granted by remember { mutableStateOf(hasLocationPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasLocationPermission(context) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; voice.shutdown() }
    }
    DisposableEffect(voiceOn) { voice.enabled = voiceOn; onDispose { } }

    // GPS 방향(움직일 때)과 나침반(서 있을 때)을 합쳐 방위를 정함
    val compass = remember { com.eunho.tagohub.core.HeadingSensor(context) { c -> if (gpsAge() > HeadingMath.GPS_MAX_AGE_MS) heading = c } }
    DisposableEffect(lifecycle, granted, zones) {
        if (!granted) return@DisposableEffect onDispose { }
        var stop: () -> Unit = {}
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    compass.start()
                    stop()
                    stop = startLocationUpdates(context) { loc ->
                        speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
                        compass.setLocation(loc.latitude, loc.longitude)
                        if (HeadingMath.gpsUsable(loc.hasBearing(), if (loc.hasSpeed()) loc.speed else 0f)) {
                            lastGpsAt = System.currentTimeMillis()
                            heading = HeadingMath.smooth(heading, loc.bearing.toDouble(), alpha = 0.5)
                        }
                        val h = heading
                        if (h != null && zones.isNotEmpty()) {
                            scope.launch {
                                val found = withContext(Dispatchers.Default) { FreeDrive.zonesAhead(loc.latitude, loc.longitude, h, zones) }
                                ahead = found.take(3)
                                found.firstOrNull()?.let { announcer.speakFor(it) }?.let { voice.speak(it) }
                            }
                        } else ahead = emptyList()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> { stop(); stop = {}; compass.stop(); announcer.forget() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) obs.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        onDispose { lifecycle.removeObserver(obs); stop(); compass.stop() }
    }

    if (!granted) {
        Text("위치 권한이 있어야 자유주행을 쓸 수 있어요.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("권한 허용") }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$speedKmh", fontSize = 56.sp, fontWeight = FontWeight.Black, lineHeight = 60.sp)
                Spacer(Modifier.width(6.dp))
                Text("km/h", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val h = heading
            Icon(
                Icons.Filled.Navigation,
                contentDescription = "진행 방향",
                tint = if (h != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(44.dp).rotate((h ?: 0.0).toFloat()),
            )
            Text(
                if (h != null) "${FreeDrive.compassWord(h)} ${h.toInt()}°" else "방향 확인 중",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("음성 안내", fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = voiceOn, onCheckedChange = { voiceOn = it })
    }
    Spacer(Modifier.height(8.dp))
    if (ahead.isEmpty()) {
        Text(
            if (zones.isEmpty()) "안전 지점 자료를 불러오는 중…" else "앞쪽 1.5km 안에 알릴 곳이 없어요.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ahead.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    a.zone.kind.label + (a.zone.limitKmh?.takeIf { a.zone.kind.isCamera }?.let { " · ${it}km/h" }.orEmpty()),
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(NavText.short(a.distance), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Volatile private var lastGpsAt = 0L

/** 마지막으로 GPS 방향을 받은 뒤 지난 시간(ms) */
private fun gpsAge(): Long = System.currentTimeMillis() - lastGpsAt
