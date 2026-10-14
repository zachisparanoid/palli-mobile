package com.palli.mobile.ui.pair

import com.palli.mobile.data.remote.ApiError

object PairErrors {
    fun message(error: Throwable, baseUrl: String): String = when (error) {
        is ApiError.Unauthorized -> "That code is not one PaLLi made. Check it, or make a new one on the door page."
        is ApiError.Gone -> "That code expired or was already used. Make a new one on the door page."
        is ApiError.RateLimited -> "Too many tries. Wait a minute, then try again."
        is ApiError.Forbidden -> "PaLLi refused this address. Use her tailnet name or IP — SETUP.md, the phone app section."
        is ApiError.Unreachable -> "Can't reach PaLLi at $baseUrl. Is Tailscale on, and is she running?"
        is ApiError.Rejected -> error.detail
        else -> "Pairing failed: ${error.message}"
    }
}
