package com.eunho.tagohub

import com.eunho.tagohub.core.init
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.ui.MainScreen
import com.eunho.tagohub.ui.theme.TagoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 관리자 페이지에서 고친 배치도·노선 선형을 다시 확인하는 간격 (앱이 화면에 보이는 동안) */
private const val REMOTE_EDITS_REFRESH_MS = 5 * 60_000L

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.eunho.tagohub.core.AppState.foreground.value = true
    }

    override fun onStop() {
        com.eunho.tagohub.core.AppState.foreground.value = false
        super.onStop()
    }

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
        // 앱이 비정상 종료되면 원인을 저장해 둠 (더보기 → 최근 오류)
        com.eunho.tagohub.data.CrashLog.install(applicationContext)
        com.eunho.tagohub.core.DevMode.init(applicationContext)
        enableEdgeToEdge()
        // 전국 정류장 DB 를 화면 그리기와 동시에 준비 (첫 실행 때만 복사, 이후엔 바로 열림)
        // 카카오맵 SDK 초기화 (지도 시작 전에 한 번)
        if (BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) {
            runCatching { com.kakao.vectormap.KakaoMapSdk.init(applicationContext, BuildConfig.KAKAO_NATIVE_KEY) }
            // 카카오 개발자 사이트에 등록할 키 해시 (Logcat 에서 TagoKakao 로 검색)
            if (BuildConfig.DEBUG) android.util.Log.i("TagoKakao", "키 해시: ${com.eunho.tagohub.ui.map.KakaoCanvas.keyHash(this)}")
        }
        // 즐겨찾기·설정을 계정에 맞춰 둠 (로그인·네트워크가 안 되면 조용히 건너뜀)
        com.eunho.tagohub.data.CloudSync.start(applicationContext)
        // 관리자 페이지에서 저장한 배치도·노선 선형: 켤 때 저장본을 바로 읽고, 보이는 동안 주기적으로 새것 확인
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                com.eunho.tagohub.data.RemoteLayouts.init(applicationContext)
                com.eunho.tagohub.data.RemoteShapes.init(applicationContext)
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    com.eunho.tagohub.data.RemoteLayouts.refresh(applicationContext)
                    com.eunho.tagohub.data.RemoteShapes.refresh(applicationContext)
                    delay(REMOTE_EDITS_REFRESH_MS)
                }
            }
        }
        com.eunho.tagohub.data.TrainStationGeo.init(applicationContext)
        com.eunho.tagohub.data.CoachTerminalGeo.init(applicationContext)
        com.eunho.tagohub.data.StopCompanions.init(applicationContext)
        com.eunho.tagohub.data.TrainTimetable.init(applicationContext)
        com.eunho.tagohub.data.AirSchedule.init(applicationContext)
        com.eunho.tagohub.data.SubwayCongestion.init(applicationContext)
        com.eunho.tagohub.data.RailFacilities.init(applicationContext)
        com.eunho.tagohub.data.TripCache.init(filesDir)
        com.eunho.tagohub.data.TrainKinds.init(filesDir)
        lifecycleScope.launch {
            StopDb.get(applicationContext)
            // 같은 자리 타 시·군 정류장 표: 서버(Firebase 등)에 더 새것이 있으면 하루 한 번·와이파이에서 받아 둠
            com.eunho.tagohub.data.StopCompanions.refresh(applicationContext)
            // 정류장·노선 데이터 자동 업데이트 (데이터 저장소, 하루 한 번·와이파이에서만)
            com.eunho.tagohub.data.DataUpdater.check(applicationContext)
        }
        // 잘 안 바뀌는 정보(노선 모양·정류장 순서·노선 정보 등)를 저장해 두는 곳
        com.eunho.tagohub.data.StaticCache.init(filesDir, appVersionCode())
        com.eunho.tagohub.data.OverlaySnapshot.init(filesDir)
        com.eunho.tagohub.data.ArrivalLog.init(filesDir)
        com.eunho.tagohub.data.SegmentTimes.init(filesDir)
        // 지난 운행 기록을 중계 서버에도 모아 다른 사용자들과 함께 씀 (서버를 안 쓰면 아무것도 안 함)
        com.eunho.tagohub.data.ArrivalLog.onHits = com.eunho.tagohub.data.SharedArrivalLog::enqueue
        com.eunho.tagohub.data.DriveLog.init(filesDir)
        com.eunho.tagohub.guidance.DriveGuidance.loadPrefs(applicationContext)
        com.eunho.tagohub.ui.theme.ThemeSettings.load(applicationContext)
        com.eunho.tagohub.data.RecentDestinations.load(applicationContext)
        com.eunho.tagohub.data.SearchHistory.load(applicationContext)
        com.eunho.tagohub.data.RecentItems.load(applicationContext)
        com.eunho.tagohub.data.RouteHistory.load(applicationContext)
        com.eunho.tagohub.data.VisitLog.load(applicationContext)
        com.eunho.tagohub.data.NightSettings.load(applicationContext)
        com.eunho.tagohub.data.VehicleSettings.load(applicationContext)
        takeEmailLink(intent)
        setContent { TagoTheme { MainScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeEmailLink(intent)
    }

    /** 메일의 로그인 링크를 눌러 앱이 열렸으면 링크를 넘겨서 로그인을 마무리하게 함 */
    private fun takeEmailLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (com.eunho.tagohub.data.CloudAccount.isEmailLink(link)) com.eunho.tagohub.data.CloudAccount.emailLink.value = link
    }

    /** 앱 버전 번호 (업데이트되면 저장해 둔 노선 정보를 새로 받기 위해) */
    private fun appVersionCode(): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)
}
