package io.github.seongkyulim.wellnessgame.data.network

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/** debug: 요청 줄과 상태 코드만 로그로 남긴다(본문·Authorization 헤더는 남기지 않음). */
fun buildTypeInterceptors(): List<Interceptor> =
    listOf(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
