package com.palli.mobile.data.pairing

import java.net.URI
import java.net.URLDecoder

/** What a pairing QR holds: where PaLLi is, and the one-use code (normalised, no dash). */
data class PairingLink(val baseUrl: String, val code: String)

/** `palli://pair?u=<url-encoded address>&c=<code>` — the backend's core/mobile_pairing.pair_link. */
object PairingLinks {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** A bare scheme, with or without its slashes ("http:", "http:/", "https:8000"): half an address, not a host called http. */
    private val bareScheme = Regex("^https?($|[:/])", RegexOption.IGNORE_CASE)

    fun parse(raw: String): PairingLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("palli", ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) return null
        val params = (uri.rawQuery ?: return null).split("&").mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to URLDecoder.decode(part.substring(at + 1), "UTF-8")
        }.toMap()
        val base = normalizeBaseUrl(params["u"] ?: return null) ?: return null
        val code = normalizeCode(params["c"] ?: return null) ?: return null
        return PairingLink(base, code)
    }

    /** Case, spaces and the dash dropped; O/I/L read as 0/1 (Crockford); null unless 8 valid characters. */
    fun normalizeCode(raw: String): String? {
        val cleaned = raw.uppercase()
            .filter { it.isLetterOrDigit() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
            .joinToString("")
        return cleaned.takeIf { it.length == 8 && it.all { c -> c in CROCKFORD } }
    }

    /**
     * `http://` added when there is no scheme; only http(s) with a host and no user-info; trailing slashes dropped;
     * scheme and host lower-cased (both are case-insensitive, so `HTTP://Host` is the address `http://host`).
     * Validate first, trim last: trimming first would eat the slashes of a half-typed `http://`.
     *
     * User-info is refused because `http://palli-box.example.ts.net@100.64.0.7:8000` names the host after the `@`, and a
     * confirm dialog that shows the whole string would look like the first one.
     */
    fun normalizeBaseUrl(raw: String): String? {
        val typed = raw.trim()
        if (typed.isEmpty()) return null
        val withScheme = if (typed.contains("://")) typed else {
            if (bareScheme.containsMatchIn(typed)) return null
            "http://$typed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host
        if (scheme !in setOf("http", "https") || host.isNullOrEmpty() || uri.userInfo != null) return null
        val afterHost = withScheme.substring(scheme.length + "://".length + host.length)
        return "$scheme://${host.lowercase()}$afterHost".trimEnd('/')
    }
}
