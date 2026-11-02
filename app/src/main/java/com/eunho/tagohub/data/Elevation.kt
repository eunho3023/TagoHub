package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 도보·자전거 경로의 고도 그래프용 지형 높이. Open-Meteo 고도 API(90m 격자 지형 모델, 한 번에 좌표 100개까지)로
 * 경로를 고르게 나눈 점들의 높이를 받는다. 대략적인 지형 높이라 고가도로·지하도·다리 위는 실제와 다를 수 있음.
 */
object Elevation {
    private const val URL = "https://api.open-meteo.com/v1/elevation"

    /** [meters] 는 경로를 따라 고르게 나눈 점의 높이(m). 거리 순서. */
    class Profile(val meters: List<Double>) {
        /** 올라간 총합(m) */
        val ascent: Int get() = meters.zipWithNext { a, b -> (b - a).coerceAtLeast(0.0) }.sum().toInt()
        /** 내려간 총합(m) */
        val descent: Int get() = meters.zipWithNext { a, b -> (a - b).coerceAtLeast(0.0) }.sum().toInt()
        val max: Double get() = meters.max()
        val min: Double get() = meters.min()
    }

    private val client = Http.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Profile>()

    /** 경로를 [samples] 개 점으로 나눠 높이를 받음. 실패하면 null (그래프를 안 보임) */
    suspend fun profile(path: List<LatLng>, samples: Int = 60): Profile? = withContext(Dispatchers.IO) {
        if (path.size < 2) return@withContext null
        val n = samples.coerceIn(2, 100)
        val points = List(n) { Shapes.along(path, it / (n - 1).toDouble()) }
        val key = points.joinToString("|") { String.format(Locale.ROOT, "%.4f,%.4f", it.lat, it.lng) }
        cache[key]?.let { return@withContext it }
        runCatching {
            val url = URL.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", points.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lat) })
                .addQueryParameter("longitude", points.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lng) })
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val root = MiniJson.parse(res.body?.string().orEmpty()) as? Map<*, *> ?: return@use null
                val list = (root["elevation"] as? List<*>)?.map { it?.toString()?.toDoubleOrNull() } ?: return@use null
                if (list.size != n || list.any { it == null }) return@use null
                Profile(list.map { it!! }).also { cache[key] = it }
            }
        }.getOrNull()
    }
}
