package com.eunho.tagohub.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.guidance.AlightAlert
import com.eunho.tagohub.ui.bus.hasLocationPermission

/**
 * "하차 알림" 버튼: 이 곳에서 내릴 때 알려달라고 켜고 끈다.
 * 위치(앱 사용 중)와 알림(안드로이드 13+) 권한을 확인한 뒤 서비스를 켠다. 위치 권한이 없으면 시작하지 않는다.
 */
@Composable
internal fun AlightButton(target: AlightAlert.Target, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current by AlightAlert.target.collectAsStateWithLifecycle()
    val on = target.same(current)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) pending?.invoke()
        pending = null
    }
    fun begin() {
        val start = { AlightAlert.start(context, target) }
        val need = buildList {
            if (!hasLocationPermission(context)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) start() else { pending = start; launcher.launch(need.toTypedArray()) }
    }
    OutlinedButton(
        onClick = { if (on) AlightAlert.stop(context) else begin() },
        modifier = modifier,
    ) {
        Icon(if (on) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "하차 알림 끄기" else "하차 알림")
    }
}

/** 하차 알림이 켜져 있을 때 지도 위에 보이는 한 줄 카드: "○○ 하차 알림 · 1.2km 남음 [끄기]" */
@Composable
internal fun AlightStatusCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val target by AlightAlert.target.collectAsStateWithLifecycle()
    val distance by AlightAlert.distance.collectAsStateWithLifecycle()
    val note by AlightAlert.note.collectAsStateWithLifecycle()
    val t = target ?: return
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    "${t.name}${if (t.subway) "역" else ""} 하차 알림" + (distance?.let { " · ${NavText.short(it)} 남음" } ?: " · 위치 확인 중"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            }
            TextButton(onClick = { AlightAlert.stop(context) }) { Text("끄기") }
        }
    }
}
