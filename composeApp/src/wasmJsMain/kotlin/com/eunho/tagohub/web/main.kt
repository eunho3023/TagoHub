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
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.js.Promise
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 브라우저의 decodeURIComponent */
private fun decodeURIComponent(s: String): String = js("decodeURIComponent(s)")

/** 한글 글꼴 (웹 화면은 브라우저 글꼴을 못 씀). index.html 이 페이지를 열자마자 받기 시작한 것 (pipeline/build_web_font.py 로 만든 작은 파일) */
@JsFun("() => window.TAGOHUB_FONT || Promise.resolve(null)")
private external fun fontPromise(): Promise<JsAny?>

private suspend fun fontBytes(): ByteArray? {
    val buf = fontPromise().await<JsAny?>() as? ArrayBuffer ?: return null
    val a = Int8Array(buf)
    return ByteArray(a.length) { a[it] }
}

/** 웹 버전: 페이지를 내준 서버(중계 서버)에 그대로 물음 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val origin = window.location.origin + "/"
    // 시험용: ?server=https://….workers.dev 를 붙이면 자료·실시간을 그 서버에 물음 (자료·웹 통로는 다른 주소에서도 받게 열려 있음)
    val server = Regex("""[?&]server=([^&]+)""").find(window.location.search)?.groupValues?.get(1)
        ?.let { decodeURIComponent(it) }?.takeIf { it.startsWith("https://") } ?: origin
    val http = HttpClient()
    val repo = WebRepo(server, http)
    ComposeViewport(document.getElementById("app") ?: document.body!!) {
        var font by remember { mutableStateOf<FontFamily?>(null) }
        LaunchedEffect(Unit) {
            // 불러오는 중 화면(index.html 의 #splash)은 한글 글꼴이 오면 지움 (글꼴 없이 그리면 한글이 네모로 보임). 늦어도 5초 뒤엔 지움
            val splashOff = launch { delay(5_000); document.getElementById("splash")?.remove() }
            font = runCatching { fontBytes()?.let { FontFamily(Font("NotoSansKR", it)) } }.getOrNull()
            splashOff.cancel()
            delay(150)   // 글꼴로 다시 그려진 뒤에 지움 (한 장면이라도 네모 글자가 보이지 않게)
            document.getElementById("splash")?.remove()
        }
        App(repo, font, http)
    }
}
