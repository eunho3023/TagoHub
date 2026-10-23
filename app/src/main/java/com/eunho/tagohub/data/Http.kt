package com.eunho.tagohub.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 앱 전체가 함께 쓰는 HTTP 클라이언트의 뿌리. 각 데이터 코드가 자기 클라이언트를 따로 만들던 것(20곳)을
 * `Http.builder()` 로 바꿔서, 연결 풀·스레드를 같이 쓰게 한다 → 같은 서버(카카오·공공데이터포털)로 가는 요청이
 * 이미 맺은 연결(TLS)을 다시 쓰고, 스레드·메모리를 덜 쓴다. 제한 시간·헤더 같은 설정은 쓰는 쪽에서 그대로 덧붙인다.
 */
object Http {
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12   // 기본 5: 한 서버로 가는 요청이 여럿 겹치면 줄 서서 기다리던 것을 완화
        })
        .build()

    /** 공유 연결 풀·스레드를 쓰는 새 빌더 */
    fun builder(): OkHttpClient.Builder = base.newBuilder()
}
