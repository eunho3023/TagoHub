package com.eunho.tagohub.ui.home

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.RouteHistory
import com.eunho.tagohub.data.RouteRecord
import com.eunho.tagohub.data.SearchHistory
import com.eunho.tagohub.data.VisitLog
import com.eunho.tagohub.data.VisitRecord
import com.eunho.tagohub.ui.bus.currentLocation
import com.eunho.tagohub.ui.bus.hasLocationPermission
import com.eunho.tagohub.ui.map.MapViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기록·즐겨찾기 관리: 경로 기록, 검색 기록, 즐겨찾기를 한눈에 보고 하나씩 또는 모두 지움.
 * 경로·검색 기록은 "기록 저장" 스위치로 새로 쌓을지 정함. 기록은 로그인하면 계정에 함께 저장돼 다른 기기에서도 이어짐.
 */
@Composable
fun HistoryManageDialog(vm: MapViewModel, onDismiss: () -> Unit, onGoRoute: (RouteRecord) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("경로 기록", "검색 기록", "즐겨찾기", "방문 장소")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("기록·즐겨찾기 관리", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
                TabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> RouteTab(onGoRoute = { onGoRoute(it); onDismiss() })
                    1 -> SearchTab()
                    2 -> FavoriteTab(vm)
                    else -> VisitTab()
                }
            }
        }
    }
}

/** 위쪽 줄: (켜고 끄는 스위치) + 모두 지우기. 모두 지우기는 한 번 더 물어봄 */
@Composable
private fun ManageHeader(count: Int, noun: String, enabled: Boolean?, onEnabled: (Boolean) -> Unit, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (enabled != null) {
            Text("기록 저장", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        Text("${count}개", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = count > 0, onClick = { confirm = true }) { Text("모두 지우기") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("$noun 을(를) 모두 지울까요?") },
        text = { Text("지우면 되돌릴 수 없어요.") },
        confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RowWithDelete(title: String, sub: String?, deleteLabel: String, onClick: (() -> Unit)?, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = deleteLabel, modifier = Modifier.size(20.dp)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RouteTab(onGoRoute: (RouteRecord) -> Unit) {
    val items by RouteHistory.items.collectAsStateWithLifecycle()
    val enabled by RouteHistory.enabled.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    ManageHeader(items.size, "경로 기록", enabled, RouteHistory::setEnabled, RouteHistory::clear)
    if (items.isEmpty()) EmptyText("길찾기를 하거나 길 안내를 시작하면 여기에 기록돼요.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) { r ->
            RowWithDelete(
                "${r.fromName} → ${r.toName}",
                listOfNotNull(r.mode.takeIf { it.isNotBlank() }, if (r.guided) "안내 시작함" else null, if (r.atMs > 0) fmt.format(Date(r.atMs)) else null).joinToString(" · "),
                "경로 기록 지우기", { onGoRoute(r) }, { RouteHistory.remove(r) },
            )
        }
    }
}

@Composable
private fun SearchTab() {
    val items by SearchHistory.items.collectAsStateWithLifecycle()
    val enabled by SearchHistory.enabled.collectAsStateWithLifecycle()
    ManageHeader(items.size, "검색 기록", enabled, SearchHistory::setEnabled, SearchHistory::clear)
    if (items.isEmpty()) EmptyText("검색 결과를 누르면 검색어가 여기에 기록돼요.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) { q -> RowWithDelete(q, null, "$q 지우기", null) { SearchHistory.remove(q) } }
    }
}

@Composable
private fun FavoriteTab(vm: MapViewModel) {
    val items by vm.favorites.collectAsStateWithLifecycle()
    ManageHeader(items.size, "즐겨찾기", null, {}, vm::clearFavorites)
    if (items.isEmpty()) EmptyText("정류장·노선·역의 ★ 를 누르면 여기에 모여요.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) { f ->
            val kind = when (f.kind) { Favorite.Kind.Stop -> "정류장"; Favorite.Kind.Route -> "노선"; Favorite.Kind.Station -> "역" }
            RowWithDelete(f.name, listOfNotNull(kind, f.sub).joinToString(" · "), "${f.name} 즐겨찾기 지우기", null) { vm.removeFavorite(f) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VisitTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by VisitLog.items.collectAsStateWithLifecycle()
    var locating by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var label by remember { mutableStateOf("방문 장소") }
    val dateFmt = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    fun captureLocation() {
        if (locating) return
        locating = true
        scope.launch {
            val location = runCatching { currentLocation(context) }.getOrNull()
            locating = false
            if (location == null) {
                Toast.makeText(context, "현재 위치를 가져오지 못했어요. 위치 서비스가 켜져 있는지 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                label = "방문 장소"
                captured = location.latitude to location.longitude
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) captureLocation()
        else Toast.makeText(context, "위치 권한이 없어 현재 장소를 저장할 수 없어요.", Toast.LENGTH_LONG).show()
    }

    ManageHeader(records.size, "방문 장소", null, {}, VisitLog::clear)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = {
                if (hasLocationPermission(context)) captureLocation()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (locating) "위치 확인 중…" else "현재 위치 저장", Modifier.padding(start = 8.dp))
        }
        Text("기록은 버튼을 눌렀을 때만 저장되며, 이 기기 안에만 보관돼요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (records.isEmpty()) EmptyText("버스 정류장을 열어 방문 기록을 저장하거나, 현재 위치를 기록해 보세요.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(records, key = { it.id }) { record ->
            val coordinates = String.format(Locale.US, "%.5f, %.5f", record.lat, record.lng)
            val sub = listOfNotNull(record.source, record.detail, dateFmt.format(Date(record.atMs)), coordinates).joinToString(" · ")
            RowWithDelete(record.name, sub, "${record.name} 방문 기록 지우기", null) { VisitLog.remove(record) }
        }
    }

    captured?.let { (lat, lng) ->
        AlertDialog(
            onDismissRequest = { captured = null },
            title = { Text("방문 장소 이름") },
            text = {
                Column {
                    Text(String.format(Locale.US, "위치: %.5f, %.5f", lat, lng), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("장소 이름") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (label.isNotBlank()) {
                        VisitLog.add(VisitRecord(name = label.trim(), source = "현재 위치", lat = lat, lng = lng))
                        captured = null
                        Toast.makeText(context, "방문 장소를 저장했어요.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { captured = null }) { Text("취소") } },
        )
    }
}
