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
import com.eunho.tagohub.ui.common.ChoiceTabs
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

/** 설정: 화면 모드, 길안내(안내 음성·크기·알릴 항목), 계정, 정보(공지사항·앱 제보·최근 오류) */
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
                Text("설정", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                SettingsLabel("화면 모드")
                ThemeModePicker()

                SettingsLabel("길안내")
                GuidanceSettings()

                SettingsLabel("계정")
                SettingsCard { AccountRow() }

                SettingsLabel("정보")
                // 앱이 비정상 종료된 적이 있으면 원인을 보고 공유할 수 있음
                var crash by remember { mutableStateOf(com.eunho.tagohub.data.CrashLog.last()) }
                var showCrash by remember { mutableStateOf(false) }
                SettingsCard {
                    MoreRow("공지사항", onNotices)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MoreRow("앱 제보", onReport)
                    if (crash != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoreRow("최근 오류") { showCrash = true }
                    }
                }
                if (showCrash) crash?.let { text ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showCrash = false },
                        title = { Text("최근 오류") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontSize = 13.sp) } },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
                                        "오류 보고서 공유",
                                    ),
                                )
                            }) { Text("공유") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { com.eunho.tagohub.data.CrashLog.clear(); crash = null; showCrash = false }) { Text("지우기") }
                        },
                    )
                }
                // 버전을 7번 누르면 개발자 모드(키 설정 안내·모의주행 같은 개발자용 표시)가 켜지고 꺼짐
                val devContext = LocalContext.current
                var taps by remember { mutableStateOf(0) }
                Text(
                    "앱 버전 ${com.eunho.tagohub.BuildConfig.VERSION_NAME}" + if (com.eunho.tagohub.core.DevMode.on) " · 개발자 모드" else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable {
                        taps += 1
                        if (taps >= 7) {
                            taps = 0
                            val on = com.eunho.tagohub.core.DevMode.toggle(devContext)
                            android.widget.Toast.makeText(devContext, if (on) "개발자 모드를 켰어요" else "개발자 모드를 껐어요", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
        AppBottomNav(AppTab.Settings, onTab)
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

/** 길안내 설정: 안내 음성 켜고 끄기, 음성 크기, 안내받을 항목 (자주 쓰는 4개 먼저, 나머지는 펼쳐서) */
@Composable
private fun GuidanceSettings() {
    val context = LocalContext.current
    val voiceOn by com.eunho.tagohub.guidance.DriveGuidance.voiceOn.collectAsStateWithLifecycle()
    val volume by com.eunho.tagohub.guidance.DriveGuidance.voiceVolume.collectAsStateWithLifecycle()
    val groups by com.eunho.tagohub.guidance.DriveGuidance.zoneGroups.collectAsStateWithLifecycle()
    var more by remember { mutableStateOf(false) }
    val main = listOf(
        com.eunho.tagohub.data.ZoneGroup.Camera, com.eunho.tagohub.data.ZoneGroup.Parking,
        com.eunho.tagohub.data.ZoneGroup.Protect, com.eunho.tagohub.data.ZoneGroup.Bump,
    )
    val rest = com.eunho.tagohub.data.ZoneGroup.entries - main.toSet()
    SettingsCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("안내 음성", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            com.eunho.tagohub.ui.common.ClearSwitch(voiceOn, { com.eunho.tagohub.guidance.DriveGuidance.voiceOn.value = it })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("음성 크기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Slider(
                value = volume,
                onValueChange = { com.eunho.tagohub.guidance.DriveGuidance.voiceVolume.value = it },
                enabled = voiceOn,
            )
        }
        (main + if (more) rest else emptyList()).forEach { g ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.label, Modifier.weight(1f).padding(end = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                com.eunho.tagohub.ui.common.ClearSwitch(g in groups, { com.eunho.tagohub.guidance.DriveGuidance.setZoneGroup(context, g, it) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            if (more) "접기" else "그 밖의 안내 항목 ${rest.size}개 보기",
            Modifier.fillMaxWidth().clickable { more = !more }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 14.dp),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        )
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
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
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
        // 계정·데이터 삭제 (Play 요구: 앱 안에서 지울 수 있어야 함)
        var confirmDelete by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(enabled = !busy && activity != null, onClick = { confirmDelete = true }) { Text("내 데이터 삭제") }
        if (confirmDelete) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("내 데이터를 삭제할까요?") },
            text = { Text("클라우드에 저장된 즐겨찾기·집/회사·안내 설정과 연결된 계정을 지우고, 이 기기의 즐겨찾기·집/회사·안내 설정도 비워요. 되돌릴 수 없어요. 앱 제보는 앱에서 지울 수 없으니 문의로 요청해 주세요.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    val a = activity ?: return@TextButton
                    busy = true
                    scope.launch {
                        val msg = com.eunho.tagohub.data.CloudAccount.deleteMyData(a)
                        busy = false
                        android.widget.Toast.makeText(a, msg ?: "삭제했어요. 앱을 다시 켜면 깨끗한 상태로 시작해요", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("삭제") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 화면 모드 고르기: 시스템(기본) · 라이트 · 다크. 고르면 바로 적용되고 기기에 저장됨 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeModePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by ThemeSettings.mode.collectAsStateWithLifecycle()
    ChoiceTabs(
        labels = ThemeMode.entries.map { it.label },
        selectedIndex = mode.ordinal,
        onSelect = { ThemeSettings.set(context, ThemeMode.entries[it]) },
        modifier = modifier,
    )
}
