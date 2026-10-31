package com.eunho.tagohub.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.NightMode
import com.eunho.tagohub.data.NightSettings

/** 야간 주행 모드 고르기: 끔 · 밤에만(저녁 7시~아침 6시) · 항상. 달리는 중에만 지도를 어둡게 함 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightModePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected by NightSettings.mode.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightMode.entries.forEach { m ->
                FilterChip(selected = selected == m, onClick = { NightSettings.set(context, m) }, label = { Text(m.label) })
            }
        }
        Text(
            "길 안내·자유주행 중에 일반 지도를 어둡게 해서 눈부심을 줄여요(위성 지도는 어둡게 하지 않아요). 밤은 저녁 ${NightMode.NIGHT_FROM}시부터 아침 ${NightMode.NIGHT_TO}시까지예요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
