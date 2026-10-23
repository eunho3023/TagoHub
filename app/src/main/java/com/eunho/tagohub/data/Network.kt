package com.eunho.tagohub.data

import com.eunho.tagohub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Network {
    private const val BASE_URL = "https://apis.data.go.kr/1613000/"

    private val rawKey: String = BuildConfig.TAGO_SERVICE_KEY.trim()
    val hasKey: Boolean get() = rawKey.isNotEmpty()

    /**
     * Encoding 키(%2F 포함)는 그대로, Decoding 키(+, / 포함)는 직접 인코딩.
     * Retrofit @Query 에 맡기면 '+' 가 인코딩되지 않아 서버가 공백으로 읽고
     * SERVICE_KEY_IS_NOT_REGISTERED(30) 를 돌려주는 문제가 있다.
     */
    private val encodedKey: String by lazy {
        if ('%' in rawKey) rawKey else URLEncoder.encode(rawKey, "UTF-8")
    }

    private val client: OkHttpClient = Http.builder()
        .protocols(listOf(Protocol.HTTP_1_1)) // data.go.kr 게이트웨이 HTTP/2 오류 회피 (TagoTransit 과 동일)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val url = req.url.newBuilder()
                .addEncodedQueryParameter("serviceKey", encodedKey)
                // 신규 TAGO 서비스(TrainInfo·SubwayInfo·ExpBusInfo 등)는 형식을 지정하지 않으면 JSON 을 준다.
                // 브라우저는 Accept 헤더 덕분에 XML 을 받지만 OkHttp 는 Accept 를 안 보낸다.
                .addQueryParameter("_type", "xml")
                .build()
            chain.proceed(
                req.newBuilder()
                    .url(url)
                    .header("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8")
                    .build()
            )
        }
        .build()

    val api: TagoApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TagoApi::class.java)
}

object Graph {
    val repo: TagoRepository by lazy { TagoRepository(Network.api) }
    val kricSubway: KricSubwayRepository by lazy { KricSubwayRepository.create() }
    val seoul: SeoulSubwayRepository by lazy { SeoulSubwayRepository.create() }
    val jeonju: JeonjuRepository by lazy { JeonjuRepository.create() }
    val jeju: JejuRepository by lazy { JejuRepository.create() }
    val gyeonggi: GyeonggiRepository by lazy { GyeonggiRepository.create() }
    val seoulBus: SeoulBusRepository by lazy { SeoulBusRepository.create() }
    val klidBus: KlidBusRepository by lazy { KlidBusRepository() }
    val regionalBus: RegionalBusRepository by lazy { RegionalBusRepository() }
}
