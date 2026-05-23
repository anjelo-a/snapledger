package com.snapledger.core.network

import okhttp3.OkHttpClient

object SnapLedgerHttpClient {
    fun builder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val apiKey = NetworkConfig.backendApiKey
                val request = if (apiKey.isBlank()) {
                    chain.request()
                } else {
                    chain.request()
                        .newBuilder()
                        .header("x-api-key", apiKey)
                        .build()
                }
                chain.proceed(request)
            }
    }
}
