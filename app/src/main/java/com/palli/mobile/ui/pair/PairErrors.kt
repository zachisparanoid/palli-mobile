package com.palli.mobile.ui.pair

import com.palli.mobile.data.remote.ApiError

object PairErrors {
    const val ADDRESS_HINT = "Enter PaLLi's address, like http://your-host.your-tailnet.ts.net:8000."
    const val NOT_PALLI = "That address answered, but it isn't PaLLi."

    /** A server that is not PaLLi answers with a web page (or nothing); PaLLi's own refusals are `{"detail": …}` sentences. */
    private fun notPaLLi(detail: String) = detail.isBlank() || detail.trimStart().startsWith("<")

    fun message(error: Throwable, baseUrl: String): String = when (error) {
        is ApiError.Unauthorized -> "That code is not one PaLLi made. Check it, or make a new one on the door page."
        is ApiError.Gone -> "That code expired or was already used. Make a new one on the door page."
        is ApiError.RateLimited -> "Too many tries. Wait a minute, then try again."
        is ApiError.Forbidden -> "PaLLi refused this address. Use her tailnet name or IP — SETUP.md, the phone app section."
        is ApiError.Unreachable -> "Can't reach PaLLi at $baseUrl. Is Tailscale on, and is she running?"
        is ApiError.Rejected -> if (notPaLLi(error.detail)) NOT_PALLI else error.detail
        is ApiError.Server -> if (notPaLLi(error.detail)) NOT_PALLI else "Pairing failed: ${error.detail}"
        // Retrofit refusing the address ("Invalid URL port", "baseUrl must end in /"): the address was not usable.
        is IllegalArgumentException -> ADDRESS_HINT
        else -> "Pairing failed: ${error.message ?: error.javaClass.simpleName}"
    }
}
