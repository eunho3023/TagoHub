package com.eunho.tagohub.guidance

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eunho.tagohub.data.KakaoDriveRoute
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.NavState
import com.eunho.tagohub.data.NavText
import com.eunho.tagohub.data.TurnKind
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 경로 안내의 공유 상태. 화면(지도 위 배너)과 포그라운드 서비스([DriveGuidanceService])가 이 객체로 만난다.
 * 서비스가 위치를 받아 엔진을 돌리고 결과를 여기에 올리면, 화면은 보기만 한다.
 */
object DriveGuidance {
    /** [speedKmh] 는 지금 속도, [zone] 은 앞에 있는 단속카메라·어린이 보호구역 정보(없으면 null) */
    data class Info(
        val state: NavState,
        val lat: Double,
        val lng: Double,
        val speedKmh: Int = 0,
        val zone: com.eunho.tagohub.data.ZoneStatus? = null,
    )

    /** 안내 중인지 */
    val active = MutableStateFlow(false)

    /** 가장 최근 안내 상태 (안내 중이 아니면 null) */
    val info = MutableStateFlow<Info?>(null)

    /** 지금 따라가는 경로. 재탐색하면 새 경로로 바뀌어 화면이 지도에 다시 그림 */
    val route = MutableStateFlow<KakaoDriveRoute?>(null)

    /** 음성 안내 켜기·끄기 (끄면 화면·알림 안내만) */
    val voiceOn = MutableStateFlow(true)

    /** 안내받을 안전 지점 종류 (설정에서 켜고 끔, 기기에 저장) */
    val zoneGroups = MutableStateFlow(com.eunho.tagohub.data.ZoneGroup.entries.toSet())

    private const val PREFS = "drive_guidance"
    private const val KEY_OFF_GROUPS = "off_groups"

    /** 앱을 켤 때 저장해 둔 설정을 읽음 */
    fun loadPrefs(context: Context) {
        val off = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_OFF_GROUPS, emptySet()).orEmpty()
        zoneGroups.value = com.eunho.tagohub.data.ZoneGroup.entries.filter { it.name !in off }.toSet()
    }

    fun setZoneGroup(context: Context, group: com.eunho.tagohub.data.ZoneGroup, on: Boolean) {
        val next = if (on) zoneGroups.value + group else zoneGroups.value - group
        zoneGroups.value = next
        val off = com.eunho.tagohub.data.ZoneGroup.entries.filter { it !in next }.map { it.name }.toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_OFF_GROUPS, off).apply()
    }

    /** 안내음성 크기 0.0~1.0 */
    val voiceVolume = MutableStateFlow(1f)

    /** 사용자에게 보여줄 알림 문구 (예: 한국어 음성이 없어 소리 없이 안내) */
    val note = MutableStateFlow<String?>(null)

    /** 모의주행 중인지 (GPS 대신 경로를 따라 가상 위치를 만듦). 소리·알림·재탐색은 실제와 같은 길을 거침 */
    val simulating = MutableStateFlow(false)

    /** 모의주행 배속 (1·5·15) */
    val simFactor = MutableStateFlow(5)

    /** 값이 바뀔 때마다 모의주행 차량을 6초 동안 경로에서 300m 벗어나게 함(이탈·재탐색 시험) */
    val deviate = MutableStateFlow(0)

    @Volatile var destination: LatLng? = null
        internal set

    @Volatile var destinationName: String = ""
        internal set

    /** 안내 시작. 화면이 보이는 상태에서 불러야 위치 서비스를 시작할 수 있음 */
    fun start(context: Context, route: KakaoDriveRoute, dest: LatLng, destName: String, simulate: Boolean = false) {
        this.route.value = route
        simulating.value = simulate
        destination = dest
        destinationName = destName
        note.value = null
        active.value = true
        ContextCompat.startForegroundService(
            context,
            Intent(context, DriveGuidanceService::class.java).setAction(DriveGuidanceService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, DriveGuidanceService::class.java))
        active.value = false
        simulating.value = false
    }
}

/** 알림·배너에 보여줄 한 줄 문구 (순수 함수라 테스트할 수 있음) */
object GuidanceText {
    fun title(st: NavState): String = when {
        st.arrived -> "목적지에 도착했습니다"
        st.offRoute -> "경로를 다시 찾는 중"
        st.nextGuide == null -> "경로 안내 중"
        st.nextGuide.isDestination -> "${NavText.short(st.distanceToNext)} 앞 목적지"
        else -> "${NavText.short(st.distanceToNext)} 앞 · ${TurnKind.text(st.nextGuide)}"
    }

    /** "남은 42.0km · 약 55분 · 도착 18:20" */
    fun detail(st: NavState, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String =
        "남은 ${NavText.short(st.remainingMeters)} · 약 ${(st.remainingSeconds + 59) / 60}분 · 도착 ${eta(nowMs, st.remainingSeconds, tz)}"

    fun eta(nowMs: Long, seconds: Int, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.KOREA).apply { timeZone = tz }.format(nowMs + seconds * 1000L)

    /** 하단 바에 크게 보이는 도착 시각: "오전 12:37" */
    fun etaLabel(nowMs: Long, seconds: Int, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("a h:mm", Locale.KOREA).apply { timeZone = tz }.format(nowMs + seconds * 1000L)

    /** 걸리는 시간: 55분 / 2시간 / 2시간 10분 (초는 올림해서 분으로) */
    fun duration(seconds: Int): String {
        val minutes = (seconds.coerceAtLeast(0) + 59) / 60
        return when {
            minutes < 60 -> "${minutes}분"
            minutes % 60 == 0 -> "${minutes / 60}시간"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }
    }
}
