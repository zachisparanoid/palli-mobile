package com.palli.mobile.data.remote

import com.palli.mobile.data.session.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Adds `Authorization: Bearer <token>` — only to the paired server (same scheme, host, port, path prefix). */
class AuthInterceptor(private val currentSession: () -> Session?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = currentSession()
        if (session == null || request.header("Authorization") != null ||
            !belongsTo(request.url.toString(), session.baseUrl)) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer ${session.token}").build())
    }

    companion object {
        fun belongsTo(url: String, baseUrl: String): Boolean {
            val target = url.toHttpUrlOrNull() ?: return false
            val base = baseUrl.toHttpUrlOrNull() ?: return false
            return target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
                target.encodedPath.startsWith(base.encodedPath.trimEnd('/'))
        }
    }
}
