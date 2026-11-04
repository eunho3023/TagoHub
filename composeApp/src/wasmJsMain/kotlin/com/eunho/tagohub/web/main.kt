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

/** 한글 글꼴 (웹 화면은 브라우저 글꼴을 못 씀). 서버가 페이지와 같이 둠 */
private const val FONT_PATH = "fonts/NotoSansKR.ttf"

/** 웹 버전: 페이지를 내준 서버(중계 서버)에 그대로 물음 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val origin = window.location.origin + "/"
    val http = HttpClient()
    val repo = WebRepo(origin, http)
    ComposeViewport(document.body!!) {
        var font by remember { mutableStateOf<FontFamily?>(null) }
        LaunchedEffect(Unit) {
            font = runCatching { FontFamily(Font("NotoSansKR", http.get(origin + FONT_PATH).readRawBytes())) }.getOrNull()
        }
        App(repo, font)
    }
}
