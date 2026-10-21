package com.eunho.tagohub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.ui.map.ItsRoadScreen
import com.eunho.tagohub.ui.map.MapViewModel

/**
 * 도로 정보 한 화면: 예전에는 메뉴가 넷(실시간 교통상황 · 도로 정보 · 고속도로 정보 · 도로날씨)으로 나뉘어 있었다.
 * 지도 위 정보는 지도의 레이어 버튼에서 켜고, 여기서는 목록·표로 자세히 본다.
 */
@Composable
fun RoadInfoScreen(vm: MapViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("교통상황", "소통·예측", "고속도로", "도로날씨")
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> UticTrafficScreen()
                1 -> ItsRoadScreen(vm)
                2 -> ExpresswayScreen()
                else -> RoadWeatherScreen()
            }
        }
    }
}
