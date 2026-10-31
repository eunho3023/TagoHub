package com.eunho.tagohub.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.VehicleIcon
import com.eunho.tagohub.data.VehicleSettings

/** 내 위치 아이콘 고르기: 기본(점)·세단·트럭·버스·오토바이·자전거. 고르면 바로 지도에 반영되고 기기에 저장됨 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehiclePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected by VehicleSettings.selected.collectAsStateWithLifecycle()
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        VehicleIcon.entries.forEach { v ->
            FilterChip(selected = selected == v, onClick = { VehicleSettings.set(context, v) }, label = { Text(v.label) })
        }
    }
}
