package com.eunho.tagohub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.ui.MainScreen
import com.eunho.tagohub.ui.theme.TagoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // 방향 고정이 남아 있으면 풀어서 폰을 돌리는 대로 따라가게
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onDestroy() {
        // 뒤로 가기 등으로 앱을 끝낼 때는 경로 안내도 끝냄 (홈 버튼·화면 회전은 해당 없음: 안내는 계속)
        if (isFinishing && !isChangingConfigurations && com.eunho.tagohub.guidance.DriveGuidance.active.value) {
            com.eunho.tagohub.guidance.DriveGuidance.stop(applicationContext)
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 전국 정류장 DB 를 화면 그리기와 동시에 준비 (첫 실행 때만 복사, 이후엔 바로 열림)
        // 카카오맵 SDK 초기화 (지도 시작 전에 한 번)
        if (BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) {
            runCatching { com.kakao.vectormap.KakaoMapSdk.init(applicationContext, BuildConfig.KAKAO_NATIVE_KEY) }
            // 카카오 개발자 사이트에 등록할 키 해시 (Logcat 에서 TagoKakao 로 검색)
            if (BuildConfig.DEBUG) android.util.Log.i("TagoKakao", "키 해시: ${com.eunho.tagohub.ui.map.KakaoCanvas.keyHash(this)}")
        }
        lifecycleScope.launch {
            StopDb.get(applicationContext)
            // 정류장·노선 데이터 자동 업데이트 (데이터 저장소, 하루 한 번·와이파이에서만)
            com.eunho.tagohub.data.DataUpdater.check(applicationContext)
        }
        // 예전 브이월드(OSMDroid) 지도 조각 저장소 정리 (최대 400MB, 이제 안 씀)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(filesDir, "osmdroid").takeIf { it.exists() }?.deleteRecursively()
        }
        // 잘 안 바뀌는 정보(노선 모양·정류장 순서·노선 정보 등)를 저장해 두는 곳
        com.eunho.tagohub.data.StaticCache.init(filesDir, appVersionCode())
        com.eunho.tagohub.data.OverlaySnapshot.init(filesDir)
        setContent { TagoTheme { MainScreen() } }
    }

    /** 앱 버전 번호 (업데이트되면 저장해 둔 노선 정보를 새로 받기 위해) */
    private fun appVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)
}
