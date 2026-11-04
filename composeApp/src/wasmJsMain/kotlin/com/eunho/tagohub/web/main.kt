package com.eunho.tagohub.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.browser.document
import kotlinx.browser.window

/** 브라우저의 decodeURIComponent */
private fun decodeURIComponent(s: String): String = js("decodeURIComponent(s)")

/** 한글 글꼴 (웹 화면은 브라우저 글꼴을 못 씀). 필요한 글자만 남긴 작은 파일 (pipeline/build_web_font.py), 서버가 페이지와 같이 둠 */
private const val FONT_PATH = "fonts/NotoSansKR-sub.ttf"

/** 웹 버전: 페이지를 내준 서버(중계 서버)에 그대로 물음 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val origin = window.location.origin + "/"
    // 시험용: ?server=https://….workers.dev 를 붙이면 자료·실시간을 그 서버에 물음 (자료·웹 통로는 다른 주소에서도 받게 열려 있음)
    val server = Regex("""[?&]server=([^&]+)""").find(window.location.search)?.groupValues?.get(1)
        ?.let { decodeURIComponent(it) }?.takeIf { it.startsWith("https://") } ?: origin
    val http = HttpClient()
    val repo = WebRepo(server, http)
    ComposeViewport(document.body!!) {
        var font by remember { mutableStateOf<FontFamily?>(null) }
        LaunchedEffect(Unit) {
            font = runCatching { FontFamily(Font("NotoSansKR", http.get(origin + FONT_PATH).readRawBytes())) }.getOrNull()
        }
        App(repo, font)
    }
}
