package com.managerdownloader.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Native, zero-extra-dependency remuxer for already encoded YouTube/DASH tracks.
 * It never transcodes. Compatible video/audio codecs are copied into MP4 or WebM.
 */
object NativeMediaMuxerEngine {
    fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        container: String,
        shouldStop: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = runCatching {
        require(videoFile.isFile && videoFile.length() > 0L) { "Pista de video vacía" }
        require(audioFile.isFile && audioFile.length() > 0L) { "Pista de audio vacía" }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0 || audioTrack < 0) {
                throw IOException("No se encontraron pistas válidas de video y audio")
            }

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()

            val normalizedContainer = container.lowercase()
            val outputFormat = when (normalizedContainer) {
                "webm" -> {
                    val videoOk = videoMime in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")
                    val audioOk = audioMime in setOf("audio/opus", "audio/vorbis")
                    if (!videoOk || !audioOk) {
                        throw IOException("Las pistas no son compatibles con WebM ($videoMime + $audioMime)")
                    }
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                }
                else -> {
                    val videoOk = videoMime in setOf("video/avc", "video/hevc", "video/mp4v-es", "video/av01")
                    val audioOk = audioMime in setOf("audio/mp4a-latm", "audio/mpeg")
                    if (!videoOk || !audioOk) {
                        throw IOException("Las pistas no son compatibles con MP4 ($videoMime + $audioMime)")
                    }
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                }
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists() && !outputFile.delete()) {
                throw IOException("No se pudo preparar el archivo de salida")
            }

            val activeMuxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            muxer = activeMuxer
            val outputVideoTrack = activeMuxer.addTrack(videoFormat)
            val outputAudioTrack = activeMuxer.addTrack(audioFormat)
            activeMuxer.start()
            muxerStarted = true

            val bufferCapacity = maxOf(
                4 * 1024 * 1024,
                maxInputSize(videoFormat),
                maxInputSize(audioFormat)
            ).coerceAtMost(32 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufferCapacity)
            val info = MediaCodec.BufferInfo()

            val videoDuration = durationUs(videoFormat)
            val audioDuration = durationUs(audioFormat)
            val totalDuration = maxOf(videoDuration, audioDuration, 1L)
            var videoDone = false
            var audioDone = false
            var lastVideoPts = -1L
            var lastAudioPts = -1L
            var lastReported = 0f

            while (!videoDone || !audioDone) {
                if (shouldStop()) throw IOException("Fusionado detenido")
                val videoPts = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val audioPts = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                if (videoPts < 0L && audioPts < 0L) break

                val writeVideo = !videoDone && (audioDone || audioPts < 0L || (videoPts >= 0L && videoPts <= audioPts))
                val extractor = if (writeVideo) videoExtractor else audioExtractor
                val track = if (writeVideo) outputVideoTrack else outputAudioTrack

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (writeVideo) videoDone = true else audioDone = true
                    continue
                }
                if (size > buffer.capacity()) {
                    throw IOException("Muestra multimedia demasiado grande para fusionar de forma segura")
                }

                var pts = extractor.sampleTime.coerceAtLeast(0L)
                if (writeVideo) {
                    if (pts <= lastVideoPts) pts = lastVideoPts + 1L
                    lastVideoPts = pts
                } else {
                    if (pts <= lastAudioPts) pts = lastAudioPts + 1L
                    lastAudioPts = pts
                }

                info.set(0, size, pts, extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                activeMuxer.writeSampleData(track, buffer, info)
                extractor.advance()

                val current = (maxOf(lastVideoPts, lastAudioPts, 0L).toDouble() / totalDuration.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                if (current - lastReported >= 0.01f || current >= 1f) {
                    lastReported = current
                    onProgress(current)
                }
            }

            onProgress(1f)
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix, ignoreCase = true)) return index
        }
        return -1
    }

    private fun maxInputSize(format: MediaFormat): Int = runCatching {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
    }.getOrDefault(0).coerceAtLeast(0)

    private fun durationUs(format: MediaFormat): Long = runCatching {
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    }.getOrDefault(0L).coerceAtLeast(0L)
}
