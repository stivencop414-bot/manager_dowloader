package com.managerdownloader.app.youtube

import android.net.Uri
import com.managerdownloader.app.security.SecurityUrlPolicy
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val URL_FINDER = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)

data class YouTubeLink(
    val rawUrl: String,
    val canonicalUrl: String?,
    val videoId: String?,
    val playlistId: String?
) {
    val isVideo: Boolean get() = videoId != null && canonicalUrl != null
}

enum class YouTubeFormatKind {
    VIDEO_WITH_AUDIO,
    VIDEO_ONLY,
    AUDIO_ONLY
}

data class YouTubeFormatOption(
    val id: String,
    val label: String,
    val url: String,
    val filename: String,
    val mimeType: String,
    val kind: YouTubeFormatKind,
    val height: Int = -1,
    val bitrateKbps: Int = -1
)

data class YouTubeMuxedOption(
    val id: String,
    val label: String,
    val videoUrl: String,
    val audioUrl: String,
    val filename: String,
    val videoFormatId: String,
    val audioFormatId: String,
    val container: String,
    val height: Int
)

data class YouTubeVideoDetails(
    val sourceUrl: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val progressiveVideo: List<YouTubeFormatOption>,
    val videoOnly: List<YouTubeFormatOption>,
    val audioOnly: List<YouTubeFormatOption>,
    val muxedHighQuality: List<YouTubeMuxedOption>,
    val warnings: List<String>
)

object YouTubeUrlParser {
    fun findInText(text: String?): YouTubeLink? {
        val clean = text?.trim().orEmpty()
        if (clean.isBlank()) return null
        parse(clean)?.let { return it }
        URL_FINDER.findAll(clean).forEach { match ->
            parse(match.value.trimEnd('.', ',', ')', ']', '}'))?.let { return it }
        }
        return null
    }

    fun parse(value: String?): YouTubeLink? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val normalized = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return null
        val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
        val shortHost = host == "youtu.be"
        if (!youtubeHost && !shortHost) return null

        val segments = uri.pathSegments.filter { it.isNotBlank() }
        val fromQuery = uri.getQueryParameter("v")?.takeIf(YOUTUBE_ID::matches)
        val fromPath = when {
            shortHost -> segments.firstOrNull()
            segments.firstOrNull()?.lowercase(Locale.US) in setOf("shorts", "live", "embed", "v", "e") -> segments.getOrNull(1)
            else -> null
        }?.takeIf(YOUTUBE_ID::matches)
        val videoId = fromQuery ?: fromPath
        val playlistId = uri.getQueryParameter("list")?.takeIf { it.length >= 10 }
        val canonical = videoId?.let { "https://www.youtube.com/watch?v=$it" }
        if (videoId == null && playlistId == null) return null
        return YouTubeLink(raw, canonical, videoId, playlistId)
    }
}

object YouTubeExtractorClient {
    @Volatile private var initialized = false
    private val initLock = Any()

    suspend fun analyze(url: String): Result<YouTubeVideoDetails> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()
            val link = YouTubeUrlParser.parse(url) ?: throw IOException("URL de YouTube no válida")
            val canonical = link.canonicalUrl ?: throw IOException("La URL no corresponde a un video individual")
            val info = StreamInfo.getInfo(ServiceList.YouTube, canonical)
            toDetails(canonical, info)
        }
    }

    internal fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(OkHttpExtractorDownloader())
            initialized = true
        }
    }

    private fun toDetails(url: String, info: StreamInfo): YouTubeVideoDetails {
        val safeTitle = sanitizeFileName(info.name.ifBlank { "YouTube-${info.id}" })
        val progressive = info.videoStreams
            .asSequence()
            .filter { it.isUrl && !it.isVideoOnly && SecurityUrlPolicy.isSafePublicHttps(it.content) }
            .map { videoOption(it, safeTitle, YouTubeFormatKind.VIDEO_WITH_AUDIO) }
            .distinctBy { it.url }
            .sortedByDescending { it.height }
            .toList()

        val videoOnly = info.videoOnlyStreams
            .asSequence()
            .filter { it.isUrl && SecurityUrlPolicy.isSafePublicHttps(it.content) }
            .map { videoOption(it, safeTitle, YouTubeFormatKind.VIDEO_ONLY) }
            .distinctBy { it.url }
            .sortedByDescending { it.height }
            .toList()

        val audio = info.audioStreams
            .asSequence()
            .filter { it.isUrl && SecurityUrlPolicy.isSafePublicHttps(it.content) }
            .map { audioOption(it, safeTitle) }
            .distinctBy { it.url }
            .sortedByDescending { it.bitrateKbps }
            .toList()

        val muxed = buildMuxedOptions(videoOnly, audio, safeTitle)

        val warnings = buildList {
            if (progressive.isEmpty() && muxed.isEmpty() && videoOnly.isNotEmpty()) {
                add("Este video expone pistas separadas, pero no se encontró una combinación compatible para MediaMuxer.")
            }
            if (info.ageLimit > 0) add("El contenido informa restricción por edad (${info.ageLimit}+).")
            if (info.errors.isNotEmpty()) add("El extractor reportó ${info.errors.size} advertencia(s) parcial(es).")
        }

        return YouTubeVideoDetails(
            sourceUrl = url,
            title = info.name,
            uploader = info.uploaderName,
            durationSeconds = info.duration,
            thumbnailUrl = info.thumbnails.lastOrNull()?.url,
            progressiveVideo = progressive,
            videoOnly = videoOnly,
            audioOnly = audio,
            muxedHighQuality = muxed,
            warnings = warnings
        )
    }

    private fun videoOption(stream: VideoStream, title: String, kind: YouTubeFormatKind): YouTubeFormatOption {
        val format = stream.format
        val suffix = format?.suffix?.ifBlank { null } ?: "mp4"
        val height = stream.height.takeIf { it > 0 } ?: stream.resolution.filter(Char::isDigit).toIntOrNull() ?: -1
        val fps = stream.fps.takeIf { it > 0 }
        val label = buildString {
            append(if (stream.resolution.isNotBlank()) stream.resolution else if (height > 0) "${height}p" else "Video")
            fps?.let { append(" · ${it}fps") }
            append(" · ${format?.name ?: suffix.uppercase(Locale.US)}")
            if (kind == YouTubeFormatKind.VIDEO_ONLY) append(" · solo video") else append(" · video + audio")
        }
        return YouTubeFormatOption(
            id = stream.id,
            label = label,
            url = stream.content,
            filename = "$title.${suffix}",
            mimeType = format?.mimeType ?: "video/mp4",
            kind = kind,
            height = height
        )
    }

    private fun audioOption(stream: AudioStream, title: String): YouTubeFormatOption {
        val format = stream.format
        val suffix = format?.suffix?.ifBlank { null } ?: "m4a"
        val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: -1
        val label = buildString {
            append("Audio")
            if (bitrate > 0) append(" · ${bitrate} kbps")
            append(" · ${format?.name ?: suffix.uppercase(Locale.US)}")
        }
        return YouTubeFormatOption(
            id = stream.id,
            label = label,
            url = stream.content,
            filename = "$title - audio.$suffix",
            mimeType = format?.mimeType ?: "audio/mp4",
            kind = YouTubeFormatKind.AUDIO_ONLY,
            bitrateKbps = bitrate
        )
    }

    private fun buildMuxedOptions(
        videoOnly: List<YouTubeFormatOption>,
        audioOnly: List<YouTubeFormatOption>,
        title: String
    ): List<YouTubeMuxedOption> = videoOnly.mapNotNull { video ->
        val container = when {
            video.mimeType.contains("webm", true) || video.filename.endsWith(".webm", true) -> "webm"
            video.mimeType.contains("mp4", true) || video.filename.endsWith(".mp4", true) || video.filename.endsWith(".m4v", true) -> "mp4"
            else -> null
        } ?: return@mapNotNull null

        val audio = audioOnly.firstOrNull { candidate ->
            when (container) {
                "webm" -> candidate.mimeType.contains("webm", true) ||
                    candidate.filename.endsWith(".webm", true) || candidate.filename.endsWith(".opus", true)
                else -> candidate.mimeType.contains("mp4", true) ||
                    candidate.filename.endsWith(".m4a", true) || candidate.filename.endsWith(".mp4", true)
            }
        } ?: return@mapNotNull null

        YouTubeMuxedOption(
            id = "${video.id}+${audio.id}",
            label = buildString {
                append(if (video.height > 0) "${video.height}p" else "Alta calidad")
                append(" · ${container.uppercase(Locale.US)} · video + audio")
            },
            videoUrl = video.url,
            audioUrl = audio.url,
            filename = "$title.$container",
            videoFormatId = video.id,
            audioFormatId = audio.id,
            container = container,
            height = video.height
        )
    }.distinctBy { "${it.height}:${it.container}" }
        .sortedByDescending { it.height }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(150)
        .ifBlank { "YouTube" }
}

private class OkHttpExtractorDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .dns(SecurityUrlPolicy.publicDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val method = request.httpMethod().uppercase(Locale.US)
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(SecurityUrlPolicy.requirePublicHttps(request.url()))
            .method(method, body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            // Explicit Accept-Encoding disables OkHttp transparent gzip. Let OkHttp negotiate
            // and decompress responses so NewPipe always receives readable text/JSON.
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }
        }

        val response = client.newCall(builder.build()).execute()
        try {
            if (response.code == 429) {
                throw ReCaptchaException("YouTube solicitó verificación adicional", request.url())
            }
            val responseText = SecurityUrlPolicy.readUtf8Limited(
                response.body,
                SecurityUrlPolicy.MAX_EXTRACTOR_RESPONSE_BYTES
            )
            return ExtractorResponse(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseText,
                response.request.url.toString()
            )
        } finally {
            response.close()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
    }
}
