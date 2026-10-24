package com.eunho.tagohub.guidance

import android.content.Context
import android.location.Location
import com.eunho.tagohub.core.AppState
import com.eunho.tagohub.core.HeadingMath
import com.eunho.tagohub.core.HeadingSensor
import com.eunho.tagohub.data.FreeDrive
import com.eunho.tagohub.data.FreeDriveAnnouncer
import com.eunho.tagohub.data.RoadHazard
import com.eunho.tagohub.data.RoadLinkIndex
import com.eunho.tagohub.data.SpeedLimitWatch
import com.eunho.tagohub.data.RoadHazards
import com.eunho.tagohub.data.SafetyZone
import com.eunho.tagohub.data.SafetyZones
import com.eunho.tagohub.ui.bus.startLocationUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 목적지 없이 달리는 자유주행. 내비게이션 탭과 지도 화면이 같은 상태를 보도록 앱 전체에서 하나만 둔다.
 * 속도·방향(GPS 방향 + 나침반)과 앞쪽 단속카메라·어린이 보호구역·결빙·위험 예보·재난·주의운전·교통안전·톨게이트를 알려 준다.
 * 앱이 화면에 보이는 동안만 위치를 받는다 (백그라운드 위치 권한을 쓰지 않으려고. 화면이 꺼지면 멈추고 다시 켜지면 이어감).
 */
object FreeDriveSession {
    data class Snapshot(
        val lat: Double,
        val lng: Double,
        val speedKmh: Int,
        /** 가는 방향(도, 북 0·동 90). 아직 모르면 null */
        val heading: Double?,
        val zones: List<FreeDrive.Ahead>,
        val hazards: List<FreeDrive.HazardAhead>,
        /** 지금 도로의 제한속도(표준 노드·링크, 모르면 null)와 도로 전체 차로 수(모르면 0) */
        val roadLimitKmh: Int? = null,
        val lanes: Int = 0,
    )

    val active = MutableStateFlow(false)
    val state = MutableStateFlow<Snapshot?>(null)
    val voiceOn = MutableStateFlow(true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var voice: GuidanceVoice? = null
    private var compass: HeadingSensor? = null
    private var stopUpdates: () -> Unit = {}
    private val announcer = FreeDriveAnnouncer()

    @Volatile private var zones: List<SafetyZone> = emptyList()
    @Volatile private var links: RoadLinkIndex? = null
    private val speedWatch = SpeedLimitWatch()
    @Volatile private var hazards: List<RoadHazard> = emptyList()
    @Volatile private var heading: Double? = null
    @Volatile private var gpsAt = 0L
    @Volatile private var speedKmh = 0
    @Volatile private var fix: Pair<Double, Double>? = null

    fun start(context: Context) {
        if (active.value) return
        active.value = true
        val app = context.applicationContext
        announcer.forget()
        voice = GuidanceVoice(app)
        compass = HeadingSensor(app) { c ->
            // 달릴 때는 GPS 방향이 더 정확하므로 최근 GPS 방향이 없을 때만 나침반을 씀
            if (System.currentTimeMillis() - gpsAt > HeadingMath.GPS_MAX_AGE_MS) {
                heading = c
                refresh()
            }
        }
        job = scope.launch {
            launch { zones = SafetyZones.load(app) }
            launch { hazards = RoadHazards.load(app) }
            launch { links = RoadLinkIndex.get(app) }
            // 앱이 보일 때만 위치·나침반을 받음
            AppState.foreground.collect { fg ->
                if (fg) {
                    compass?.start()
                    stopUpdates()
                    stopUpdates = startLocationUpdates(app, ::onLocation)
                } else {
                    stopUpdates(); stopUpdates = {}
                    compass?.stop()
                    announcer.forget()
                }
            }
        }
    }

    private fun onLocation(loc: Location) {
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        compass?.setLocation(loc.latitude, loc.longitude)
        if (HeadingMath.gpsUsable(loc.hasBearing(), speed)) {
            gpsAt = System.currentTimeMillis()
            heading = HeadingMath.smooth(heading, loc.bearing.toDouble(), alpha = 0.5)
        }
        speedKmh = (speed * 3.6f).toInt()
        fix = loc.latitude to loc.longitude
        refresh()
    }

    private fun refresh() {
        val pos = fix ?: return
        val h = heading
        scope.launch {
            // 안내 설정에서 끈 종류는 화면에도 말에도 빼고, 지점과 위험은 더 가까운 것부터 말함
            val on = DriveGuidance.zoneGroups.value
            val facilityOn = com.eunho.tagohub.data.ZoneGroup.Facility in on
            val (z, hz) = withContext(Dispatchers.Default) {
                if (h == null) emptyList<FreeDrive.Ahead>() to emptyList()
                else FreeDrive.zonesAhead(pos.first, pos.second, h, zones).filter { it.zone.kind.group in on }.take(3) to
                    (if (facilityOn) FreeDrive.hazardsAhead(pos.first, pos.second, h, hazards).take(3) else emptyList())
            }
            val road = withContext(Dispatchers.Default) { links?.find(pos.first, pos.second, h) }
            if (!active.value) return@launch
            state.value = Snapshot(pos.first, pos.second, speedKmh, h, z, hz, road?.speedKmh, road?.lanes ?: 0)
            if (voiceOn.value) {
                val zone = z.firstOrNull()
                val hazard = hz.firstOrNull()
                val zoneFirst = zone != null && (hazard == null || zone.distance <= hazard.distance)
                val spoken = if (zoneFirst) zone?.let { announcer.speakFor(it) } ?: hazard?.let { announcer.speakForHazard(it) }
                else hazard?.let { announcer.speakForHazard(it) } ?: zone?.let { announcer.speakFor(it) }
                val line = spoken ?: speedWatch.update(speedKmh, road?.speedKmh, System.currentTimeMillis())
                if (line != null) voice?.speak(line)
            }
        }
    }

    fun stop() {
        if (!active.value) return
        active.value = false
        job?.cancel()
        job = null
        stopUpdates(); stopUpdates = {}
        compass?.stop(); compass = null
        voice?.shutdown(); voice = null
        state.value = null
        heading = null
        gpsAt = 0L
        speedKmh = 0
        fix = null
    }
}
