package com.eunho.tagohub.core

/** 레포지토리 결과. (TagoTransit 의 TagoResult 와 동일한 형태) */
sealed class TagoResult<out T> {
    data class Success<T>(val data: T) : TagoResult<T>()
    data class Error(val message: String) : TagoResult<Nothing>()
}

inline fun <T, R> TagoResult<T>.map(f: (T) -> R): TagoResult<R> = when (this) {
    is TagoResult.Success -> TagoResult.Success(f(data))
    is TagoResult.Error -> this
}

/** UI 상태 */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Fail(val message: String) : Load<Nothing>
}

fun <T> TagoResult<T>.toLoad(): Load<T> = when (this) {
    is TagoResult.Success -> Load.Ok(data)
    is TagoResult.Error -> Load.Fail(message)
}

fun <T> Load<T>.valueOrNull(): T? = (this as? Load.Ok)?.value
