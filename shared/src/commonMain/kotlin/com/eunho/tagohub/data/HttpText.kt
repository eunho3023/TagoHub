package com.eunho.tagohub.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

/** 서버가 2xx 가 아닌 답을 줌. [body] 에 공공데이터 게이트웨이 오류 XML 이 들어 있는 경우가 많다 */
class HttpStatusException(val code: Int, val body: String) : Exception("HTTP $code")

/**
 * [base] 뒤에 [path] 를 붙이고 [query] (값이 null 인 것은 뺌)를 달아 GET → 본문 글자. 2xx 가 아니면 [HttpStatusException].
 * [path] 의 '/' 는 그대로 경로 구분으로 쓴다.
 */
suspend fun HttpClient.getText(
    base: String,
    path: String = "",
    query: List<Pair<String, String?>> = emptyList(),
    headers: Map<String, String> = emptyMap(),
): String {
    val url = URLBuilder(base).apply {
        if (path.isNotEmpty()) appendPathSegments(path.split('/').filter { it.isNotEmpty() })
        // 공백은 %20 으로 (Ktor 기본 "+" 를 공백으로 안 읽는 서버가 있음, 예전 Retrofit 과 같게)
        for ((k, v) in query) if (v != null) encodedParameters.append(k.encodeURLParameter(), v.encodeURLParameter())
    }.buildString()
    val res = get(url) { headers.forEach { (k, v) -> header(k, v) } }
    val text = res.bodyAsText()
    if (!res.status.isSuccess()) throw HttpStatusException(res.status.value, text)
    return text
}
