package com.managerdownloader.app.download

import android.content.Context
import android.net.Uri
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.CacheFlushedAlert
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.DownloadStatus
import com.managerdownloader.app.data.DownloadTask
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.data.StorageRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Torrent facade backed by a process-wide SessionManager. Pausing/stopping the Android service no
 * longer destroys/recreates libtorrent while JNI/native threads are still releasing sockets.
 */
internal class TorrentEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val baseDirectory: () -> File
) {
    private val magnetExecutor = Executors.newCachedThreadPool()

    fun ensureStarted(maxActive: Int) {
        TorrentSessionHolder.ensureStarted(maxActive)
    }

    fun download(task: DownloadTask, control: TransferControl, maxActive: Int) {
        ensureStarted(maxActive)
        val saveDir = torrentDirectory(task.id)
        saveDir.mkdirs()

        val info = loadTorrentInfo(task, control)
        if (control.stopped.get()) return

        DownloadRepository.updateMetadata(
            id = task.id,
            filename = info.name(),
            totalBytes = info.totalSize(),
            detail = "Buscando pares…"
        )

        var handle = TorrentSessionHolder.find(info)
        if (handle == null) {
            TorrentSessionHolder.download(info, saveDir)
            val deadline = System.currentTimeMillis() + 15_000L
            while (handle == null && System.currentTimeMillis() < deadline && !control.stopped.get()) {
                sleepCancelable(control, 200L)
                if (!control.stopped.get()) handle = TorrentSessionHolder.find(info)
            }
        }

        if (control.stopped.get()) return
        val torrentHandle = handle ?: throw IOException("No se pudo iniciar el torrent")
        TorrentSessionHolder.registerHandle(task.id, torrentHandle)
        control.torrentHandle = torrentHandle
        runCatching { torrentHandle.resume() }

        try {
            while (!control.stopped.get()) {
                TorrentSessionHolder.applyDownloadRateLimit()

                val current = DownloadRepository.find(task.id) ?: return
                if (current.status == DownloadStatus.PAUSED) {
                    runCatching { torrentHandle.pause() }
                    return
                }

                if (!runCatching { torrentHandle.isValid }.getOrDefault(false)) return
                val status = runCatching { torrentHandle.status() }.getOrNull() ?: return
                val total = status.totalWanted().takeIf { it > 0L } ?: info.totalSize()
                val done = status.totalWantedDone().coerceAtLeast(status.totalDone())
                val peers = status.numPeers()
                val seeds = status.numSeeds()
                val detail = when {
                    !status.hasMetadata() -> "Obteniendo metadatos…"
                    peers == 0 -> "Buscando pares…"
                    else -> "$peers pares · $seeds semillas"
                }

                DownloadRepository.updateProgress(
                    id = task.id,
                    downloaded = done,
                    total = total,
                    speed = status.downloadRate().toLong(),
                    detail = detail
                )

                if (status.isFinished()) {
                    TorrentSessionHolder.flushAndRemove(torrentHandle, 4_000L)
                    TorrentSessionHolder.unregisterHandle(task.id)
                    control.torrentHandle = null
                    val publishedPath = StorageRepository.publishTorrentDirectory(
                        saveDir,
                        info.name(),
                        baseDirectory()
                    )
                    DownloadRepository.markCompleted(task.id, publishedPath, total.coerceAtLeast(done))
                    return
                }

                sleepCancelable(control, 800L)
            }
        } finally {
            if (control.deleteOnStop.get()) {
                TorrentSessionHolder.flushAndRemove(torrentHandle, 1_500L)
                TorrentSessionHolder.unregisterHandle(task.id)
                deleteRecursivelySafe(saveDir)
            } else if (control.stopped.get()) {
                runCatching { torrentHandle.pause() }
            }
        }
    }

    private fun sleepCancelable(control: TransferControl, totalMs: Long) {
        var remaining = totalMs.coerceAtLeast(0L)
        while (remaining > 0L && !control.stopped.get()) {
            val slice = minOf(remaining, 100L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            remaining -= slice
        }
    }

    fun pause(id: String) = TorrentSessionHolder.pauseHandle(id)
    fun resume(id: String) = TorrentSessionHolder.resumeHandle(id)

    fun cancel(id: String) {
        TorrentSessionHolder.cancelHandle(id)
        deleteRecursivelySafe(torrentDirectory(id))
    }

    /** Service teardown only pauses handles. The native SessionManager remains process-wide. */
    fun stopAsync() {
        magnetExecutor.shutdown()
        TorrentSessionHolder.pauseAllHandles()
    }

    private fun loadTorrentInfo(task: DownloadTask, control: TransferControl): TorrentInfo {
        val source = task.url.trim()
        return when {
            source.startsWith("magnet:", ignoreCase = true) -> {
                DownloadRepository.updateMetadata(task.id, detail = "Obteniendo metadatos del magnet…")
                val metadata = fetchMagnetCancelable(source, control)
                    ?: throw IOException("No se pudieron obtener los metadatos del magnet")
                if (control.stopped.get()) throw IOException("Transferencia detenida")
                TorrentInfo(metadata)
            }
            source.startsWith("content:", ignoreCase = true) -> {
                val bytes = context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
                    input.readBytesLimited(MAX_TORRENT_METADATA_BYTES)
                } ?: throw IOException("No se pudo abrir el archivo .torrent")
                TorrentInfo(bytes)
            }
            source.startsWith("file:", ignoreCase = true) -> {
                val file = File(Uri.parse(source).path ?: throw IOException("Ruta .torrent inválida"))
                TorrentInfo(file)
            }
            else -> {
                if (!source.startsWith("https://", ignoreCase = true)) {
                    throw IOException("Por seguridad, los .torrent remotos requieren HTTPS")
                }
                val requestBuilder = Request.Builder()
                    .url(source)
                    .header("Accept-Encoding", "identity")
                    .get()
                task.cookie?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Cookie", it) }
                task.userAgent?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("User-Agent", it) }
                task.referer?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Referer", it) }
                val call = httpClient.newCall(requestBuilder.build())
                control.calls.add(call)
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} al obtener .torrent")
                        val bytes = response.body?.byteStream()?.use { it.readBytesLimited(MAX_TORRENT_METADATA_BYTES) }
                            ?: throw IOException("Archivo .torrent vacío")
                        TorrentInfo(bytes)
                    }
                } finally {
                    control.calls.remove(call)
                }
            }
        }
    }

    private fun fetchMagnetCancelable(source: String, control: TransferControl): ByteArray? {
        val future = magnetExecutor.submit<ByteArray?> {
            TorrentSessionHolder.fetchMagnet(source, 60, context.cacheDir)
        }
        try {
            while (!control.stopped.get()) {
                try {
                    return future.get(200L, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Cooperative polling.
                }
            }
            future.cancel(false)
            throw IOException("Transferencia detenida")
        } finally {
            if (control.stopped.get()) future.cancel(false)
        }
    }

    private fun torrentDirectory(id: String): File = File(File(baseDirectory(), "Torrents"), id)

    private fun deleteRecursivelySafe(file: File) {
        runCatching {
            if (file.exists() && file.canonicalPath.startsWith(baseDirectory().canonicalPath)) {
                file.deleteRecursively()
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(32 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw IOException("El archivo .torrent supera el límite de metadatos")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_TORRENT_METADATA_BYTES = 16 * 1024 * 1024
    }
}

/** Process-wide owner for FrostWire/libtorrent native state. */
internal object TorrentSessionHolder {
    private val sessionLock = ReentrantLock()
    private val manager = SessionManager()
    private val started = AtomicBoolean(false)
    private val handles = ConcurrentHashMap<String, TorrentHandle>()

    fun ensureStarted(maxActive: Int) = sessionLock.withLock {
        if (started.compareAndSet(false, true)) {
            manager.start()
            manager.maxActiveSeeds(0)
            manager.maxConnections(200)
            manager.maxPeers(400)
        }
        manager.maxActiveDownloads(maxActive.coerceIn(1, 4))
        applyDownloadRateLimit()
    }

    fun find(info: TorrentInfo): TorrentHandle? = runCatching { manager.find(info) }.getOrNull()

    fun download(info: TorrentInfo, saveDir: File) {
        runCatching { manager.download(info, saveDir) }
    }

    fun fetchMagnet(source: String, timeoutSeconds: Int, extraSaveDir: File): ByteArray? =
        runCatching { manager.fetchMagnet(source, timeoutSeconds, extraSaveDir) }.getOrNull()

    fun registerHandle(id: String, handle: TorrentHandle) {
        handles[id] = handle
    }

    fun unregisterHandle(id: String) {
        handles.remove(id)
    }

    fun pauseHandle(id: String) {
        runCatching { handles[id]?.pause() }
    }

    fun resumeHandle(id: String) {
        runCatching { handles[id]?.resume() }
    }

    fun cancelHandle(id: String) {
        val handle = handles.remove(id)
        if (handle != null) flushAndRemove(handle, 1_500L)
    }

    fun pauseAllHandles() {
        handles.values.forEach { runCatching { it.pause() } }
    }

    fun flushAndRemove(handle: TorrentHandle, timeoutMs: Long) {
        val infoHash = runCatching { handle.infoHash().toString() }.getOrNull()
        val flushed = CountDownLatch(1)
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(AlertType.CACHE_FLUSHED.swig())
            override fun alert(alert: Alert<*>) {
                val cacheAlert = alert as? CacheFlushedAlert ?: return
                val matches = infoHash == null || runCatching {
                    cacheAlert.handle().infoHash().toString() == infoHash
                }.getOrDefault(false)
                if (matches) flushed.countDown()
            }
        }

        runCatching { manager.addListener(listener) }
        try {
            runCatching { handle.pause() }
            runCatching { handle.flushCache() }
            runCatching { flushed.await(timeoutMs.coerceAtLeast(250L), TimeUnit.MILLISECONDS) }
        } finally {
            runCatching { manager.removeListener(listener) }
            runCatching { manager.remove(handle) }
        }
    }

    fun applyDownloadRateLimit() {
        val desiredLimit = SettingsRepository.settings.value.bandwidthLimitBytesPerSecond
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        runCatching {
            if (manager.downloadRateLimit() != desiredLimit) manager.downloadRateLimit(desiredLimit)
        }
    }
}
