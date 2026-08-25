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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
    private var nextAvailableNanos = 0L

    fun acquire(bytes: Int) {
        val rate = rateProvider()
        if (rate <= 0L || bytes <= 0) return

        val waitNanos = synchronized(lock) {
            val now = System.nanoTime()
            val start = max(now, nextAvailableNanos)
            val duration = ((bytes.toDouble() / rate.toDouble()) * 1_000_000_000.0).toLong()
            nextAvailableNanos = start + duration
            (start - now).coerceAtLeast(0L)
        }

        if (waitNanos > 0L) {
            val millis = waitNanos / 1_000_000L
            val nanos = (waitNanos % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        }
    }
}

class DownloadService : Service() {
    private val transferExecutor = Executors.newCachedThreadPool()
    private val segmentExecutor = Executors.newFixedThreadPool(32)
    private val schedulerLock = Any()
    private val active = ConcurrentHashMap<String, TransferControl>()
    private val shuttingDown = AtomicBoolean(false)
    private val bandwidthLimiter = SharedBandwidthLimiter {
        SettingsRepository.settings.value.bandwidthLimitBytesPerSecond
    }
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
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(httpDispatcher)
        .connectionPool(ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    private lateinit var torrentEngine: TorrentEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        torrentEngine = TorrentEngine(this, client, ::downloadsDirectory)
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

            if (active.isEmpty() && !DownloadRepository.hasQueued()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun downloadHttp(task: DownloadTask, control: TransferControl) {
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
        val segmentCount = chooseSegmentCount(probe.totalBytes, requestedSegments, probe.supportsRanges)

        if (segmentCount > 1 && probe.totalBytes > 0) {
            downloadSegmented(
                task.copy(filename = safeFilename),
                probe.totalBytes,
                segmentCount,
                probe.validator,
                control
            )
        } else {
            prepareSingleLayout(task.id, probe.totalBytes, probe.validator)
            downloadSingleWithRetries(task.copy(filename = safeFilename), probe.validator, control)
        }
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

        return call.execute().use { response ->
            if (control.stopped.get()) throw IOException("Transferencia detenida")
            if (!response.isSuccessful && response.code != 416) {
                throw IOException("HTTP ${response.code}")
            }

            val contentRange = response.header("Content-Range")
            val rangeTotal = contentRange
                ?.substringAfterLast('/', "")
                ?.takeIf { it != "*" }
                ?.toLongOrNull()

            val supportsRanges = response.code == 206 && rangeTotal != null && rangeTotal > 1
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
    }

    private fun chooseSegmentCount(total: Long, requested: Int, rangeSupported: Boolean): Int {
        if (!rangeSupported || total <= 0L) return 1
        val settings = SettingsRepository.settings.value
        val desired = requested.coerceIn(1, 16)
        return if (settings.turboMode) {
            when {
                total < 4L * 1024L * 1024L -> 1
                total < 16L * 1024L * 1024L -> desired.coerceAtMost(2)
                total < 64L * 1024L * 1024L -> desired.coerceAtMost(4)
                total < 256L * 1024L * 1024L -> desired.coerceAtMost(8)
                total < 1024L * 1024L * 1024L -> desired.coerceAtMost(12)
                else -> desired
            }
        } else {
            when {
                total < 8L * 1024L * 1024L -> 1
                total < 32L * 1024L * 1024L -> desired.coerceAtMost(2)
                total < 128L * 1024L * 1024L -> desired.coerceAtMost(4)
                total < 512L * 1024L * 1024L -> desired.coerceAtMost(8)
                else -> desired.coerceAtMost(12)
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
        prepareSegmentLayout(task.id, totalBytes, segmentCount, validator)
        val segments = createSegments(totalBytes, segmentCount)
        val progress = AtomicLongArray(segmentCount)
        segments.forEachIndexed { index, segment ->
            val file = segmentFile(task.id, index)
            val validLength = file.length().coerceIn(0L, segment.length)
            if (file.length() != validLength) RandomAccessFile(file, "rw").use { it.setLength(validLength) }
            progress.set(index, validLength)
        }

        val initialDone = (0 until segmentCount).sumOf { progress.get(it) }
        DownloadRepository.updateProgress(
            task.id,
            initialDone,
            totalBytes,
            0L,
            "$segmentCount conexiones · reanudación por segmentos"
        )

        val latch = CountDownLatch(segmentCount)
        val firstError = AtomicReference<Throwable?>(null)
        val progressLock = Any()
        val lastUpdate = AtomicLong(System.currentTimeMillis())
        val lastBytes = AtomicLong(initialDone)

        segments.forEachIndexed { index, segment ->
            segmentExecutor.execute {
                try {
                    val maxRetries = SettingsRepository.settings.value.segmentRetryCount
                    var attempt = 0
                    while (!control.stopped.get()) {
                        try {
                            downloadRange(
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
                                lastBytes = lastBytes
                            )
                            break
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
                    latch.countDown()
                }
            }
        }

        latch.await()
        if (control.stopped.get()) return
        firstError.get()?.let { throw it }

        val finalFile = uniqueFinalFile(task.filename)
        mergeSegments(task.id, segmentCount, finalFile)
        verifySha256IfRequested(task.id, finalFile)
        deleteSegmentFiles(task.id)

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

    private fun downloadRange(
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
        lastBytes: AtomicLong
    ) {
        val file = segmentFile(task.id, index)
        file.parentFile?.mkdirs()
        val existing = file.length().coerceIn(0L, segment.length)
        progress.set(index, existing)
        if (existing >= segment.length) return

        val requestStart = segment.start + existing
        val builder = requestBuilder(task)
            .header("Range", "bytes=$requestStart-${segment.end}")
            .header("Accept-Encoding", "identity")
            .get()
        validator?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }

        val call = client.newCall(builder.build())
        control.calls.add(call)

        call.execute().use { response ->
            if (response.code != 206) throw IOException("El servidor dejó de aceptar rangos (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Respuesta sin contenido")

            RandomAccessFile(file, "rw").use { output ->
                output.seek(existing)
                body.byteStream().use { input ->
                    val buffer = ByteArray(512 * 1024)
                    while (!control.stopped.get()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (!ensureNetworkPolicy(task.id, control)) break
                        bandwidthLimiter.acquire(read)
                        output.write(buffer, 0, read)
                        progress.addAndGet(index, read.toLong())

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate.get() >= 800L) {
                            synchronized(progressLock) {
                                if (now - lastUpdate.get() >= 800L) {
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
                                    updateAggregateNotification()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!control.stopped.get() && file.length() < segment.length) {
            throw IOException("Segmento ${index + 1} incompleto")
        }
    }

    private fun downloadSingleWithRetries(task: DownloadTask, validator: String?, control: TransferControl) {
        val maxRetries = SettingsRepository.settings.value.segmentRetryCount
        var attempt = 0
        while (!control.stopped.get()) {
            try {
                downloadSingle(task, validator, control)
                return
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
            validator?.takeIf { it.isNotBlank() }?.let { builder.header("If-Range", it) }
        }

        val call = client.newCall(builder.build())
        control.calls.add(call)

        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Respuesta sin contenido")

            if (existing > 0 && response.code == 200) {
                RandomAccessFile(partial, "rw").use { it.setLength(0L) }
                existing = 0L
            }

            val filename = URLUtil.guessFileName(
                response.request.url.toString(),
                response.header("Content-Disposition"),
                response.header("Content-Type")
            ).takeIf { it.isNotBlank() } ?: task.filename

            val total = resolveTotalBytes(response, existing)
            DownloadRepository.updateMetadata(task.id, filename = filename, totalBytes = total.takeIf { it > 0 })

            var downloaded = existing
            var lastBytes = downloaded
            var lastTime = System.currentTimeMillis()

            RandomAccessFile(partial, "rw").use { output ->
                output.seek(existing)
                body.byteStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (!control.stopped.get()) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (!ensureNetworkPolicy(task.id, control)) break
                        bandwidthLimiter.acquire(read)
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 800L) {
                            val speed = ((downloaded - lastBytes) * 1000L) / (now - lastTime).coerceAtLeast(1L)
                            DownloadRepository.updateProgress(task.id, downloaded, total, speed, "1 conexión")
                            updateAggregateNotification()
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
                partial.copyTo(finalFile, overwrite = false)
                partial.delete()
            }

            verifySha256IfRequested(task.id, finalFile, restoreTo = partial)
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
    }

    private fun requestBuilder(task: DownloadTask): Request.Builder {
        val builder = Request.Builder().url(task.url)
        task.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        task.userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        return builder
    }

    private fun resolveTotalBytes(response: Response, existingBytes: Long): Long {
        val contentRange = response.header("Content-Range")
        val rangeTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
        if (rangeTotal != null && rangeTotal > 0) return rangeTotal
        val contentLength = response.body?.contentLength() ?: -1L
        return if (contentLength > 0) contentLength + existingBytes else -1L
    }

    private fun prepareSegmentLayout(id: String, total: Long, count: Int, validator: String?) {
        val meta = segmentMetaFile(id)
        val expected = "$total|$count|${validator.orEmpty()}"
        val existing = runCatching { meta.takeIf { it.exists() }?.readText() }.getOrNull()
        if (existing != expected) {
            deleteSegmentFiles(id)
            meta.parentFile?.mkdirs()
            meta.writeText(expected)
        }
    }


    private fun prepareSingleLayout(id: String, total: Long, validator: String?) {
        val meta = singleMetaFile(id)
        val expected = "$total|${validator.orEmpty()}"
        val existing = runCatching { meta.takeIf { it.exists() }?.readText() }.getOrNull()
        if (existing != expected && partialFile(id).exists()) {
            // Old or mismatched partial data cannot be safely resumed against a
            // resource whose validator/size we cannot prove is identical.
            runCatching { partialFile(id).delete() }
        }
        meta.parentFile?.mkdirs()
        meta.writeText(expected)
    }

    private fun mergeSegments(id: String, count: Int, destination: File) {
        BufferedOutputStream(FileOutputStream(destination), 512 * 1024).use { output ->
            for (index in 0 until count) {
                BufferedInputStream(FileInputStream(segmentFile(id, index)), 256 * 1024).use { input ->
                    input.copyTo(output, 256 * 1024)
                }
            }
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

    private fun verifySha256IfRequested(id: String, file: File, restoreTo: File? = null) {
        val expected = DownloadRepository.find(id)?.expectedSha256 ?: return
        val actual = sha256(file)
        DownloadRepository.updateHash(id, actual)
        if (!actual.equals(expected, ignoreCase = true)) {
            if (restoreTo != null) {
                runCatching {
                    if (restoreTo.exists()) restoreTo.delete()
                    file.renameTo(restoreTo)
                }
            } else {
                runCatching { file.delete() }
            }
            throw IOException("SHA-256 no coincide. Esperado ${expected.take(12)}… · obtenido ${actual.take(12)}…")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
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

    private fun deleteSegmentFiles(id: String) {
        downloadsDirectory().listFiles()?.forEach { file ->
            if (file.name == ".$id.segments" || file.name.startsWith(".$id.seg")) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupHttpParts(id: String) {
        runCatching { partialFile(id).delete() }
        runCatching { singleMetaFile(id).delete() }
        deleteSegmentFiles(id)
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
        stopSelf()
    }

    override fun onDestroy() {
        shuttingDown.set(true)
        active.forEach { (_, control) -> control.pause() }
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
        private const val CHANNEL_ID = "manager_downloads"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_PROCESS = "manager.action.PROCESS"
        private const val ACTION_PAUSE = "manager.action.PAUSE"
        private const val ACTION_RESUME = "manager.action.RESUME"
        private const val ACTION_CANCEL = "manager.action.CANCEL"
        private const val ACTION_PAUSE_ALL = "manager.action.PAUSE_ALL"
        private const val ACTION_RESUME_ALL = "manager.action.RESUME_ALL"
        private const val EXTRA_ID = "download_id"

        fun process(context: Context) = send(context, ACTION_PROCESS)
        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE, id)
        fun resume(context: Context, id: String) = send(context, ACTION_RESUME, id)
        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id)
        fun pauseAll(context: Context) = send(context, ACTION_PAUSE_ALL)
        fun resumeAll(context: Context) = send(context, ACTION_RESUME_ALL)

        private fun send(context: Context, action: String, id: String? = null) {
            val intent = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
