// 웹(wasm)·PC(데스크톱) 버전 화면 (Compose Multiplatform). 계산·통신·자료 해석은 :shared 를 같이 씀.
// 정류장·노선 자료는 중계 서버의 /data 조각, 실시간 도착은 서버의 웹 전용 통로(/w/, 토큰 없음)에서 받음.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// PC 버전이 물을 서버 주소 (local.properties 의 TAGO_PROXY_URL; 토큰은 넣지 않음). 웹은 페이지가 있는 서버를 씀
val serverUrl: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("TAGO_PROXY_URL", "").trim()

val genDir = layout.buildDirectory.dir("generated/serverUrl")
val genServerUrl by tasks.registering {
    val out = genDir
    val url = serverUrl
    inputs.property("url", url)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/eunho/tagohub/web/ServerUrl.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.eunho.tagohub.web\n\n/** 빌드할 때 local.properties 에서 넣음 */\ninternal const val BUILT_SERVER_URL = \"${url.replace("\"", "")}\"\n")
    }
}

kotlin {
    jvm("desktop")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "tagohub"
        browser { commonWebpackConfig { outputFileName = "tagohub.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.2.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        val desktopMain by getting {
            kotlin.srcDir(genServerUrl)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.eunho.tagohub.web.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "TagoHub"
            packageVersion = "1.0.0"
            windows {
                menu = true
                shortcut = true
            }
        }
    }
}
