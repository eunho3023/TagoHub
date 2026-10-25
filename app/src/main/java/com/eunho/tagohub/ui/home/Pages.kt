package com.eunho.tagohub.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirportShuttle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.ui.ModeJump
import kotlinx.coroutines.launch
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.ThemeMode
import com.eunho.tagohub.ui.theme.ThemeSettings

/** 기차·고속시외·항공·도로교통 화면의 모드색 (라이트/다크) */
fun pageColor(kind: ModeJump.Kind, dark: Boolean): Color = when (kind) {
    ModeJump.Kind.Train -> if (dark) Color(0xFF2F55B5) else Color(0xFF1B3E8A)
    ModeJump.Kind.Coach -> Color(0xFF0A7E8C)
    ModeJump.Kind.Air -> Color(0xFFA85F0E)
    ModeJump.Kind.Road -> Color(0xFF294653)
}

/**
 * 모드색 머리띠가 위에 있고 아래에 탭이 있는 한 화면. 노선도(Line Map) 방식 화면의 틀:
 * 머리띠에는 큰 제목(예: 기차), 본문은 [content].
 */
@Composable
fun PageScaffold(
    kind: ModeJump.Kind,
    tab: AppTab,
    onTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TabScaffold(kind.label, pageColor(kind, LocalDarkTheme.current), tab, onTab, modifier, content)
}

/** 제목 머리띠 + 본문 + 아래 탭. 버스·지하철·내비게이션 탭도 같은 틀을 씀 */
@Composable
fun TabScaffold(
    title: String,
    color: Color,
    tab: AppTab,
    onTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 흰 머리 + 아래 얇은 선. 어느 탭인지는 제목 앞의 수단 색 점으로
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(color, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) { content() }
        AppBottomNav(tab, onTab)
    }
}

/** 설정: 화면 모드, 공지사항, 앱 제보 */
@Composable
fun MorePage(
    onOpen: (ModeJump.Kind) -> Unit,
    onNotices: () -> Unit,
    onReport: () -> Unit,
    onTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("설정", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                Text("화면 모드", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                ThemeModePicker()
                Text(
                    "시스템을 고르면 기기의 라이트·다크 설정을 따라 바뀝니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                AccountRow()
                MoreRow("공지사항", Icons.Outlined.Info, onNotices)
                MoreRow("앱 제보", Icons.Outlined.Email, onReport)
                // 앱이 비정상 종료된 적이 있으면 원인을 보고 공유할 수 있음
                var crash by remember { mutableStateOf(com.eunho.tagohub.data.CrashLog.last()) }
                var showCrash by remember { mutableStateOf(false) }
                if (crash != null) MoreRow("최근 오류 보고서", Icons.Outlined.Info) { showCrash = true }
                if (showCrash) crash?.let { text ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showCrash = false },
                        title = { Text("최근 오류") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontSize = 12.sp) } },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "오류 보고서 공유",
                                    ),
                                )
                            }) { Text("공유") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { com.eunho.tagohub.data.CrashLog.clear(); crash = null; showCrash = false }) { Text("지우고 닫기") }
                        },
                    )
                }
            }
        }
        AppBottomNav(AppTab.Settings, onTab)
    }
}

/** 구글 계정 연결: 연결하면 즐겨찾기·설정이 계정에 이어져서 앱을 지우거나 폰을 바꿔도 유지됨 */
@Composable
private fun AccountRow() {
    val context = LocalContext.current
    val account by com.eunho.tagohub.data.CloudAccount.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val activity = remember(context) {
        var c = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    }
    val email = account.email
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text("계정", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            if (email != null) "$email 연결됨 · 즐겨찾기와 설정이 계정에 저장돼요" else "구글 계정을 연결하면 앱을 지우거나 폰을 바꿔도 즐겨찾기와 설정이 이어져요",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(
            enabled = !busy && activity != null,
            onClick = {
                val a = activity ?: return@OutlinedButton
                if (email != null) {
                    com.eunho.tagohub.data.CloudAccount.signOut(a)
                } else {
                    busy = true
                    scope.launch {
                        val msg = com.eunho.tagohub.data.CloudAccount.signInWithGoogle(a)
                        busy = false
                        if (msg != null) android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
        ) { Text(if (email != null) "로그아웃" else if (busy) "연결 중…" else "구글 계정 연결") }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun MoreRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 화면 모드 고르기: 시스템(기본) · 라이트 · 다크. 고르면 바로 적용되고 기기에 저장됨 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by ThemeSettings.mode.collectAsStateWithLifecycle()
    // 세 칸 전환: 회색 바탕 위에 고른 칸만 흰 카드로
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ThemeMode.entries.forEach { m ->
            val on = mode == m
            Surface(
                onClick = { ThemeSettings.set(context, m) },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        m.label,
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
