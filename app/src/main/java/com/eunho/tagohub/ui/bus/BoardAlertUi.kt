package com.eunho.tagohub.ui.bus

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.BoardRule
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.guidance.BoardAlert

/**
 * 정류장 화면의 승차 알림: 노선 줄의 종을 누르면 "몇 정류장/몇 분 전에 알릴지" 고르고, 켜져 있으면 끈다.
 * [content] 에 (이 노선이 켜져 있는지, 종을 눌렀을 때) 를 넘겨준다.
 */
@Composable
internal fun BoardBellHost(
    stop: BusStop,
    others: List<BusStop>,
    content: @Composable (isOn: (StopRouteRow) -> Boolean, onBell: (StopRouteRow) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val current by BoardAlert.target.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<StopRouteRow?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()   // 알림 권한을 거절해도 음성·진동은 됨
        pending = null
    }
    fun isOn(r: StopRouteRow) = current?.let { it.routeId == r.routeId && it.stops.firstOrNull()?.nodeId == stop.nodeId } == true

    content(::isOn) { r ->
        if (isOn(r)) {
            BoardAlert.stop(context)
            Toast.makeText(context, "승차 알림을 껐어요.", Toast.LENGTH_SHORT).show()
        } else picking = r
    }

    val r = picking ?: return
    AlertDialog(
        onDismissRequest = { picking = null },
        title = { Text("${r.routeNo}번 승차 알림") },
        text = {
            Column {
                Text(
                    "버스가 이만큼 가까워지면 소리·진동으로 알려드려요. 앱을 닫아도 동작해요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoardRule.OPTIONS.forEach { rule ->
                    Text(
                        rule.label,
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picking = null
                                val start = {
                                    BoardAlert.start(context, BoardAlert.Target(listOf(stop) + others, stop.name, r.routeId, r.routeNo, rule))
                                    Toast.makeText(context, "${r.routeNo}번 버스가 ${rule.label}이면 알려드릴게요.", Toast.LENGTH_SHORT).show()
                                }
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pending = start
                                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else start()
                            }
                            .padding(vertical = 14.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { picking = null }) { Text("취소") } },
    )
}
