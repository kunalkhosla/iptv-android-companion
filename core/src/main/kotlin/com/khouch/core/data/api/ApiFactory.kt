package com.khouch.core.data.api

import com.khouch.core.data.auth.PersistentCookieJar
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

// Builds Retrofit clients against a dynamic base URL. The Android TV
// app can repoint at any Khouch backend, so we don't hard-code the
// base URL — instead, every screen requests an api instance via the
// repository, which builds (or reuses) a Retrofit pointed at the
// currently-stored server URL.
class ApiFactory(private val cookieJar: PersistentCookieJar) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val okhttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401) {
                    cookieJar.clear()
                    AuthEvents.needsLogin.tryEmit(Unit)
                }
                response
            }
            .build()
    }

    @Volatile private var cached: Pair<String, KhouchApi>? = null

    fun api(baseUrl: String): KhouchApi {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        cached?.let { (url, api) -> if (url == normalized) return api }
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okhttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KhouchApi::class.java)
        cached = normalized to api
        return api
    }

    val httpClient: OkHttpClient get() = okhttp
}
