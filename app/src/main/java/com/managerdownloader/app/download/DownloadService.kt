package com.managerdownloader.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.system.Os
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frostwire.jlibtorrent.TorrentHandle
import com.managerdownloader.app.MainActivity
import com.managerdownloader.app.data.DownloadKind
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.DownloadStatus
import com.managerdownloader.app.data.DownloadTask
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.youtube.YouTubeExtractorClient
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.runBlocking

internal class TransferControl(val id: String) {
    val stopped = AtomicBoolean(false)
    val deleteOnStop = AtomicBoolean(false)
    val calls = CopyOnWriteArrayList<Call>()

    @Volatile
    var torrentHandle: TorrentHandle? = null

    fun pause() {
        stopped.set(true)
        torrentHandle?.let { runCatching { it.pause() } }
        calls.forEach { it.cancel() }
    }

    fun cancel() {
        deleteOnStop.set(true)
        stopped.set(true)
        torrentHandle?.let { runCatching { it.pause() } }
        calls.forEach { it.cancel() }
    }
}

private class SharedBandwidthLimiter(
    private val rateProvider: () -> Long
) {
    private val lock = Any()
    private var tokens = 0.0
    private var lastRefillNanos = System.nanoTime()
    private var lastRate = -1L

    fun acquire(bytes: Int) {
        if (bytes <= 0) return
        var remaining = bytes.toLong()

        while (remaining > 0L && !Thread.currentThread().isInterrupted) {
            val rate = rateProvider().coerceAtLeast(0L)
            if (rate <= 0L) return

            var waitNanos = 0L
            synchronized(lock) {
                val now = System.nanoTime()
                if (rate != lastRate) {
                    lastRate = rate
                    lastRefillNanos = now
                    tokens = 0.0
                }

                val elapsed = (now - lastRefillNanos).coerceAtLeast(0L)
                lastRefillNanos = now
                val capacity = (rate.toDouble() * 0.25)
                    .coerceAtLeast(32.0 * 1024.0)
                    .coerceAtMost(2.0 * 1024.0 * 1024.0)
                tokens = (tokens + elapsed.toDouble() / 1_000_000_000.0 * rate.toDouble())
                    .coerceAtMost(capacity)

                val granted = minOf(remaining.toDouble(), tokens).toLong()
                if (granted > 0L) {
                    tokens -= granted.toDouble()
                    remaining -= granted
                } else {
                    val target = minOf(remaining, 64L * 1024L).coerceAtLeast(1L)
                    waitNanos = ((target.toDouble() / rate.toDouble()) * 1_000_000_000.0)
                        .toLong()
                        .coerceIn(1_000_000L, 50_000_000L)
                }
            }

            if (waitNanos > 0L) {
                LockSupport.parkNanos(waitNanos)
            }
        }
    }
}


class DownloadService : Service() {
    private val transferExecutor = Executors.newCachedThreadPool()
    // Cached workers + fair semaphore avoid one parallel download monopolizing a fixed segment pool.
    private val segmentExecutor = Executors.newCachedThreadPool()
    private val segmentPermits = Semaphore(MAX_TOTAL_SEGMENT_WORKERS, true)
    private val schedulerLock = Any()
    private val active = ConcurrentHashMap<String, TransferControl>()
    private val shuttingDown = AtomicBoolean(false)
    private val bandwidthLimiter = SharedBandwidthLimiter {
        SettingsRepository.settings.value.bandwidthLimitBytesPerSecond
    }
    private val lastNotificationUpdate = AtomicLong(0L)
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            schedule()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            handleNetworkPolicyChange()
        }

        override fun onLost(network: Network) {
            handleNetworkPolicyChange()
        }
    }

    private val httpDispatcher = Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 8
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(httpDispatcher)
        .connectionPool(ConnectionPool(12, 5, java.util.concurrent.TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    private lateinit var torrentEngine: TorrentEngine
    private lateinit var safePowerManager: SafePowerManager

    override fun onCreate() {
        super.onCreate()
        serviceRunning.set(true)
        createNotificationChannel()
        torrentEngine = TorrentEngine(this, client, ::downloadsDirectory)
        safePowerManager = SafePowerManager(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("Preparando transferencias…")

        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let(::pauseInternal)
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let(::resumeInternal)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(::cancelInternal)
            ACTION_PAUSE_ALL -> pauseAllInternal()
            ACTION_RESUME_ALL -> resumeAllInternal()
            ACTION_REFRESH_SETTINGS -> handleNetworkPolicyChange()
            ACTION_PROCESS, null -> Unit
        }

        schedule()
        return START_STICKY
    }

    private fun pauseInternal(id: String) {
        DownloadRepository.pause(id)
        active[id]?.pause()
        torrentEngine.pause(id)
    }

    private fun resumeInternal(id: String) {
        DownloadRepository.resume(id)
        torrentEngine.resume(id)
    }

    private fun cancelInternal(id: String) {
        active[id]?.cancel()
        torrentEngine.cancel(id)
        cleanupHttpParts(id)
        DownloadRepository.remove(id)
    }

    private fun pauseAllInternal() {
        DownloadRepository.downloads.value
            .filter { it.status == DownloadStatus.ACTIVE || it.status == DownloadStatus.QUEUED }
            .forEach { pauseInternal(it.id) }
    }

    private fun resumeAllInternal() {
        DownloadRepository.downloads.value
            .filter { it.status == DownloadStatus.PAUSED }
            .forEach { resumeInternal(it.id) }
    }

    private fun handleNetworkPolicyChange() {
        if (!SettingsRepository.settings.value.wifiOnly) {
            schedule()
            return
        }
        if (isWifiConnected()) {
            schedule()
            return
        }
        active.forEach { (id, control) ->
            DownloadRepository.waitForWifi(id)
            control.pause()
            torrentEngine.pause(id)
        }
        ensureForeground("Esperando Wi-Fi…")
    }

    /**
     * Starts as many queued items as allowed by the selected queue mode.
     * SEQUENTIAL = 1 active item. PARALLEL = user-selected 2..6 active items.
     */
    private fun schedule() {
        if (shuttingDown.get()) return

        synchronized(schedulerLock) {
            val settings = SettingsRepository.settings.value
            if (settings.wifiOnly && !isWifiConnected()) {
                DownloadRepository.queued(1000).forEach {
                    DownloadRepository.updateMetadata(it.id, detail = "Esperando Wi-Fi")
                }
                ensureForeground("Esperando Wi-Fi…")
                return
            }
            val limit = settings.activeTransferLimit
            val slots = (limit - active.size).coerceAtLeast(0)

            if (slots > 0) {
                DownloadRepository.queued(slots).forEach { task ->
                    if (active.containsKey(task.id)) return@forEach
                    val control = TransferControl(task.id)
                    if (active.putIfAbsent(task.id, control) != null) return@forEach

                    DownloadRepository.markActive(
                        task.id,
                        if (task.kind == DownloadKind.TORRENT) "Preparando torrent…" else "Analizando servidor…"
                    )

                    transferExecutor.execute {
                        try {
                            when (task.kind) {
                                DownloadKind.HTTP -> downloadHttp(task, control)
                                DownloadKind.TORRENT -> torrentEngine.download(task, control, limit)
                            }
                        } catch (error: Throwable) {
                            val current = DownloadRepository.find(task.id)
                            if (
                                current != null &&
                                current.status == DownloadStatus.ACTIVE &&
                                !control.stopped.get()
                            ) {
                                DownloadRepository.markFailed(
                                    task.id,
                                    error.message ?: error.javaClass.simpleName
                                )
                            }
                        } finally {
                            if (control.deleteOnStop.get()) cleanupHttpParts(task.id)
                            active.remove(task.id)
                            DownloadRepository.flush()
                            schedule()
                        }
                    }
                }
            }

            updateAggregateNotification()

            if (active.isNotEmpty()) {
                safePowerManager.acquire()
            } else {
                safePowerManager.release()
            }

            if (active.isEmpty() && !DownloadRepository.hasQueued()) {
                safePowerManager.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun downloadHttp(initialTask: DownloadTask, control: TransferControl) {
        var task = initialTask
        var refreshedTemporaryUrl = false

        while (!control.stopped.get()) {
            try {
                downloadHttpAttempt(task, control)
                return
            } catch (error: HttpStatusException) {
                if (
                    error.statusCode == 403 &&
                    !refreshedTemporaryUrl &&
                    refreshExpiredExtractedStream(task)
                ) {
                    refreshedTemporaryUrl = true
                    task = DownloadRepository.find(task.id) ?: task
                    DownloadRepository.updateMetadata(task.id, detail = "Enlace temporal renovado · reanudando")
                    continue
                }
                throw error
            }
        }
    }

    private fun downloadHttpAttempt(task: DownloadTask, control: TransferControl) {
        val probe = probeServer(task, control)
        if (control.stopped.get()) return

        val filename = probe.filename.takeIf { it.isNotBlank() } ?: task.filename
        DownloadRepository.updateMetadata(
            task.id,
            filename = filename,
            totalBytes = probe.totalBytes.takeIf { it > 0 },
            detail = if (probe.supportsRanges) "Servidor compatible con descarga segmentada" else "Descarga de flujo único"
        )

        val safeFilename = DownloadRepository.find(task.id)?.filename ?: task.filename
        val requestedSegments = SettingsRepository.settings.value.segmentsPerFile
        val hasSinglePartial = singleMetaFile(task.id).exists() && partialFile(task.id).exists()
        val segmentCount = if (hasSinglePartial) {
            1
        } else {
            chooseSegmentCount(probe.totalBytes, requestedSegments, probe.supportsRanges)
        }
        ensureEnoughWorkingSpace(task.id, probe.totalBytes)

        if (segmentCount > 1 && probe.totalBytes > 0) {
            try {
                downloadSegmented(
                    task.copy(filename = safeFilename),
                    probe.totalBytes,
                    segmentCount,
                    probe.validator,
                    control
                )
            } catch (error: RangeNotSupportedException) {
                if (control.stopped.get()) return
                // Some servers advertise 206 during the probe but later ignore Range. Fall back
                // cleanly instead of corrupting the sparse partial with a full HTTP 200 response.
                cleanupSegmentedState(task.id, deletePart = true)
                DownloadRepository.updateMetadata(task.id, detail = "Range inestable · cambiando a 1 conexión")
                prepareSingleLayout(task, probe.totalBytes, probe.validator)
                downloadSingleWithRetries(task.copy(filename = safeFilename), probe.validator, control)
            }
        } else {
            prepareSingleLayout(task, probe.totalBytes, probe.validator)
            downloadSingleWithRetries(task.copy(filename = safeFilename), probe.validator, control)
        }
    }

    private fun refreshExpiredExtractedStream(task: DownloadTask): Boolean {
        val sourceUrl = task.originalSourceUrl?.takeIf { it.isNotBlank() } ?: return false
        val details = runBlocking { YouTubeExtractorClient.analyze(sourceUrl) }.getOrNull() ?: return false
        val options = details.progressiveVideo + details.audioOnly + details.videoOnly
        if (options.isEmpty()) return false

        val selected = task.sourceFormatId?.let { wantedId ->
            options.firstOrNull { it.id == wantedId }
        } ?: run {
            val ext = task.filename.substringAfterLast('.', "").lowercase()
            options.firstOrNull { it.filename.substringAfterLast('.', "").equals(ext, true) }
        } ?: return false

        DownloadRepository.updateUrl(
            id = task.id,
            url = selected.url,
            cookie = task.cookie,
            userAgent = task.userAgent,
            referer = sourceUrl
        )
        return true
    }

    private data class HttpProbe(
        val supportsRanges: Boolean,
        val totalBytes: Long,
        val filename: String,
        val validator: String?
    )

    private fun probeServer(task: DownloadTask, control: TransferControl): HttpProbe {
        val builder = requestBuilder(task)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()

        val call = client.newCall(builder.build())
        control.calls.add(call)

        try {
            return call.execute().use { response ->
                if (control.stopped.get()) throw IOException("Transferencia detenida")
                if (!response.isSuccessful && response.code != 416) {
                    throw HttpStatusException(response.code, "HTTP ${response.code} al analizar servidor")
                }

            val parsedRange = parseContentRange(response.header("Content-Range"))
            val rangeTotal = parsedRange?.total
            val supportsRanges = response.code == 206 &&
                parsedRange?.start == 0L && parsedRange.end == 0L &&
                rangeTotal != null && rangeTotal > 1L
            val contentLength = response.body?.contentLength() ?: -1L
            val total = when {
                rangeTotal != null && rangeTotal > 0 -> rangeTotal
                response.code == 200 && contentLength > 0 -> contentLength
                else -> -1L
            }

            val guessed = URLUtil.guessFileName(
                response.request.url.toString(),
                response.header("Content-Disposition"),
                response.header("Content-Type")
            )

            val etag = response.header("ETag")
                ?.takeIf { !it.trimStart().startsWith("W/", ignoreCase = true) }
            val validator = etag ?: response.header("Last-Modified")

                HttpProbe(
                    supportsRanges = supportsRanges,
                    totalBytes = total,
                    filename = guessed,
                    validator = validator
                )
            }
        } finally {
            control.calls.remove(call)
        }
    }

    private fun chooseSegmentCount(total: Long, requested: Int, rangeSupported: Boolean): Int {
        if (!rangeSupported || total <= 0L) return 1
        val settings = SettingsRepository.settings.value
        // On mobile, more than 6–8 parallel ranges usually adds radio/CPU/thermal overhead
        // without improving throughput. Keep a hard ceiling of 8.
        val desired = requested.coerceIn(1, 8)
        return if (settings.turboMode) {
            when {
                total < 8L * 1024L * 1024L -> 1
                total < 32L * 1024L * 1024L -> desired.coerceAtMost(2)
                total < 128L * 1024L * 1024L -> desired.coerceAtMost(4)
                total < 512L * 1024L * 1024L -> desired.coerceAtMost(6)
                else -> desired.coerceAtMost(8)
            }
        } else {
            when {
                total < 16L * 1024L * 1024L -> 1
                total < 64L * 1024L * 1024L -> desired.coerceAtMost(2)
                total < 256L * 1024L * 1024L -> desired.coerceAtMost(4)
                else -> desired.coerceAtMost(6)
            }
        }
    }

    private fun downloadSegmented(
        task: DownloadTask,
        totalBytes: Long,
        segmentCount: Int,
        validator: String?,
        control: TransferControl
    ) {
        val segments = createSegments(totalBytes, segmentCount)
        val progress = prepareSegmentLayout(task, totalBytes, segmentCount, validator, segments)
        val initialDone = (0 until segmentCount).sumOf { progress.get(it) }

        DownloadRepository.updateProgress(
            task.id,
            initialDone,
            totalBytes,
            0L,
            "$segmentCount conexiones · archivo único reanudable"
        )

        val firstError = AtomicReference<Throwable?>(null)
        val progressLock = Any()
        val lastUpdate = AtomicLong(System.currentTimeMillis())
        val lastBytes = AtomicLong(initialDone)
        val lastStatePersist = AtomicLong(0L)

        val futures = segments.mapIndexed { index, segment ->
            segmentExecutor.submit {
                var permitAcquired = false
                try {
                    segmentPermits.acquire()
                    permitAcquired = true
                    if (control.stopped.get()) return@submit

                    val maxRetries = SettingsRepository.settings.value.segmentRetryCount
                    var attempt = 0
                    while (!control.stopped.get()) {
                        try {
                            downloadRangeToPart(
                                task = task,
                                index = index,
                                segment = segment,
                                progress = progress,
                                totalBytes = totalBytes,
                                segmentCount = segmentCount,
                                validator = validator,
                                control = control,
                                progressLock = progressLock,
                                lastUpdate = lastUpdate,
                                lastBytes = lastBytes,
                                lastStatePersist = lastStatePersist
                            )
                            break
                        } catch (error: RangeNotSupportedException) {
                            throw error
                        } catch (error: HttpStatusException) {
                            if (error.statusCode == 403 && task.originalSourceUrl != null) throw error
                            if (control.stopped.get() || attempt >= maxRetries) throw error
                            attempt++
                            DownloadRepository.updateMetadata(
                                task.id,
                                detail = "Reintentando conexión ${index + 1}/$segmentCount · intento $attempt/$maxRetries"
                            )
                            Thread.sleep((250L * attempt).coerceAtMost(1200L))
                        } catch (error: Throwable) {
                            if (control.stopped.get() || attempt >= maxRetries) throw error
                            attempt++
                            DownloadRepository.updateMetadata(
                                task.id,
                                detail = "Reintentando conexión ${index + 1}/$segmentCount · intento $attempt/$maxRetries"
                            )
                            Thread.sleep((250L * attempt).coerceAtMost(1200L))
                        }
                    }
                } catch (error: Throwable) {
                    if (!control.stopped.get()) {
                        firstError.compareAndSet(null, error)
                        control.calls.forEach { it.cancel() }
                    }
                } finally {
                    if (permitAcquired) segmentPermits.release()
                }
            }
        }

        try {
            futures.forEach { future ->
                while (!future.isDone) {
                    if (control.stopped.get()) {
                        futures.forEach { it.cancel(true) }
                        persistSegmentProgress(task, totalBytes, segmentCount, validator, progress)
                        return
                    }
                    try {
                        future.get(250, TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        // Poll so pause/cancel can interrupt a queued segment promptly.
                    }
                }
                runCatching { future.get() }.exceptionOrNull()?.let { wrapped ->
                    val cause = wrapped.cause ?: wrapped
                    firstError.compareAndSet(null, cause)
                }
            }
        } finally {
            if (control.stopped.get()) futures.forEach { it.cancel(true) }
        }

        if (control.stopped.get()) {
            persistSegmentProgress(task, totalBytes, segmentCount, validator, progress)
            return
        }
        firstError.get()?.let { throw it }

        segments.forEachIndexed { index, segment ->
            if (progress.get(index) != segment.length) {
                throw IOException("Segmento ${index + 1} incompleto")
            }
        }
        persistSegmentProgress(task, totalBytes, segmentCount, validator, progress)

        val part = partialFile(task.id)
        if (!part.exists() || part.length() != totalBytes) {
            throw IOException("El archivo parcial no tiene el tamaño esperado")
        }

        verifySha256IfRequested(task.id, part)
        val finalFile = uniqueFinalFile(task.filename)
        if (!part.renameTo(finalFile)) {
            throw IOException("No se pudo finalizar el archivo sin duplicar espacio; se conserva el parcial para reintentar")
        }
        runCatching { segmentMetaFile(task.id).delete() }
        cleanupLegacySegmentFiles(task.id)

        val completedBytes = finalFile.length()
        val publishedPath = StorageRepository.publishFile(
            finalFile,
            task.filename,
            downloadsDirectory()
        )
        DownloadRepository.markCompleted(task.id, publishedPath, completedBytes)
        showCompletedNotification(task.filename)
    }

    private data class ByteSegment(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1L
    }

    private data class ParsedContentRange(val start: Long, val end: Long, val total: Long?)

    private class RangeNotSupportedException(message: String) : IOException(message)

    private class HttpStatusException(val statusCode: Int, message: String) : IOException(message)

    private fun createSegments(totalBytes: Long, count: Int): List<ByteSegment> {
        val base = totalBytes / count
        val remainder = totalBytes % count
        var cursor = 0L
        return List(count) { index ->
            val length = base + if (index < remainder) 1L else 0L
            val segment = ByteSegment(cursor, cursor + length - 1L)
            cursor += length
            segment
        }
    }

    private fun downloadRangeToPart(
        task: DownloadTask,
        index: Int,
        segment: ByteSegment,
        progress: AtomicLongArray,
        totalBytes: Long,
        segmentCount: Int,
        validator: String?,
        control: TransferControl,
        progressLock: Any,
        lastUpdate: AtomicLong,
        lastBytes: AtomicLong,
        lastStatePersist: AtomicLong
    ) {
        val existing = progress.get(index).coerceIn(0L, segment.length)
        if (existing >= segment.length) return

        val requestStart = segment.start + existing
        val builder = requestBuilder(task)
            .header("Range", "bytes=$requestStart-${segment.end}")
            .header("Accept-Encoding", "identity")
            .get()

        // Short-lived extracted URLs (e.g. YouTube) may rotate validators. Content-Range validation
        // is authoritative for those streams, so don't send a stale If-Range across re-extraction.
        if (task.originalSourceUrl == null) {
            validator?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
        }

        val call = client.newCall(builder.build())
        control.calls.add(call)
        try {
            call.execute().use { response ->
                when (response.code) {
                    200 -> throw RangeNotSupportedException("El servidor dejó de respetar Range; se cambiará a descarga de una conexión")
                    206 -> Unit
                    else -> throw HttpStatusException(response.code, "HTTP ${response.code} durante descarga segmentada")
                }

                val contentRange = parseContentRange(response.header("Content-Range"))
                    ?: throw IOException("Content-Range ausente o inválido")
                if (contentRange.start != requestStart || contentRange.end > segment.end) {
                    throw IOException("Content-Range no coincide con el bloque solicitado")
                }
                if (contentRange.total != null && contentRange.total != totalBytes) {
                    throw IOException("El tamaño remoto cambió durante la descarga")
                }

                val body = response.body ?: throw IOException("Respuesta sin contenido")
                RandomAccessFile(partialFile(task.id), "rw").use { output ->
                    val fileChannel = output.channel
                    var writePosition = requestStart
                    body.byteStream().use { input ->
                        val inputChannel = Channels.newChannel(input)
                        val bufferSize = if (SettingsRepository.settings.value.bandwidthLimitBytesPerSecond > 0L) 64 * 1024 else 128 * 1024
                        val buffer = ByteBuffer.allocateDirect(bufferSize)
                        while (!control.stopped.get()) {
                            buffer.clear()
                            val read = inputChannel.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            if (!ensureNetworkPolicy(task.id, control)) break

                            val before = progress.get(index)
                            if (before + read > segment.length) {
                                throw IOException("El servidor envió más bytes de los solicitados")
                            }

                            bandwidthLimiter.acquire(read)
                            buffer.flip()
                            while (buffer.hasRemaining()) {
                                val written = fileChannel.write(buffer, writePosition)
                                if (written <= 0) throw IOException("No se pudo escribir el bloque en disco")
                                writePosition += written.toLong()
                            }
                            progress.addAndGet(index, read.toLong())

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate.get() >= 500L) {
                                synchronized(progressLock) {
                                    if (now - lastUpdate.get() >= 500L) {
                                        val downloaded = (0 until segmentCount).sumOf { progress.get(it) }
                                        val previousBytes = lastBytes.getAndSet(downloaded)
                                        val previousTime = lastUpdate.getAndSet(now)
                                        val elapsed = (now - previousTime).coerceAtLeast(1L)
                                        val speed = ((downloaded - previousBytes).coerceAtLeast(0L) * 1000L) / elapsed
                                        DownloadRepository.updateProgress(
                                            task.id,
                                            downloaded,
                                            totalBytes,
                                            speed,
                                            "$segmentCount conexiones activas"
                                        )
                                        val previousPersist = lastStatePersist.get()
                                        if (now - previousPersist >= 2_000L &&
                                            lastStatePersist.compareAndSet(previousPersist, now)
                                        ) {
                                            persistSegmentProgress(task, totalBytes, segmentCount, validator, progress)
                                        }
                                        updateAggregateNotificationThrottled(now)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            control.calls.remove(call)
        }

        if (!control.stopped.get() && progress.get(index) < segment.length) {
            throw IOException("Segmento ${index + 1} incompleto")
        }
    }

    private fun parseContentRange(header: String?): ParsedContentRange? {
        val value = header?.trim() ?: return null
        val match = CONTENT_RANGE_REGEX.matchEntire(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (start < 0 || end < start) return null
        return ParsedContentRange(start, end, total)
    }

    private fun downloadSingleWithRetries(task: DownloadTask, validator: String?, control: TransferControl) {
        val maxRetries = SettingsRepository.settings.value.segmentRetryCount
        var attempt = 0
        while (!control.stopped.get()) {
            try {
                downloadSingle(task, validator, control)
                return
            } catch (error: HttpStatusException) {
                if (error.statusCode == 403 && task.originalSourceUrl != null) throw error
                if (control.stopped.get() || attempt >= maxRetries) throw error
                attempt++
                DownloadRepository.updateMetadata(
                    task.id,
                    detail = "Reconectando descarga · intento $attempt/$maxRetries"
                )
                Thread.sleep((300L * attempt).coerceAtMost(1500L))
            } catch (error: Throwable) {
                if (control.stopped.get() || attempt >= maxRetries) throw error
                attempt++
                DownloadRepository.updateMetadata(
                    task.id,
                    detail = "Reconectando descarga · intento $attempt/$maxRetries"
                )
                Thread.sleep((300L * attempt).coerceAtMost(1500L))
            }
        }
    }

    private fun downloadSingle(task: DownloadTask, validator: String?, control: TransferControl) {
        val partial = partialFile(task.id)
        partial.parentFile?.mkdirs()
        var existing = partial.length().coerceAtLeast(0L)

        val builder = requestBuilder(task)
            .header("Accept-Encoding", "identity")
            .get()
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
            if (task.originalSourceUrl == null) {
                validator?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
            }
        }

        val call = client.newCall(builder.build())
        control.calls.add(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code, "HTTP ${response.code} durante descarga")
                val body = response.body ?: throw IOException("Respuesta sin contenido")

                if (existing > 0) {
                    when (response.code) {
                        200 -> {
                            // Server ignored Range. Restart this single stream safely from byte 0.
                            RandomAccessFile(partial, "rw").use { it.setLength(0L) }
                            existing = 0L
                        }
                        206 -> {
                            val parsed = parseContentRange(response.header("Content-Range"))
                                ?: throw RangeNotSupportedException("Content-Range inválido al reanudar")
                            if (parsed.start != existing) {
                                throw RangeNotSupportedException(
                                    "El servidor respondió desde ${parsed.start}, se esperaba $existing"
                                )
                            }
                            val knownTotal = DownloadRepository.find(task.id)?.totalBytes ?: -1L
                            if (parsed.total != null && knownTotal > 0L && parsed.total != knownTotal) {
                                throw IOException("El archivo remoto cambió de tamaño durante la reanudación")
                            }
                        }
                    }
                }

                val filename = URLUtil.guessFileName(
                    response.request.url.toString(),
                    response.header("Content-Disposition"),
                    response.header("Content-Type")
                ).takeIf { it.isNotBlank() } ?: task.filename

                val total = resolveTotalBytes(response, existing)
                DownloadRepository.updateMetadata(task.id, filename = filename, totalBytes = total.takeIf { it > 0 })
                ensureEnoughWorkingSpace(task.id, total)

                var downloaded = existing
                var lastBytes = downloaded
                var lastTime = System.currentTimeMillis()

                RandomAccessFile(partial, "rw").use { output ->
                    val fileChannel = output.channel
                    fileChannel.position(existing)
                    body.byteStream().use { input ->
                        val inputChannel = Channels.newChannel(input)
                        val bufferSize = if (SettingsRepository.settings.value.bandwidthLimitBytesPerSecond > 0L) 64 * 1024 else 256 * 1024
                        val buffer = ByteBuffer.allocateDirect(bufferSize)
                        while (!control.stopped.get()) {
                            buffer.clear()
                            val read = inputChannel.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            if (!ensureNetworkPolicy(task.id, control)) break
                            bandwidthLimiter.acquire(read)
                            buffer.flip()
                            while (buffer.hasRemaining()) fileChannel.write(buffer)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 500L) {
                                val speed = ((downloaded - lastBytes) * 1000L) / (now - lastTime).coerceAtLeast(1L)
                                DownloadRepository.updateProgress(task.id, downloaded, total, speed, "1 conexión")
                                updateAggregateNotificationThrottled(now)
                                lastBytes = downloaded
                                lastTime = now
                            }
                        }
                    }
                }

                if (control.stopped.get()) return@use

                val currentName = DownloadRepository.find(task.id)?.filename ?: filename
                val finalFile = uniqueFinalFile(currentName)
                if (!partial.renameTo(finalFile)) {
                    throw IOException("No se pudo finalizar el archivo sin duplicar espacio; se conserva el parcial")
                }

                verifySha256IfRequested(task.id, finalFile)
                val completedBytes = finalFile.length()
                val publishedPath = StorageRepository.publishFile(
                    finalFile,
                    currentName,
                    downloadsDirectory()
                )
                DownloadRepository.markCompleted(task.id, publishedPath, completedBytes)
                runCatching { singleMetaFile(task.id).delete() }
                showCompletedNotification(currentName)
            }
        } finally {
            control.calls.remove(call)
        }
    }

    private fun requestBuilder(task: DownloadTask): Request.Builder {
        val builder = Request.Builder().url(task.url)
        task.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        task.userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        task.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        return builder
    }

    private fun resolveTotalBytes(response: Response, existingBytes: Long): Long {
        val rangeTotal = parseContentRange(response.header("Content-Range"))?.total
        if (rangeTotal != null && rangeTotal > 0L) return rangeTotal
        val contentLength = response.body?.contentLength() ?: -1L
        return if (contentLength > 0L) contentLength + existingBytes else -1L
    }

    private fun prepareSegmentLayout(
        task: DownloadTask,
        total: Long,
        count: Int,
        validator: String?,
        segments: List<ByteSegment>
    ): AtomicLongArray {
        val meta = segmentMetaFile(task.id)
        val part = partialFile(task.id)
        val identity = segmentIdentity(task, total, count, validator)
        val existingText = runCatching { meta.takeIf { it.exists() }?.readText() }.getOrNull()
        val existingIdentity = existingText?.lineSequence()?.firstOrNull().orEmpty()
        val progress = AtomicLongArray(count)

        if (existingIdentity == identity && part.exists() && part.length() == total) {
            parseProgressLine(existingText?.lineSequence()?.drop(1)?.firstOrNull(), segments)
                .forEachIndexed { index, value -> progress.set(index, value) }
            return progress
        }

        // Migrate old v0.7.1 .segN partials into the new single sparse file one segment at a time.
        // This preserves useful downloaded data without the old 2x final merge step.
        val legacyFiles = legacySegmentFiles(task.id, count)
        if (existingIdentity == identity && !part.exists() && legacyFiles.any { it.exists() }) {
            part.parentFile?.mkdirs()
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            RandomAccessFile(part, "rw").use { destinationRaf ->
                val destination = destinationRaf.channel
                segments.forEachIndexed { index, segment ->
                    val legacy = legacyFiles[index]
                    if (!legacy.exists()) return@forEachIndexed
                    val valid = legacy.length().coerceIn(0L, segment.length)
                    if (valid <= 0L) {
                        runCatching { legacy.delete() }
                        return@forEachIndexed
                    }
                    FileInputStream(legacy).channel.use { source ->
                        var copied = 0L
                        destination.position(segment.start)
                        while (copied < valid) {
                            val amount = source.transferTo(copied, valid - copied, destination)
                            if (amount <= 0L) break
                            copied += amount
                        }
                        progress.set(index, copied.coerceAtMost(segment.length))
                    }
                    runCatching { legacy.delete() }
                }
            }
            preallocatePartFile(part, total)
            persistSegmentProgress(task, total, count, validator, progress)
            return progress
        }

        cleanupSegmentedState(task.id, deletePart = true)
        part.parentFile?.mkdirs()
        preallocatePartFile(part, total)
        persistSegmentProgress(task, total, count, validator, progress)
        return progress
    }

    private fun prepareSingleLayout(task: DownloadTask, total: Long, validator: String?) {
        val meta = singleMetaFile(task.id)
        val stableIdentity = task.originalSourceUrl ?: validator.orEmpty()
        val expected = "$total|$stableIdentity"
        val existing = runCatching { meta.takeIf { it.exists() }?.readText() }.getOrNull()
        if (existing != expected && partialFile(task.id).exists()) {
            // A different resource must not reuse bytes from an older partial. For extracted
            // streams originalSourceUrl is stable across short-lived URL refreshes.
            runCatching { partialFile(task.id).delete() }
        }
        cleanupLegacySegmentFiles(task.id)
        runCatching { segmentMetaFile(task.id).delete() }
        meta.parentFile?.mkdirs()
        runCatching { meta.writeText(expected) }
    }

    private fun segmentIdentity(task: DownloadTask, total: Long, count: Int, validator: String?): String =
        "$total|$count|${task.originalSourceUrl ?: validator.orEmpty()}"

    private fun persistSegmentProgress(
        task: DownloadTask,
        total: Long,
        count: Int,
        validator: String?,
        progress: AtomicLongArray
    ) {
        val meta = segmentMetaFile(task.id)
        meta.parentFile?.mkdirs()
        val values = (0 until count).joinToString(",") { progress.get(it).coerceAtLeast(0L).toString() }
        val text = segmentIdentity(task, total, count, validator) + "\n" + values
        runCatching { meta.writeText(text) }
    }

    private fun parseProgressLine(line: String?, segments: List<ByteSegment>): List<Long> {
        val parts = line?.split(',').orEmpty()
        return segments.mapIndexed { index, segment ->
            parts.getOrNull(index)?.toLongOrNull()?.coerceIn(0L, segment.length) ?: 0L
        }
    }

    private fun preallocatePartFile(file: File, total: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            // Reserve physical blocks when supported. Fallback keeps a sparse file on providers
            // where posix_fallocate is unavailable.
            runCatching { Os.posix_fallocate(raf.fd, 0L, total) }
                .onFailure { if (raf.length() != total) raf.setLength(total) }
        }
    }

    private fun legacySegmentFiles(id: String, count: Int): List<File> =
        List(count) { index -> segmentFile(id, index) }

    private fun cleanupLegacySegmentFiles(id: String) {
        downloadsDirectory().listFiles()?.forEach { file ->
            if (file.name.startsWith(".$id.seg") && file.name != ".$id.segments") {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupSegmentedState(id: String, deletePart: Boolean) {
        runCatching { segmentMetaFile(id).delete() }
        cleanupLegacySegmentFiles(id)
        if (deletePart) runCatching { partialFile(id).delete() }
    }

    private fun segmentDownloadedBytes(id: String): Long {
        val meta = segmentMetaFile(id)
        if (meta.exists()) {
            val line = runCatching { meta.readLines().getOrNull(1) }.getOrNull()
            if (!line.isNullOrBlank()) {
                return line.split(',').sumOf { it.toLongOrNull()?.coerceAtLeast(0L) ?: 0L }
            }
        }
        return downloadsDirectory().listFiles()
            ?.filter { it.name.startsWith(".$id.seg") && it.name != ".$id.segments" }
            ?.sumOf { it.length() }
            ?: 0L
    }

    private fun ensureEnoughWorkingSpace(id: String, totalBytes: Long) {
        if (totalBytes <= 0L) return
        val dir = downloadsDirectory()
        val segmented = segmentMetaFile(id).exists() ||
            dir.listFiles()?.any { it.name.startsWith(".$id.seg") && it.name != ".$id.segments" } == true
        val alreadyDownloaded = if (segmented) {
            segmentDownloadedBytes(id)
        } else {
            partialFile(id).takeIf { it.exists() }?.length() ?: 0L
        }
        val remaining = (totalBytes - alreadyDownloaded).coerceAtLeast(0L)
        val reserve = 32L * 1024L * 1024L
        if (dir.usableSpace in 1 until (remaining + reserve)) {
            throw IOException("Espacio insuficiente. Se necesitan aproximadamente ${formatBytes(remaining + reserve)} libres")
        }
    }

    private fun updateAggregateNotificationThrottled(now: Long = System.currentTimeMillis()) {
        val previous = lastNotificationUpdate.get()
        if (now - previous < 1500L) return
        if (lastNotificationUpdate.compareAndSet(previous, now)) {
            updateAggregateNotification()
        }
    }

    private fun ensureNetworkPolicy(id: String, control: TransferControl): Boolean {
        if (!SettingsRepository.settings.value.wifiOnly || isWifiConnected()) return true
        DownloadRepository.waitForWifi(id)
        control.pause()
        return false
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun verifySha256IfRequested(id: String, file: File) {
        val expected = DownloadRepository.find(id)?.expectedSha256 ?: return
        val actual = sha256(file)
        DownloadRepository.updateHash(id, actual)
        if (!actual.equals(expected, ignoreCase = true)) {
            // Never reuse bytes that already failed integrity verification; otherwise Retry loops
            // forever over the same corrupt partial/segments.
            runCatching { file.delete() }
            cleanupHttpParts(id)
            throw IOException("SHA-256 no coincide. Se descartó el parcial corrupto. Esperado ${expected.take(12)}… · obtenido ${actual.take(12)}…")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadsDirectory(): File {
        val external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val base = external ?: File(filesDir, "downloads")
        return File(base, "ManagerDownloader").apply { mkdirs() }
    }

    private fun partialFile(id: String): File = File(downloadsDirectory(), ".$id.part")
    private fun segmentFile(id: String, index: Int): File = File(downloadsDirectory(), ".$id.seg$index")
    private fun segmentMetaFile(id: String): File = File(downloadsDirectory(), ".$id.segments")
    private fun singleMetaFile(id: String): File = File(downloadsDirectory(), ".$id.singlemeta")

    private fun cleanupHttpParts(id: String) {
        runCatching { partialFile(id).delete() }
        runCatching { singleMetaFile(id).delete() }
        cleanupSegmentedState(id, deletePart = false)
    }

    private fun uniqueFinalFile(filename: String): File {
        val dir = downloadsDirectory()
        var candidate = File(dir, filename)
        if (!candidate.exists()) return candidate

        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun ensureForeground(text: String) {
        startForeground(NOTIFICATION_ID, buildNotification("Manager Downloader", text, -1))
    }

    private fun updateAggregateNotification() {
        val tasks = DownloadRepository.downloads.value.filter { it.status == DownloadStatus.ACTIVE }
        if (tasks.isEmpty()) return
        val speed = tasks.sumOf { it.speedBytesPerSecond }
        val title = if (tasks.size == 1) tasks.first().filename else "${tasks.size} descargas activas"
        val text = "${formatBytes(speed)}/s · ${SettingsRepository.settings.value.queueMode.name.lowercase()}"
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(title, text, -1)
        )
    }

    private fun showCompletedNotification(filename: String) {
        getSystemService(NotificationManager::class.java).notify(
            filename.hashCode(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Descarga completada")
                .setContentText(filename)
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build()
        )
    }

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pausar todo",
                serviceActionIntent(ACTION_PAUSE_ALL, 11)
            )
            .addAction(
                android.R.drawable.ic_media_play,
                "Reanudar",
                serviceActionIntent(ACTION_RESUME_ALL, 12)
            )
        if (progress in 0..100) builder.setProgress(100, progress, false)
        return builder.build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso de descargas HTTP y torrent"
                }
            )
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ may time out long dataSync FGS sessions. Preserve partial data.
        shuttingDown.set(true)
        active.forEach { (id, control) ->
            DownloadRepository.pause(id)
            control.pause()
        }
        safePowerManager.release()
        stopSelf()
    }

    override fun onDestroy() {
        serviceRunning.set(false)
        shuttingDown.set(true)
        active.forEach { (_, control) -> control.pause() }
        if (::safePowerManager.isInitialized) safePowerManager.release()
        torrentEngine.stopAsync()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        transferExecutor.shutdownNow()
        segmentExecutor.shutdownNow()
        DownloadRepository.flush()
        super.onDestroy()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    companion object {
        private val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        private const val CHANNEL_ID = "manager_downloads"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_PROCESS = "manager.action.PROCESS"
        private const val ACTION_PAUSE = "manager.action.PAUSE"
        private const val ACTION_RESUME = "manager.action.RESUME"
        private const val ACTION_CANCEL = "manager.action.CANCEL"
        private const val ACTION_PAUSE_ALL = "manager.action.PAUSE_ALL"
        private const val ACTION_RESUME_ALL = "manager.action.RESUME_ALL"
        private const val ACTION_REFRESH_SETTINGS = "manager.action.REFRESH_SETTINGS"
        private const val EXTRA_ID = "download_id"
        private const val MAX_TOTAL_SEGMENT_WORKERS = 8
        private val serviceRunning = AtomicBoolean(false)

        fun process(context: Context) = send(context, ACTION_PROCESS)
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE, id)
        fun resume(context: Context, id: String) = send(context, ACTION_RESUME, id)
        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id)
        fun pauseAll(context: Context) = send(context, ACTION_PAUSE_ALL)
        fun resumeAll(context: Context) = send(context, ACTION_RESUME_ALL)

        fun refreshSettings(context: Context) {
            if (!serviceRunning.get()) return
            val intent = Intent(context.applicationContext, DownloadService::class.java)
                .setAction(ACTION_REFRESH_SETTINGS)
            runCatching { context.applicationContext.startService(intent) }
        }

        private fun send(context: Context, action: String, id: String? = null) {
            val intent = Intent(context.applicationContext, DownloadService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            runCatching { ContextCompat.startForegroundService(context.applicationContext, intent) }
        }
    }
}
