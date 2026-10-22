package com.eunho.tagohub.guidance

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 하차 알림의 공유 상태. 화면(상태 카드·버튼)과 포그라운드 서비스([AlightService])가 이 객체로 만난다.
 * 서비스가 GPS 로 내릴 곳까지의 거리를 재서 여기에 올리고, 화면은 보기만 한다.
 */
object AlightAlert {
    /** 내릴 곳: [name] 정류장/역 이름, [routeNo] 타고 있는 노선(모르면 null) */
    data class Target(val name: String, val lat: Double, val lng: Double, val subway: Boolean, val routeNo: String? = null) {
        /** 같은 곳인지 (이름과 좌표가 거의 같으면) */
        fun same(o: Target?): Boolean =
            o != null && name == o.name && kotlin.math.abs(lat - o.lat) < 1e-4 && kotlin.math.abs(lng - o.lng) < 1e-4
    }

    /** 알림 중인 내릴 곳 (없으면 null) */
    val target = MutableStateFlow<Target?>(null)

    /** 내릴 곳까지 직선거리(m). 위치를 아직 못 받았으면 null */
    val distance = MutableStateFlow<Double?>(null)

    /** 사용자에게 보여줄 안내 문구 (예: 위치 권한 없음, GPS 꺼짐) */
    val note = MutableStateFlow<String?>(null)

    /** 음성으로도 알릴지 (끄면 진동·알림만) */
    val voiceOn = MutableStateFlow(true)

    /** 시작. 앱이 화면에 보이는 상태에서 불러야 위치 서비스를 시작할 수 있음 */
    fun start(context: Context, t: Target) {
        target.value = t
        distance.value = null
        note.value = null
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlightService::class.java).setAction(AlightService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, AlightService::class.java))
        target.value = null
        distance.value = null
    }
}
