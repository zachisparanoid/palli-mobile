package com.palli.mobile.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One Retrofit per server address: the address is only known at pairing time. */
@Singleton
class ApiFactory @Inject constructor(private val client: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, PalliApi>()

    fun forBase(baseUrl: String): PalliApi = cache.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(Wire.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PalliApi::class.java)
    }
}
