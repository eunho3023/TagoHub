package com.eunho.tagohub.ui.navi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.map.PlannerPage

/**
 * 길찾기 탭: 처음 화면은 출발·도착을 정해 경로를 찾는 길찾기(대중교통·자동차·도보·자전거),
 * 위쪽 두 번째 칩은 예전 내비게이션 탭 내용(목적지 안내·자유주행·최근 목적지).
 */
@Composable
fun RouteTab(vm: MapViewModel, onShowMap: () -> Unit, navi: @Composable () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("경로 찾기", "자동차 안내").forEachIndexed { i, label ->
                FilterChip(selected = page == i, onClick = { page = i }, label = { Text(label) })
            }
        }
        Box(Modifier.weight(1f)) {
            if (page == 0) PlannerPage(vm, onShowMap) else navi()
        }
    }
}
