package com.snapledger.core.network

import com.snapledger.BuildConfig
import java.net.URI

object NetworkConfig {
    const val DEFAULT_DEBUG_BACKEND_BASE_URL = "http://10.0.2.2:8000/"

    val backendBaseUrl: String
        get() = BuildConfig.BACKEND_BASE_URL

    val safeBackendBaseUrl: String
        get() {
            val configured = backendBaseUrl.trim()
            val normalized = if (configured.endsWith("/")) configured else "$configured/"
            val parsed = runCatching { URI(normalized) }.getOrNull()
            val isValidHttpUrl = parsed != null &&
                !parsed.scheme.isNullOrBlank() &&
                (parsed.scheme.equals("http", ignoreCase = true) ||
                    parsed.scheme.equals("https", ignoreCase = true)) &&
                !parsed.host.isNullOrBlank()
            return if (isValidHttpUrl) {
                normalized
            } else {
                DEFAULT_DEBUG_BACKEND_BASE_URL
            }
        }
}
