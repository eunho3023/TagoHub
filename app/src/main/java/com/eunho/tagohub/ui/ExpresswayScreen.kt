package com.eunho.tagohub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.ExApi
import com.eunho.tagohub.data.ExCatalog
import com.eunho.tagohub.data.ExRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 한국도로공사 고속도로 공공데이터(OpenAPI 93개) 전체 조회 화면.
 * 서비스(묶음) 고르기 → API 고르기 → 요청변수 넣고 조회 → 결과 카드.
 */
@Composable
fun ExpresswayScreen() {
    val context = LocalContext.current
    val apis = remember {
        runCatching {
            context.assets.open("ex_catalog.json").bufferedReader(Charsets.UTF_8).use { ExCatalog.parse(it.readText()) }
        }.getOrDefault(emptyList())
    }
    var service by rememberSaveable { mutableStateOf<String?>(null) }
    var apiId by rememberSaveable { mutableStateOf<String?>(null) }
    val api = apis.firstOrNull { it.id == apiId }

    BackHandler(enabled = api != null) { apiId = null }
    BackHandler(enabled = api == null && service != null) { service = null }

    when {
        apis.isEmpty() -> Text("고속도로 API 목록을 읽지 못했습니다.", Modifier.padding(20.dp))
        api != null -> ExApiDetail(api)
        service != null -> {
            val inService = apis.filter { it.service == service }
            ExHeader("$service", "${inService.size}개") { service = null }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(inService, key = { it.id }) { a ->
                    ExRow(a.name, if (a.params.any { it.required }) "필수 입력 ${a.params.count { it.required }}개" else "바로 조회") { apiId = a.id }
                }
            }
        }
        else -> {
            val groups = apis.groupBy { it.service }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "한국도로공사 고속도로 공공데이터 ${apis.size}개를 직접 조회합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(groups.entries.toList(), key = { it.key }) { (name, list) ->
                    ExRow(name, "${list.size}개") { service = name }
                }
            }
        }
    }
}

@Composable
private fun ExHeader(title: String, sub: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹ 뒤로", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(title, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExRow(title: String, sub: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** 날짜·연도처럼 뻔한 요청변수는 미리 채움 */
internal fun exDefaultFor(code: String): String {
    val today = LocalDate.now()
    val ymd = DateTimeFormatter.BASIC_ISO_DATE
    return when {
        code.contains("year", ignoreCase = true) -> (today.year - 1).toString()
        code.contains("date", ignoreCase = true) -> today.minusDays(1).format(ymd)
        else -> ""
    }
}

@Composable
private fun ExApiDetail(api: ExApi) {
    val scope = rememberCoroutineScope()
    val repo = remember { ExRepository() }
    val values = remember(api.id) { mutableStateListOf<String>().apply { addAll(api.params.map { exDefaultFor(it.code) }) } }
    var rows by remember(api.id) { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var total by remember(api.id) { mutableStateOf<Int?>(null) }
    var page by remember(api.id) { mutableStateOf(0) }
    var loading by remember(api.id) { mutableStateOf(false) }
    var error by remember(api.id) { mutableStateOf<String?>(null) }

    fun load(next: Int) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val params = api.params.mapIndexed { i, p -> p.code to values[i] }.toMap()
                val res = repo.call(api, params, page = next)
                rows = if (next == 1) res.rows else rows + res.rows
                total = res.total
                page = next
            } catch (e: Exception) {
                error = e.message ?: "조회하지 못했습니다."
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(api.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(api.service, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(api.params.size) { i ->
            val p = api.params[i]
            OutlinedTextField(
                value = values[i],
                onValueChange = { values[i] = it },
                label = { Text(p.label + if (p.required) " *" else " (선택)") },
                supportingText = { Text(p.code) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = { load(1) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading && page == 0) "조회 중…" else "조회") }
        }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        if (page > 0 && rows.isEmpty() && error == null) item { Text("조회 결과가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (rows.isNotEmpty()) {
            item { Text("총 ${total ?: rows.size}건 중 ${rows.size}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows.size) { i ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows[i].filterKeys { it !in ExCatalog.META_FIELDS }.forEach { (k, v) ->
                            Row {
                                Text(api.outputs[k] ?: k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (total == null || rows.size < (total ?: 0)) {
                item {
                    OutlinedButton(onClick = { load(page + 1) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp))
                        Text("더 보기")
                    }
                }
            }
        }
    }
}
