package com.eunho.tagohub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 경로 안내·자유주행이 달리는 동안 알려 줄 도로 위험·시설의 전국 목록.
 * 상습 결빙구간(앱에 든 자료), 도로 위험 예보·교통안전 지점(UTIC), 재난·주의운전(ITS), 톨게이트(한국도로공사).
 * 어느 쪽이 받아지지 않아도 그 종류만 빠지고 나머지는 그대로 쓴다. 한 번 받은 목록은 메모리에 두고 다시 쓴다.
 */
object RoadHazards {
    @Volatile private var cache: Pair<Long, List<RoadHazard>>? = null
    private const val TTL_MS = 30 * 60_000L
    private const val PART_TIMEOUT_MS = 45_000L

    private fun fresh(): List<RoadHazard>? = cache?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.second

    suspend fun load(context: Context): List<RoadHazard> {
        fresh()?.let { return it }
        val app = context.applicationContext
        val list = coroutineScope {
            val parts = listOf(
                async { part { ice(app) } },
                async { part { tolls() } },
                async { part { risks() } },
                async { part { itsLayer("its_disasters", RoadAlertKind.Disaster, "재난 정보") } },
                async { part { itsLayer("its_safe_driving", RoadAlertKind.Caution, "주의운전 구간") } },
                async { part { safety(app) } },
            )
            parts.flatMap { it.await() }
        }
        // 모두 비면(네트워크 등) 저장하지 않아 다음에 다시 시도
        if (list.isNotEmpty()) cache = System.currentTimeMillis() to list
        return list
    }

    private suspend fun part(block: suspend () -> List<RoadHazard>): List<RoadHazard> =
        runCatching { withTimeoutOrNull(PART_TIMEOUT_MS) { block() } }.getOrNull().orEmpty()

    private suspend fun ice(app: Context): List<RoadHazard> = withContext(Dispatchers.IO) {
        app.assets.open("its_ice_sections.csv").bufferedReader(java.nio.charset.Charset.forName("MS949")).use {
            ItsRoadParser.iceSections(it.readText())
        }.map { RoadHazard(RoadAlertKind.Ice, "상습 결빙 구간", null, it.lat, it.lng, it.id) }
    }

    private suspend fun tolls(): List<RoadHazard> =
        ExMapLayers.features(ExMapLayers.TOLL).map { f ->
            val t = f.title.trim()
            RoadHazard(RoadAlertKind.Toll, if (t.endsWith("톨게이트") || t.endsWith("영업소")) t else "$t 톨게이트", null, f.lat, f.lng, f.id)
        }

    private suspend fun risks(): List<RoadHazard> =
        UticRoadRiskRepository().roadRisks().map {
            RoadHazard(RoadAlertKind.Risk, "도로 위험 예보 구간", it.grade?.let { g -> "위험 등급 $g" }, it.lat, it.lng, it.id)
        }

    private suspend fun safety(app: Context): List<RoadHazard> =
        UticSafetyRepository(app.cacheDir).safetyPoints().map {
            RoadHazard(RoadAlertKind.Safety, "교통안전 지점", it.description.takeIf { d -> d.isNotBlank() }, it.lat, it.lng, it.id)
        }

    /** ITS 레이어를 전국 범위로 (집 PC 가 모아 둔 저장본이 있으면 그것, 없으면 ITS 호출) */
    private suspend fun itsLayer(layer: String, kind: RoadAlertKind, name: String): List<RoadHazard> =
        ItsRoadRepository().features(layer, 33.0, 124.5, 38.7, 131.9).map {
            RoadHazard(kind, name, it.title.takeIf { t -> t.isNotBlank() && t != name }, it.lat, it.lng, it.id)
        }
}
