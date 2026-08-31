package com.wakewindow.app.data.remote.nws

import com.wakewindow.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

object NwsConfig {
    const val BASE_URL = "https://api.weather.gov/"

    /** NWS asks API clients to identify themselves - see docs/DATA_SOURCES.md. Never hardcode
     * a personal contact; it's supplied at build time (see app/build.gradle.kts). */
    private fun userAgent(): String = "${BuildConfig.NWS_USER_AGENT_APP} (${BuildConfig.NWS_CONTACT_IDENTIFIER})"

    private object UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent())
                .header("Accept", "application/geo+json")
                .build()
            return chain.proceed(request)
        }
    }

    fun service(): NwsService {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(UserAgentInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(NwsService::class.java)
    }
}
