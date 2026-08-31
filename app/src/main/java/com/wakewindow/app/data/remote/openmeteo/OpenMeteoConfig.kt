package com.wakewindow.app.data.remote.openmeteo

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

object OpenMeteoConfig {
    const val GENERAL_BASE_URL = "https://api.open-meteo.com/"
    const val MARINE_BASE_URL = "https://marine-api.open-meteo.com/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder().build()

    fun generalService(): OpenMeteoService = Retrofit.Builder()
        .baseUrl(GENERAL_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenMeteoService::class.java)

    fun marineService(): OpenMeteoMarineService = Retrofit.Builder()
        .baseUrl(MARINE_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenMeteoMarineService::class.java)
}
