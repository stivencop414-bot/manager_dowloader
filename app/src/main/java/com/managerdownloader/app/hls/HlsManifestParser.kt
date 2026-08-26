package com.managerdownloader.app.hls

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class HlsVariantStream(
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val url: String
) {
    val qualityLabel: String
        get() = height?.takeIf { it > 0 }?.let { "${it}p" }
            ?: when {
                bandwidth >= 12_000_000L -> "2160p aprox."
                bandwidth >= 6_000_000L -> "1080p aprox."
                bandwidth >= 3_000_000L -> "720p aprox."
                bandwidth >= 1_200_000L -> "480p aprox."
                else -> "Calidad adaptativa"
            }
}

object HlsManifestParser {
    fun parseMasterPlaylist(rawContent: String, manifestUrl: String): List<HlsVariantStream> {
        val baseUri = runCatching { URI.create(manifestUrl) }.getOrNull()
        val lines = rawContent.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = mutableListOf<HlsVariantStream>()
        var pending: Map<String, String>? = null

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pending = parseAttributes(line.substringAfter(':'))
                }
                !line.startsWith("#") && pending != null -> {
                    val attrs = pending.orEmpty()
                    val resolution = attrs["RESOLUTION"]?.lowercase()?.split('x')
                    val width = resolution?.getOrNull(0)?.toIntOrNull()
                    val height = resolution?.getOrNull(1)?.toIntOrNull()
                    val resolved = runCatching {
                        val candidate = URI.create(line)
                        when {
                            candidate.isAbsolute -> candidate.toString()
                            baseUri != null -> baseUri.resolve(candidate).toString()
                            else -> line
                        }
                    }.getOrDefault(line)
                    variants += HlsVariantStream(
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = width,
                        height = height,
                        codecs = attrs["CODECS"],
                        url = resolved
                    )
                    pending = null
                }
            }
        }

        return variants
            .distinctBy { it.url }
            .sortedWith(compareByDescending<HlsVariantStream> { it.height ?: 0 }.thenByDescending { it.bandwidth })
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val regex = Regex("""([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))""", RegexOption.IGNORE_CASE)
        return regex.findAll(value).associate { match ->
            match.groupValues[1].uppercase() to match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
        }
    }
}

object HlsManifestClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun analyze(
        url: String,
        cookie: String? = null,
        userAgent: String? = null,
        referer: String? = null
    ): Result<List<HlsVariantStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(url).get()
            cookie?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            userAgent?.takeIf(String::isNotBlank)?.let { builder.header("User-Agent", it) }
            referer?.takeIf(String::isNotBlank)?.let { builder.header("Referer", it) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} al analizar HLS")
                val body = response.body?.string().orEmpty()
                if (!body.contains("#EXTM3U", ignoreCase = true)) error("La respuesta no es un manifiesto HLS")
                HlsManifestParser.parseMasterPlaylist(body, response.request.url.toString())
            }
        }
    }
}
