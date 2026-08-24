package com.managerdownloader.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.managerdownloader.app.MainActivity
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.DownloadStatus
import com.managerdownloader.app.data.DownloadTask
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var activeId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopRequested.set(false)
        ensureForeground("Preparando cola…")

        when (intent?.action) {
            ACTION_PAUSE -> {
                intent.getStringExtra(EXTRA_ID)?.let { id ->
                    DownloadRepository.pause(id)
                    if (activeId == id) activeCall?.cancel()
                }
            }

            ACTION_RESUME -> {
                intent.getStringExtra(EXTRA_ID)?.let(DownloadRepository::resume)
            }

            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_ID)?.let { id ->
                    if (activeId == id) activeCall?.cancel()
                    deletePartial(id)
                    DownloadRepository.remove(id)
                }
            }
        }

        processQueue()
        return START_STICKY
    }

    private fun processQueue() {
        if (!processing.compareAndSet(false, true)) return

        executor.execute {
            try {
                while (!stopRequested.get()) {
                    val task = DownloadRepository.nextQueued() ?: break
                    DownloadRepository.markActive(task.id)
                    performDownload(task)
                }
            } finally {
                processing.set(false)
                DownloadRepository.flush()

                if (DownloadRepository.nextQueued() != null) {
                    processQueue()
                } else {
                    activeCall = null
                    activeId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun performDownload(original: DownloadTask) {
        val latest = DownloadRepository.find(original.id) ?: return
        if (latest.status != DownloadStatus.ACTIVE) return

        activeId = latest.id
        val partialFile = partialFile(latest.id)
        partialFile.parentFile?.mkdirs()

        var existingBytes = partialFile.length().coerceAtLeast(0L)

        val requestBuilder = Request.Builder()
            .url(latest.url)
            .get()

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        latest.cookie?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Cookie", it)
        }

        latest.userAgent?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("User-Agent", it)
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Respuesta sin contenido")

                if (existingBytes > 0L && response.code == 200) {
                    RandomAccessFile(partialFile, "rw").use { it.setLength(0L) }
                    existingBytes = 0L
                }

                val totalBytes = resolveTotalBytes(response, existingBytes)
                var downloaded = existingBytes

                RandomAccessFile(partialFile, "rw").use { output ->
                    output.seek(existingBytes)

                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var lastUpdateAt = System.currentTimeMillis()
                        var bytesAtLastUpdate = downloaded

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break

                            val current = DownloadRepository.find(latest.id) ?: return
                            if (current.status == DownloadStatus.PAUSED) {
                                call.cancel()
                                return
                            }

                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateAt >= 700) {
                                val elapsedMs = (now - lastUpdateAt).coerceAtLeast(1L)
                                val delta = downloaded - bytesAtLastUpdate
                                val speed = (delta * 1000L) / elapsedMs

                                DownloadRepository.updateProgress(
                                    latest.id,
                                    downloaded,
                                    totalBytes,
                                    speed
                                )
                                updateNotification(latest.filename, downloaded, totalBytes)

                                lastUpdateAt = now
                                bytesAtLastUpdate = downloaded
                            }
                        }
                    }
                }

                val finalFile = uniqueFinalFile(latest.filename)
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = false)
                    partialFile.delete()
                }

                DownloadRepository.markCompleted(
                    latest.id,
                    finalFile.absolutePath,
                    finalFile.length()
                )
                showCompletedNotification(latest.filename)
            }
        } catch (error: Exception) {
            val current = DownloadRepository.find(latest.id)
            if (current == null || current.status == DownloadStatus.PAUSED) {
                return
            }

            if (call.isCanceled()) {
                return
            }

            DownloadRepository.markFailed(
                latest.id,
                error.message ?: "Error de descarga"
            )
        } finally {
            activeCall = null
            activeId = null
            DownloadRepository.flush()
        }
    }

    private fun resolveTotalBytes(response: Response, existingBytes: Long): Long {
        val contentRange = response.header("Content-Range")
        if (!contentRange.isNullOrBlank()) {
            val total = contentRange.substringAfterLast('/').toLongOrNull()
            if (total != null && total > 0) return total
        }

        val contentLength = response.body?.contentLength() ?: -1L
        return if (contentLength > 0) contentLength + existingBytes else -1L
    }

    private fun downloadsDirectory(): File {
        val external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val base = external ?: File(filesDir, "downloads")
        return File(base, "ManagerDownloader").apply { mkdirs() }
    }

    private fun partialFile(id: String): File =
        File(downloadsDirectory(), ".$id.part")

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

    private fun deletePartial(id: String) {
        runCatching { partialFile(id).delete() }
    }

    private fun ensureForeground(text: String) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Manager Downloader", text, -1)
        )
    }

    private fun updateNotification(filename: String, downloaded: Long, total: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(filename, formatProgress(downloaded, total), progressPercent(downloaded, total))
        )
    }

    private fun showCompletedNotification(filename: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
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

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent())

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Descargas",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progreso de las descargas"
                }
            )
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopRequested.set(true)
        activeId?.let(DownloadRepository::pause)
        activeCall?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        activeCall?.cancel()
        executor.shutdownNow()
        DownloadRepository.flush()
        super.onDestroy()
    }

    private fun formatProgress(downloaded: Long, total: Long): String =
        if (total > 0) "${formatBytes(downloaded)} de ${formatBytes(total)}"
        else formatBytes(downloaded)

    private fun progressPercent(downloaded: Long, total: Long): Int =
        if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        else -1

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
        private const val EXTRA_ID = "download_id"

        fun process(context: Context) =
            send(context, ACTION_PROCESS)

        fun pause(context: Context, id: String) =
            send(context, ACTION_PAUSE, id)

        fun resume(context: Context, id: String) =
            send(context, ACTION_RESUME, id)

        fun cancel(context: Context, id: String) =
            send(context, ACTION_CANCEL, id)

        private fun send(context: Context, action: String, id: String? = null) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
