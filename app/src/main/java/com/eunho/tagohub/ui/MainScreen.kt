package com.eunho.tagohub.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.eunho.tagohub.ui.coach.CoachScreen
import com.eunho.tagohub.ui.map.MapScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.timetable.AirScreen
import com.eunho.tagohub.ui.timetable.TrainScreen

enum class Tab(val label: String, val icon: ImageVector, val color: Color) {
    /** 버스·지하철을 한 지도에서 */
    Bus("버스·지하철", Icons.Filled.DirectionsBus, ModeColor.Bus),
    Train("기차", Icons.Filled.Train, ModeColor.Train),
    Coach("고속·시외", Icons.Filled.AirportShuttle, ModeColor.Coach),
    Air("항공", Icons.Filled.Flight, ModeColor.Air),
}

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(Tab.Bus) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.color,
                            selectedTextColor = t.color,
                            indicatorColor = t.color.copy(alpha = 0.14f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when (tab) {
                // 버스·지하철 탭: 같은 지도 화면을 레이어만 바꿔 별도 ViewModel 인스턴스로
                Tab.Bus -> MapScreen(viewModel<MapViewModel>(key = "transitMap"), MapMode.Transit)
                Tab.Train -> TrainScreen()
                Tab.Coach -> CoachScreen()
                Tab.Air -> AirScreen()
            }
        }
    }
}
