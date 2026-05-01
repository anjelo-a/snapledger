package com.snapledger.core.network

import com.snapledger.BuildConfig

object NetworkConfig {
    const val DEFAULT_DEBUG_BACKEND_BASE_URL = "http://10.0.2.2:8000/"

    val backendBaseUrl: String
        get() = BuildConfig.BACKEND_BASE_URL
}
