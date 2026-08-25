package com.managerdownloader.app.browser

import java.util.Locale

/**
 * Lightweight media-sniffer filter for noisy modern web pages.
 *
 * Important: [canonicalMediaUrl] is a DEDUPLICATION KEY only. The original URL
 * must still be used for the real download because CDN signatures and tokens
 * can depend on the untouched query string.
 */
object MediaSnifferFilter {
    private val ignoredExtensions = setOf(
        ".ts", ".m4s", ".cmfv", ".cmfa",
        ".vtt", ".srt", ".key",
        ".ico", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp"
    )

    private val trackingPatterns = listOf(
        "analytics", "telemetry", "beacon", "pixel", "tracker", "log_event",
        "collect?", "/collect", "metrics", "measurement"
    )

    private val fragmentQueryParameters = setOf(
        "range", "bytes", "start", "end"
    )

    fun isCleanMediaCandidate(url: String, contentLength: Long? = null): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_URL_LENGTH) return false

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("data:") || lower.startsWith("javascript:")) return false

        val cleanUrl = lower.substringBefore('?').substringBefore('#')
        if (ignoredExtensions.any { cleanUrl.endsWith(it) }) return false
        if (trackingPatterns.any { lower.contains(it) }) return false

        // Only use the size threshold when a caller actually knows Content-Length.
        // Unknown/chunked responses must not be rejected just because the size is absent.
        if (contentLength != null && contentLength in 1..MIN_USEFUL_MEDIA_BYTES) return false

        return true
    }

    /**
     * Normalizes only known range/fragment query parameters for deduplication.
     * All other query fields (signatures, expiry, auth tokens, etc.) are preserved.
     */
    fun canonicalMediaUrl(url: String): String {
        val noFragment = url.substringBefore('#')
        val queryIndex = noFragment.indexOf('?')
        if (queryIndex < 0) return noFragment

        val base = noFragment.substring(0, queryIndex)
        val rawQuery = noFragment.substring(queryIndex + 1)
        if (rawQuery.isBlank()) return base

        val kept = rawQuery
            .split('&')
            .filter { part ->
                val rawName = part.substringBefore('=').trim().lowercase(Locale.US)
                rawName !in fragmentQueryParameters
            }

        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    fun hasFragmentRangeParameters(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return false
        return query.split('&').any { part ->
            part.substringBefore('=').trim().lowercase(Locale.US) in fragmentQueryParameters
        }
    }

    private const val MIN_USEFUL_MEDIA_BYTES = 150_000L
    private const val MAX_URL_LENGTH = 8_192
}
