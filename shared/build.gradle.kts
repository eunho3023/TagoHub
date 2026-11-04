// 폰·웹·PC 가 같이 쓰는 코드 (Kotlin Multiplatform). 안드로이드·자바 전용 기능을 쓰지 않는 계산·데이터 코드만 여기에.
// 패키지 이름은 앱과 같게 둬서 앱 코드의 import 를 바꾸지 않음.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

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
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
