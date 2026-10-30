package com.eunho.tagohub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.ui.common.ChoiceTabs
import com.eunho.tagohub.ui.map.ItsRoadScreen
import com.eunho.tagohub.ui.map.MapViewModel

/**
 * 도로 정보 한 화면: 기본은 "교통상황"(지금 막히는 곳), 전문가용 화면은 "고급 도로정보" 한 곳에 모음.
 * 고속도로 API 를 직접 조회하는 화면은 개발자 모드에서만 보임. 지도 위 정보는 지도의 레이어 버튼에서 켜고, 여기서는 목록·표로 자세히 본다.
 */
@Composable
fun RoadInfoScreen(vm: MapViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("교통상황", "고급 도로정보")
    Column(Modifier.fillMaxSize()) {
        ChoiceTabs(titles, tab, { tab = it }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> UticTrafficScreen()
                else -> AdvancedRoadInfo(vm)
            }
        }
    }
}

/** 소통·예측(구간별 예측 조회)과, 개발자 모드에서는 고속도로 API 조회 */
@Composable
private fun AdvancedRoadInfo(vm: MapViewModel) {
    val dev = com.eunho.tagohub.core.DevMode.on
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (dev) ChoiceTabs(listOf("소통·예측", "고속도로 API"), page, { page = it }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        Box(Modifier.weight(1f)) {
            if (dev && page == 1) ExpresswayScreen() else ItsRoadScreen(vm)
        }
    }
}
