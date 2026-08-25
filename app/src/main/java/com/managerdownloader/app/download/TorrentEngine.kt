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
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.data.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/** BitTorrent support backed by FrostWire jlibtorrent/libtorrent. */
internal class TorrentEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val baseDirectory: () -> File
) {
    private val manager = SessionManager()
    private val started = AtomicBoolean(false)
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val magnetExecutor = Executors.newCachedThreadPool()

    @Synchronized
    fun ensureStarted(maxActive: Int) {
        if (started.compareAndSet(false, true)) {
            manager.start()
            // Download-manager behavior: don't intentionally keep finished torrents seeding.
            manager.maxActiveSeeds(0)
            manager.maxConnections(150)
            manager.maxPeers(300)
        }
        manager.maxActiveDownloads(maxActive.coerceIn(1, 4))
        applyDownloadRateLimit()
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

        var handle = manager.find(info)
        if (handle == null) {
            manager.download(info, saveDir)
            val deadline = System.currentTimeMillis() + 15_000L
            while (handle == null && System.currentTimeMillis() < deadline && !control.stopped.get()) {
                Thread.sleep(250)
                handle = manager.find(info)
            }
        }

        val torrentHandle = handle ?: throw IOException("No se pudo iniciar el torrent")
        handles[task.id] = torrentHandle
        control.torrentHandle = torrentHandle
        torrentHandle.resume()

        try {
            while (!control.stopped.get()) {
                applyDownloadRateLimit()

                val current = DownloadRepository.find(task.id) ?: return
                if (current.status == DownloadStatus.PAUSED) {
                    torrentHandle.pause()
                    return
                }

                val status = torrentHandle.status()
                val total = status.totalWanted().takeIf { it > 0L }
                    ?: info.totalSize()
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
                    flushAndRemove(torrentHandle, 4_000L)
                    handles.remove(task.id)
                    control.torrentHandle = null
                    val publishedPath = StorageRepository.publishTorrentDirectory(
                        saveDir,
                        info.name(),
                        baseDirectory()
                    )
                    DownloadRepository.markCompleted(
                        task.id,
                        publishedPath,
                        total.coerceAtLeast(done)
                    )
                    return
                }

                Thread.sleep(900)
            }
        } finally {
            if (control.deleteOnStop.get()) {
                flushAndRemove(torrentHandle, 1_500L)
                handles.remove(task.id)
                deleteRecursivelySafe(saveDir)
            }
        }
    }

    private fun applyDownloadRateLimit() {
        val desiredLimit = SettingsRepository.settings.value.bandwidthLimitBytesPerSecond
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        runCatching {
            if (manager.downloadRateLimit() != desiredLimit) {
                manager.downloadRateLimit(desiredLimit)
            }
        }
    }

    fun pause(id: String) {
        runCatching { handles[id]?.pause() }
    }

    fun resume(id: String) {
        runCatching { handles[id]?.resume() }
    }

    fun cancel(id: String) {
        val handle = handles.remove(id)
        if (handle != null) flushAndRemove(handle, 1_500L)
        deleteRecursivelySafe(torrentDirectory(id))
    }

    fun stopAsync() {
        magnetExecutor.shutdownNow()
        if (!started.get()) return
        Thread({ runCatching { manager.stop() } }, "torrent-session-stop").start()
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
            manager.fetchMagnet(source, 60, context.cacheDir)
        }
        try {
            while (!control.stopped.get()) {
                try {
                    return future.get(250L, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll cancellation so Pause/Cancel never waits for fetchMagnet's full timeout.
                }
            }
            future.cancel(true)
            throw IOException("Transferencia detenida")
        } finally {
            if (control.stopped.get()) future.cancel(true)
        }
    }

    private fun flushAndRemove(handle: TorrentHandle, timeoutMs: Long) {
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

    private fun torrentDirectory(id: String): File =
        File(File(baseDirectory(), "Torrents"), id)

    private fun deleteRecursivelySafe(file: File) {
        runCatching {
            if (file.exists() && file.canonicalPath.startsWith(baseDirectory().canonicalPath)) {
                file.deleteRecursively()
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val buffer = ByteArray(16 * 1024)
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
