package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient

/** 공지사항 한 개 (저장소 notices.json, 폰 앱과 같은 파일) */
data class NoticeW(val date: String, val title: String, val body: String)

/** 공지사항: 깃허브 저장소의 notices.json ([{date,title,body}]). 파일만 고치면 앱·웹에 같이 나옴 */
object NoticesW {
    const val URL = "https://raw.githubusercontent.com/eunho3023/TagoHub/main/notices.json"
    const val ISSUES = "https://github.com/eunho3023/TagoHub/issues/new"

    suspend fun load(http: HttpClient): List<NoticeW> = parse(http.getText(URL))

    fun parse(text: String): List<NoticeW> {
        val list = runCatching { MiniJson.parse(text) }.getOrNull() as? List<*> ?: return emptyList()
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val title = m["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NoticeW(m["date"]?.toString().orEmpty(), title, m["body"]?.toString().orEmpty())
        }.sortedByDescending { it.date }
    }

    /** 제보: 깃허브 이슈 쓰기 화면 (제목만 채움) */
    fun reportUrl(): String = "$ISSUES?title=" + "[웹 제보] ".encodeToByteArray().joinToString("") { b ->
        val c = b.toInt() and 0xFF
        if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A) c.toChar().toString() else "%" + c.toString(16).uppercase().padStart(2, '0')
    }
}

/** 찾기 화면 아래의 공지 한 줄 (가장 최근 것), 누르면 공지 화면 */
@Composable
internal fun NoticeLine(latest: NoticeW?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("공지", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(
            latest?.title ?: "공지사항 · 제보", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 공지사항 화면 + 제보하기 (깃허브 이슈) */
@Composable
internal fun NoticesPane(list: List<NoticeW>?, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Header("공지사항", null, onBack, action = "제보하기", onAction = { runCatching { uri.openUri(NoticesW.reportUrl()) } })
    when {
        list == null -> Note("공지사항을 불러오는 중...")
        list.isEmpty() -> Note("공지사항이 없어요")
    }
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        items(list.orEmpty(), key = { it.date + it.title }) { n ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (n.date.isNotEmpty()) Text(n.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (n.body.isNotEmpty()) Text(n.body, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
