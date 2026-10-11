package com.example.wellnessgame.data.network

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 서버와 같은 JSON 규칙: 모르는 필드는 무시하고, null 필드는 보내지 않는다(iOS Encodable과 같음). */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 모든 요청에 앱 표시 언어(ko·en)를 `Accept-Language`로 붙여 서버가 메시지를 번역하게 한다. */
class AcceptLanguageInterceptor(private val language: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("Accept-Language", language()).build())
}

object NetworkModule {
    fun okHttpClient(language: () -> String, extra: List<Interceptor> = emptyList()): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AcceptLanguageInterceptor(language))
            .apply { extra.forEach(::addInterceptor) }
            .build()

    fun api(baseUrl: String, client: OkHttpClient): WellnessApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WellnessApi::class.java)
}
