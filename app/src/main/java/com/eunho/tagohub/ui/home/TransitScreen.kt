package com.eunho.tagohub.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.ui.common.ChoiceTabs

/** 대중교통 탭 안의 갈래 */
enum class TransitMode(val label: String) { Bus("버스"), Subway("지하철"), Train("기차"), Coach("고속·시외"), Air("항공") }

/**
 * 대중교통 한 화면: 위의 갈래(버스·지하철·기차·고속·시외·항공)를 고르면 아래에 그 화면이 나옴.
 * 예전에는 하단 탭이 갈래마다 따로 있었음.
 */
@Composable
fun TransitScreen(
    mode: TransitMode,
    onMode: (TransitMode) -> Unit,
    bus: @Composable () -> Unit,
    subway: @Composable () -> Unit,
    train: @Composable () -> Unit,
    coach: @Composable () -> Unit,
    air: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ChoiceTabs(
            labels = TransitMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            onSelect = { onMode(TransitMode.entries[it]) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            when (mode) {
                TransitMode.Bus -> bus()
                TransitMode.Subway -> subway()
                TransitMode.Train -> train()
                TransitMode.Coach -> coach()
                TransitMode.Air -> air()
            }
        }
    }
}
