import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.eunho.tagohub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eunho.tagohub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String", "TAGO_SERVICE_KEY",
            "\"${localProps.getProperty("TAGO_SERVICE_KEY", "")}\""
        )
        // 서울 열린데이터광장 실시간 지하철 인증키 (swopenapi)
        buildConfigField("String", "SEOUL_SUBWAY_KEY", "\"${localProps.getProperty("SEOUL_SUBWAY_KEY", "")}\"")
        // 카카오맵 네이티브 앱 키 (카카오 개발자 사이트 → 내 애플리케이션 → 앱 키)
        buildConfigField("String", "KAKAO_NATIVE_KEY", "\"${localProps.getProperty("KAKAO_NATIVE_KEY", "")}\"")
        // 카카오 로컬 API (길찾기 장소 이름 검색) — 같은 카카오 앱의 REST API 키
        buildConfigField("String", "KAKAO_REST_KEY", "\"${localProps.getProperty("KAKAO_REST_KEY", "")}\"")
        // 브이월드 2D 데이터 API (전국 CCTV, LT_P_UTISCCTV) — 키 발급 때 등록한 서비스 URL 이 domain
        buildConfigField("String", "VWORLD_DATA_KEY", "\"${localProps.getProperty("VWORLD_DATA_KEY", "")}\"")
        buildConfigField("String", "VWORLD_DOMAIN", "\"${localProps.getProperty("VWORLD_DOMAIN", "")}\"")
        // 정류장·노선 데이터 저장소 안내 파일 (GitHub Release data-latest 의 manifest.json, pipeline/README.md). local.properties 로 바꿀 수 있음
        buildConfigField("String", "DATA_MANIFEST_URL", "\"${localProps.getProperty("DATA_MANIFEST_URL", "https://github.com/eunho3023/TagoHub/releases/download/data-latest/manifest.json")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 카카오맵 SDK v2
    implementation("com.kakao.maps.open:android:2.15.2")

    testImplementation("junit:junit:4.13.2")
}
