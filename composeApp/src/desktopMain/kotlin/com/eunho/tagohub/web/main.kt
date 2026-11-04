package com.eunho.tagohub.web

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** PC 버전. 서버 주소는 빌드할 때 local.properties 에서 넣고, 실행할 때 TAGOHUB_SERVER 환경 변수로 바꿀 수 있음 */
fun main() = application {
    val server = System.getenv("TAGOHUB_SERVER")?.takeIf { it.isNotBlank() } ?: BUILT_SERVER_URL
    val repo = WebRepo(server)
    Window(onCloseRequest = ::exitApplication, title = "TagoHub", state = rememberWindowState(width = 480.dp, height = 800.dp)) {
        App(repo)
    }
}
