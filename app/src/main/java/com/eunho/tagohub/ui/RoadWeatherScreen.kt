package com.eunho.tagohub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.KmaRoadParser
import com.eunho.tagohub.data.KmaRoadRepository
import com.eunho.tagohub.data.KmaRoadStation
import com.eunho.tagohub.data.KmaRoadWeather
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 기상청 CCTV 기반 도로날씨: 노선 고르기 → 그 노선 지점들의 현재 도로날씨 (지점 861곳, 데이터가 있는 곳만 표시) */
@Composable
fun RoadWeatherScreen() {
    val context = LocalContext.current
    val stations = remember {
        runCatching {
            context.assets.open("kma_road_stations.json").bufferedReader(Charsets.UTF_8).use { KmaRoadParser.stations(it.readText()) }
        }.getOrDefault(emptyList())
    }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = route != null) { route = null }

    val picked = route
    when {
        stations.isEmpty() -> Text("도로날씨 지점 목록을 읽지 못했습니다.", Modifier.padding(20.dp))
        picked == null -> {
            val groups = stations.groupBy { it.route }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "기상청이 CCTV 영상을 분석한 도로날씨입니다. 지점 ${stations.size}곳 중 관측값이 있는 곳만 날씨가 나옵니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(groups.entries.toList(), key = { it.key }) { (name, list) ->
                    Surface(onClick = { route = name }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("${list.size}곳", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
        }
        else -> RouteWeather(picked, stations.filter { it.route == picked })
    }
}

@Composable
private fun RouteWeather(route: String, list: List<KmaRoadStation>) {
    val repo = remember { KmaRoadRepository() }
    val results = remember(route) { mutableStateMapOf<String, KmaRoadWeather?>() }
    var done by remember(route) { mutableStateOf(0) }
    var error by remember(route) { mutableStateOf<String?>(null) }

    // 이 노선 지점을 한꺼번에(동시에 6개씩) 조회
    LaunchedEffect(route) {
        val gate = Semaphore(6)
        coroutineScope {
            list.map { s ->
                async {
                    gate.withPermit {
                        try {
                            results[s.id] = repo.weather(s.id)
                        } catch (e: Exception) {
                            if (error == null) error = e.message
                        } finally {
                            done++
                        }
                    }
                }
            }.awaitAll()
        }
    }

    val withData = list.count { results[it.id] != null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(route, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (done < list.size) "조회 중… $done/${list.size}" else "${list.size}곳 중 ${withData}곳에 도로날씨가 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (done < list.size) LinearProgressIndicator(progress = { done / list.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        // 날씨가 있는 지점을 위로
        items(list.sortedByDescending { results[it.id] != null }, key = { it.id }) { s ->
            val w = results[s.id]
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (w != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(w.weather, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(w.timeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(if (s.id in results) "정보 없음" else "…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
