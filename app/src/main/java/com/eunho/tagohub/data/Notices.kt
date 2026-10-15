package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson

/** 공지사항: 깃허브 저장소의 notices.json ([{date,title,body}]) — 저장소에서 파일만 고치면 앱에 반영 */
object Notices {
    const val URL = "https://raw.githubusercontent.com/eunho3023/TagoHub/main/notices.json"

    data class Notice(val date: String, val title: String, val body: String)

    fun parse(text: String): List<Notice> {
        val list = runCatching { MiniJson.parse(text) }.getOrNull() as? List<*> ?: return emptyList()
        return list.mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            val title = m["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Notice(m["date"]?.toString().orEmpty(), title, m["body"]?.toString().orEmpty())
        }.sortedByDescending { it.date }
    }

    /** 앱 제보: 깃허브 이슈 쓰기 화면 주소 (제목·내용을 채워서) */
    fun reportUrl(kind: String, text: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val title = "[$kind] " + text.lineSequence().firstOrNull().orEmpty().take(40)
        return "https://github.com/eunho3023/TagoHub/issues/new?title=${enc(title)}&body=${enc("종류: $kind\n\n$text")}"
    }
}
