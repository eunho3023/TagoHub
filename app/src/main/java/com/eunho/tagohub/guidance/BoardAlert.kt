package com.eunho.tagohub.guidance

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eunho.tagohub.data.BoardRule
import com.eunho.tagohub.data.BusStop
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 승차 알림의 공유 상태. 정류장 화면(종 버튼)과 포그라운드 서비스([BoardService])가 이 객체로 만난다.
 * 서비스가 도착 정보를 20초마다 받아, 기다리는 버스가 [Target.rule] 만큼 가까워지면 알리고 끝낸다.
 */
object BoardAlert {
    /**
     * [stops]: 이 정류장과 같은 자리의 다른 시군 정류장 (그 노선이 어느 쪽 도착 정보에 나오는지 모를 수 있어서 모두 봄)
     */
    data class Target(val stops: List<BusStop>, val stopName: String, val routeId: String, val routeNo: String, val rule: BoardRule)

    val target = MutableStateFlow<Target?>(null)

    /** 진행 중 안내 (예: "7정류장 전 · 12분") */
    val status = MutableStateFlow<String?>(null)

    fun start(context: Context, t: Target) {
        target.value = t
        status.value = null
        ContextCompat.startForegroundService(context, Intent(context, BoardService::class.java).setAction(BoardService.ACTION_START))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, BoardService::class.java))
        target.value = null
        status.value = null
    }
}
