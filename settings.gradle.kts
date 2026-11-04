pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // 웹(wasm) 빌드 도구가 스스로 저장소를 더하려 해서, 여기 적은 저장소만 쓰게 함 (아래 Node·Yarn·Binaryen)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // 카카오맵 SDK v2 (KakaoMaps SDK for Android)
        maven { url = uri("https://devrepo.kakao.com/nexus/repository/kakaomap-releases/") }
        // 웹 버전 빌드 도구 (Kotlin/Wasm 이 받아 씀)
        ivy("https://nodejs.org/dist") {
            name = "Node.js"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}
rootProject.name = "TagoHub"
include(":app")
include(":shared")
include(":composeApp")
