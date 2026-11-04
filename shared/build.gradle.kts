// 폰·웹·PC 가 같이 쓰는 코드 (Kotlin Multiplatform). 안드로이드·자바 전용 기능을 쓰지 않는 계산·데이터 코드만 여기에.
// 패키지 이름은 앱과 같게 둬서 앱 코드의 import 를 바꾸지 않음.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val ktor = "3.2.3"

kotlin {
    androidLibrary {
        namespace = "com.eunho.tagohub.shared"
        compileSdk = 37
        minSdk = 26
    }
    jvm()          // PC (exe)
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }   // 웹

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            // 통신 (Ktor): 폰·PC 는 OkHttp, 웹은 브라우저 fetch
            api("io.ktor:ktor-client-core:$ktor")
        }
        androidMain.dependencies { api("io.ktor:ktor-client-okhttp:$ktor") }
        jvmMain.dependencies { api("io.ktor:ktor-client-okhttp:$ktor") }
        wasmJsMain.dependencies { api("io.ktor:ktor-client-js:$ktor") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:$ktor")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
