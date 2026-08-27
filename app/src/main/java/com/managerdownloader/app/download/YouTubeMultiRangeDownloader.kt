package com.managerdownloader.app.download

import com.managerdownloader.app.data.DownloadTask
import com.managerdownloader.app.security.SecurityUrlPolicy
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal class YouTubeTrackHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

/**
 * Resumable multi-range downloader for extracted YouTube/googlevideo tracks.
 * It intentionally avoids Thread.interrupt()/Future.cancel(true) while FileChannel is writing.
 */
internal object YouTubeMultiRangeDownloader {
    private data class RangeInfo(val start: Long, val end: Long, val total: Long?)
    private data class Segment(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1L
    }

    fun downloadIfSupported(
        client: OkHttpClient,
        task: DownloadTask,
        url: String,
        target: File,
        metaFile: File,
        stableId: String,
        control: TransferControl,
        requestedSegments: Int,
        throttle: (Int) -> Unit,
        networkAllowed: () -> Boolean,
        onProgress: (downloaded: Long, total: Long, speed: Long, connections: Int) -> Unit
    ): Boolean {
        val probe = probe(client, task, url, control)
        if (!probe.first || probe.second <= 0L || control.stopped.get()) return false
        val total = probe.second
        val count = chooseSegmentCount(total, requestedSegments)
        if (count <= 1) return false

        target.parentFile?.mkdirs()
        val segments = createSegments(total, count)
        val progress = prepareState(target, metaFile, total, count, stableId, segments)
        if ((0 until count).all { progress.get(it) >= segments[it].length }) {
            runCatching { metaFile.delete() }
            onProgress(total, total, 0L, count)
            return true
        }

        val executor = Executors.newFixedThreadPool(count)
        val firstError = AtomicReference<Throwable?>(null)
        val lastBytes = AtomicLong((0 until count).sumOf { progress.get(it) })
        val lastTime = AtomicLong(System.currentTimeMillis())
        val lastPersist = AtomicLong(0L)
        val updateLock = Any()
        val futures = mutableListOf<Future<*>>()

        try {
            segments.forEachIndexed { index, segment ->
                futures += executor.submit {
                    if (control.stopped.get()) return@submit
                    try {
                        downloadSegment(
                            client = client,
                            task = task,
                            url = url,
                            target = target,
                            index = index,
                            segment = segment,
                            total = total,
                            progress = progress,
                            control = control,
                            throttle = throttle,
                            networkAllowed = networkAllowed,
                            onBytesWritten = {
                                val now = System.currentTimeMillis()
                                if (now - lastTime.get() >= 500L) {
                                    synchronized(updateLock) {
                                        val previousTime = lastTime.get()
                                        if (now - previousTime >= 500L) {
                                            val downloaded = (0 until count).sumOf { progress.get(it) }
                                            val previousBytes = lastBytes.getAndSet(downloaded)
                                            lastTime.set(now)
                                            val speed = ((downloaded - previousBytes).coerceAtLeast(0L) * 1000L) /
                                                (now - previousTime).coerceAtLeast(1L)
                                            onProgress(downloaded, total, speed, count)
                                            val previousPersist = lastPersist.get()
                                            if (now - previousPersist >= 2_000L &&
                                                lastPersist.compareAndSet(previousPersist, now)
                                            ) {
                                                persistState(metaFile, total, count, stableId, progress)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    } catch (error: Throwable) {
                        if (!control.stopped.get() && firstError.compareAndSet(null, error)) {
                            control.calls.forEach { it.cancel() }
                        }
                    }
                }
            }

            awaitWorkers(futures, control)
            persistState(metaFile, total, count, stableId, progress)
            if (control.stopped.get()) return true
            firstError.get()?.let { throw it }

            segments.forEachIndexed { index, segment ->
                if (progress.get(index) != segment.length) {
                    throw IOException("Segmento YouTube ${index + 1} incompleto")
                }
            }
            if (target.length() != total) {
                throw IOException("La pista YouTube parcial no tiene el tamaño esperado")
            }
            runCatching { metaFile.delete() }
            onProgress(total, total, 0L, count)
            return true
        } finally {
            // shutdown() is cooperative: never interrupt a FileChannel writer.
            executor.shutdown()
        }
    }

    private fun probe(
        client: OkHttpClient,
        task: DownloadTask,
        url: String,
        control: TransferControl
    ): Pair<Boolean, Long> {
        val builder = requestBuilder(task, url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()
        val call = client.newCall(builder.build())
        control.calls.add(call)
        try {
            call.execute().use { response ->
                if (response.code == 403) {
                    throw YouTubeTrackHttpException(403, "HTTP 403 al analizar pista YouTube")
                }
                if (response.code == 429) {
                    throw YouTubeTrackHttpException(429, "HTTP 429 al analizar pista YouTube")
                }
                if (response.code == 200) {
                    val total = response.body?.contentLength()?.takeIf { it > 0L } ?: -1L
                    return false to total
                }
                if (response.code != 206) {
                    throw YouTubeTrackHttpException(response.code, "HTTP ${response.code} al analizar pista YouTube")
                }
                val parsed = parseContentRange(response.header("Content-Range"))
                    ?: return false to -1L
                val total = parsed.total ?: return false to -1L
                // Some CDNs answer bytes=0-0 with 0-N. That is still valid Range support.
                val supported = parsed.start == 0L && parsed.end >= 0L && total > 1L
                return supported to total
            }
        } finally {
            control.calls.remove(call)
        }
    }

    private fun downloadSegment(
        client: OkHttpClient,
        task: DownloadTask,
        url: String,
        target: File,
        index: Int,
        segment: Segment,
        total: Long,
        progress: AtomicLongArray,
        control: TransferControl,
        throttle: (Int) -> Unit,
        networkAllowed: () -> Boolean,
        onBytesWritten: () -> Unit
    ) {
        val existing = progress.get(index).coerceIn(0L, segment.length)
        if (existing >= segment.length) return
        val requestStart = segment.start + existing
        val builder = requestBuilder(task, url)
            .header("Range", "bytes=$requestStart-${segment.end}")
            .header("Accept-Encoding", "identity")
            .get()
        val call = client.newCall(builder.build())
        control.calls.add(call)
        try {
            call.execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    throw YouTubeTrackHttpException(response.code, "HTTP ${response.code} durante descarga YouTube")
                }
                if (response.code != 206) {
                    throw IOException("El CDN dejó de respetar HTTP Range")
                }
                val range = parseContentRange(response.header("Content-Range"))
                    ?: throw IOException("Content-Range inválido en pista YouTube")
                if (range.start != requestStart || range.end < requestStart) {
                    throw IOException("Content-Range no coincide con el inicio del bloque YouTube solicitado")
                }
                if (range.total != null && range.total != total) {
                    throw IOException("El tamaño de la pista YouTube cambió")
                }

                val body = response.body ?: throw IOException("Respuesta vacía de pista YouTube")
                RandomAccessFile(target, "rw").use { output ->
                    val channel = output.channel
                    var position = requestStart
                    val buffer = ByteArray(256 * 1024)
                    body.byteStream().use { input ->
                        while (!control.stopped.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            if (!networkAllowed()) break
                            val before = progress.get(index)
                            val remaining = (segment.length - before).coerceAtLeast(0L)
                            if (remaining <= 0L) break
                            val accepted = minOf(read.toLong(), remaining).toInt()
                            throttle(accepted)
                            val byteBuffer = ByteBuffer.wrap(buffer, 0, accepted)
                            while (byteBuffer.hasRemaining()) {
                                val written = channel.write(byteBuffer, position)
                                if (written <= 0) throw IOException("No se pudo escribir pista YouTube")
                                position += written.toLong()
                            }
                            progress.addAndGet(index, accepted.toLong())
                            onBytesWritten()
                            if (accepted < read || progress.get(index) >= segment.length) break
                        }
                    }
                }
            }
        } finally {
            control.calls.remove(call)
        }

        if (!control.stopped.get() && progress.get(index) < segment.length) {
            throw IOException("Segmento YouTube ${index + 1} incompleto")
        }
    }

    private fun requestBuilder(task: DownloadTask, url: String): Request.Builder {
        val builder = Request.Builder().url(SecurityUrlPolicy.requirePublicHttps(url))
        task.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it.take(16_384)) }
        task.userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it.take(1_024)) }
        task.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it.take(8_192)) }
        return builder
    }

    private fun chooseSegmentCount(total: Long, requested: Int): Int {
        val desired = requested.coerceIn(1, 8)
        return when {
            total < 4L * 1024L * 1024L -> 1
            total < 16L * 1024L * 1024L -> minOf(2, desired)
            total < 64L * 1024L * 1024L -> minOf(4, desired)
            else -> minOf(6, desired)
        }
    }

    private fun createSegments(total: Long, count: Int): List<Segment> {
        val base = total / count
        val remainder = total % count
        var cursor = 0L
        return List(count) { index ->
            val size = base + if (index < remainder) 1L else 0L
            Segment(cursor, cursor + size - 1L).also { cursor += size }
        }
    }

    private fun prepareState(
        target: File,
        metaFile: File,
        total: Long,
        count: Int,
        stableId: String,
        segments: List<Segment>
    ): AtomicLongArray {
        val identity = "$total|$count|$stableId"
        val progress = AtomicLongArray(count)
        val existingText = runCatching { metaFile.takeIf { it.exists() }?.readText() }.getOrNull()
        val lines = existingText?.lineSequence()?.toList().orEmpty()
        if (lines.firstOrNull() == identity && target.exists() && target.length() == total) {
            val values = lines.getOrNull(1)?.split(',').orEmpty()
            segments.forEachIndexed { index, segment ->
                progress.set(index, values.getOrNull(index)?.toLongOrNull()?.coerceIn(0L, segment.length) ?: 0L)
            }
            return progress
        }

        // Migrate only an old v0.8.0 contiguous single-stream partial. A full-length file
        // without valid segment metadata may be sparse/corrupt, so rewrite it from byte 0.
        val existingLength = target.takeIf { it.exists() }?.length()?.coerceIn(0L, total) ?: 0L
        val contiguous = if (existingLength in 1 until total) existingLength else 0L
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }
        segments.forEachIndexed { index, segment ->
            val saved = when {
                contiguous <= segment.start -> 0L
                contiguous > segment.end -> segment.length
                else -> contiguous - segment.start
            }
            progress.set(index, saved.coerceIn(0L, segment.length))
        }
        persistState(metaFile, total, count, stableId, progress)
        return progress
    }

    private fun persistState(
        metaFile: File,
        total: Long,
        count: Int,
        stableId: String,
        progress: AtomicLongArray
    ) {
        metaFile.parentFile?.mkdirs()
        val values = (0 until count).joinToString(",") { progress.get(it).coerceAtLeast(0L).toString() }
        runCatching { metaFile.writeText("$total|$count|$stableId\n$values") }
    }

    private fun awaitWorkers(futures: List<Future<*>>, control: TransferControl) {
        futures.forEach { future ->
            while (!future.isDone) {
                if (control.stopped.get()) control.calls.forEach { it.cancel() }
                try {
                    future.get(200L, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    // Cooperative wait: cancelled OkHttp calls unblock reads without interrupting FileChannel.
                } catch (_: java.util.concurrent.CancellationException) {
                    break
                } catch (_: java.util.concurrent.ExecutionException) {
                    break
                }
            }
        }
    }

    private fun parseContentRange(header: String?): RangeInfo? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (start < 0L || end < start) return null
        return RangeInfo(start, end, total)
    }

    private val CONTENT_RANGE_REGEX =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
}
