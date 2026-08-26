package com.managerdownloader.app.data

import android.content.Context
import android.net.Uri
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory queue with asynchronous atomic persistence.
 *
 * UI/network callers only mutate immutable snapshots under a short lock. JSON serialization and
 * disk writes are performed on Dispatchers.IO and coalesced, so progress updates cannot stall the
 * main thread or the HTTP workers behind a file-system write.
 */
object DownloadRepository {
    private const val FILE_NAME = "download_queue.json"
    private const val PERSIST_DEBOUNCE_MS = 300L
    private const val PROGRESS_PERSIST_INTERVAL_MS = 3_000L

    private val lock = Any()
    private lateinit var appContext: Context
    @Volatile private var initialized = false

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistSignals = Channel<Unit>(Channel.CONFLATED)
    private var persistenceWorkerStarted = false
    private val lastProgressPersistAt = AtomicLong(0L)

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    fun initialize(context: Context) {
        var shouldPersist = false
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            _downloads.value = load()
                .map {
                    if (it.status == DownloadStatus.ACTIVE) {
                        it.copy(
                            status = DownloadStatus.QUEUED,
                            speedBytesPerSecond = 0L,
                            detail = "Recuperada tras reinicio"
                        )
                    } else {
                        it.copy(speedBytesPerSecond = 0L)
                    }
                }
                .sortedBy { it.order }
                .mapIndexed { index, item -> item.copy(order = index) }
            initialized = true
            startPersistenceWorkerLocked()
            shouldPersist = true
        }
        if (shouldPersist) schedulePersist()
    }

    fun add(
        url: String,
        suggestedFilename: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
        referer: String? = null,
        kind: DownloadKind? = null,
        expectedSha256: String? = null,
        originalSourceUrl: String? = null,
        sourceFormatId: String? = null,
        secondaryUrl: String? = null,
        secondarySourceFormatId: String? = null,
        muxContainer: String? = null,
        sourceProfile: String? = null
    ): DownloadTask {
        val task = synchronized(lock) {
            ensureInitialized()
            val resolvedKind = kind ?: detectKind(url)
            val filename = sanitizeFilename(
                suggestedFilename?.takeIf { it.isNotBlank() }
                    ?: filenameFromUrl(url, resolvedKind)
            )
            DownloadTask(
                id = UUID.randomUUID().toString(),
                filename = filename,
                url = url.trim(),
                kind = resolvedKind,
                order = _downloads.value.size,
                cookie = cookie?.takeIf { it.isNotBlank() },
                userAgent = userAgent?.takeIf { it.isNotBlank() },
                referer = referer?.takeIf { it.isNotBlank() },
                expectedSha256 = normalizeSha256(expectedSha256),
                originalSourceUrl = originalSourceUrl?.takeIf { it.isNotBlank() },
                sourceFormatId = sourceFormatId?.takeIf { it.isNotBlank() },
                secondaryUrl = secondaryUrl?.takeIf { it.isNotBlank() },
                secondarySourceFormatId = secondarySourceFormatId?.takeIf { it.isNotBlank() },
                muxContainer = muxContainer?.lowercase()?.takeIf { it == "mp4" || it == "webm" },
                sourceProfile = sourceProfile?.takeIf { it.isNotBlank() }
            ).also { newTask ->
                _downloads.value = (_downloads.value + newTask).sortedBy { it.order }
            }
        }
        schedulePersist()
        return task
    }

    fun find(id: String): DownloadTask? = _downloads.value.firstOrNull { it.id == id }

    fun queued(limit: Int): List<DownloadTask> =
        _downloads.value.asSequence()
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedBy { it.order }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun nextQueued(): DownloadTask? = queued(1).firstOrNull()
    fun activeCount(): Int = _downloads.value.count { it.status == DownloadStatus.ACTIVE }
    fun hasQueued(): Boolean = _downloads.value.any { it.status == DownloadStatus.QUEUED }

    fun pause(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item else item.copy(
            status = DownloadStatus.PAUSED,
            speedBytesPerSecond = 0L,
            detail = "En pausa",
            error = null
        )
    }

    fun resume(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item else item.copy(
            status = DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            detail = "En cola",
            error = null
        )
    }

    fun waitForWifi(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item else item.copy(
            status = DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            detail = "Esperando Wi-Fi",
            error = null
        )
    }

    fun markActive(id: String, detail: String? = null) = update(id) {
        it.copy(status = DownloadStatus.ACTIVE, error = null, detail = detail)
    }

    fun markFailed(id: String, message: String) = update(id) {
        it.copy(
            status = DownloadStatus.FAILED,
            speedBytesPerSecond = 0L,
            detail = null,
            error = message.take(320)
        )
    }

    fun markCompleted(id: String, path: String, bytes: Long) = update(id) {
        it.copy(
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = bytes,
            totalBytes = if (it.totalBytes > 0) it.totalBytes else bytes,
            speedBytesPerSecond = 0L,
            outputPath = path,
            detail = "Completada",
            error = null
        )
    }

    fun updateProgress(
        id: String,
        downloaded: Long,
        total: Long,
        speed: Long,
        detail: String? = null
    ) {
        update(id, persist = false) {
            it.copy(
                bytesDownloaded = downloaded.coerceAtLeast(0L),
                totalBytes = total,
                speedBytesPerSecond = speed.coerceAtLeast(0L),
                detail = detail ?: it.detail
            )
        }
        val now = System.currentTimeMillis()
        val previous = lastProgressPersistAt.get()
        if (now - previous >= PROGRESS_PERSIST_INTERVAL_MS &&
            lastProgressPersistAt.compareAndSet(previous, now)
        ) {
            schedulePersist()
        }
    }

    fun updateMetadata(
        id: String,
        filename: String? = null,
        totalBytes: Long? = null,
        detail: String? = null
    ) = update(id) { item ->
        item.copy(
            filename = filename?.takeIf { it.isNotBlank() }?.let(::sanitizeFilename) ?: item.filename,
            totalBytes = totalBytes?.takeIf { it >= 0 } ?: item.totalBytes,
            detail = detail ?: item.detail
        )
    }

    fun updateUrl(
        id: String,
        url: String,
        cookie: String? = null,
        userAgent: String? = null,
        referer: String? = null
    ) = update(id) { item ->
        item.copy(
            url = url.trim(),
            cookie = cookie ?: item.cookie,
            userAgent = userAgent ?: item.userAgent,
            referer = referer ?: item.referer,
            status = if (item.status == DownloadStatus.COMPLETED) item.status else DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            error = null,
            detail = "Enlace actualizado · reanudación conservada"
        )
    }

    fun updateMuxUrls(
        id: String,
        videoUrl: String,
        audioUrl: String,
        videoFormatId: String?,
        audioFormatId: String?,
        container: String?,
        filename: String? = null
    ) = update(id) { item ->
        item.copy(
            url = videoUrl.trim(),
            secondaryUrl = audioUrl.trim(),
            sourceFormatId = videoFormatId ?: item.sourceFormatId,
            secondarySourceFormatId = audioFormatId ?: item.secondarySourceFormatId,
            muxContainer = container ?: item.muxContainer,
            filename = filename?.takeIf { it.isNotBlank() }?.let(::sanitizeFilename) ?: item.filename,
            error = null,
            detail = "Enlaces HD renovados"
        )
    }

    fun updateHash(id: String, actualSha256: String) = update(id) { item ->
        item.copy(actualSha256 = normalizeSha256(actualSha256))
    }

    fun updateOutputPath(id: String, outputPath: String) = update(id) { item ->
        item.copy(outputPath = outputPath, detail = "Archivo movido")
    }

    fun moveToTop(id: String) {
        var changed = false
        synchronized(lock) {
            ensureInitialized()
            val list = _downloads.value.sortedBy { it.order }.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index <= 0) return
            if (list[index].status in setOf(DownloadStatus.ACTIVE, DownloadStatus.COMPLETED)) return
            val moving = list.removeAt(index)
            list.add(0, moving)
            _downloads.value = list.mapIndexed { newIndex, item -> item.copy(order = newIndex) }
            changed = true
        }
        if (changed) schedulePersist()
    }

    /** Queue a persistence pass without blocking the caller. */
    fun flush() = schedulePersist()

    fun remove(id: String) {
        var changed = false
        synchronized(lock) {
            ensureInitialized()
            val newList = _downloads.value
                .filterNot { it.id == id }
                .sortedBy { it.order }
                .mapIndexed { index, item -> item.copy(order = index) }
            changed = newList.size != _downloads.value.size
            _downloads.value = newList
        }
        if (changed) schedulePersist()
    }

    fun move(id: String, direction: Int) {
        if (direction == 0) return
        var changed = false
        synchronized(lock) {
            ensureInitialized()
            val list = _downloads.value.sortedBy { it.order }.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index == -1 || list[index].status == DownloadStatus.ACTIVE) return
            val target = (index + direction).coerceIn(0, list.lastIndex)
            if (target == index) return
            val moving = list.removeAt(index)
            list.add(target, moving)
            _downloads.value = list.mapIndexed { newIndex, item -> item.copy(order = newIndex) }
            changed = true
        }
        if (changed) schedulePersist()
    }

    private fun update(
        id: String,
        persist: Boolean = true,
        transform: (DownloadTask) -> DownloadTask
    ) {
        var changed = false
        synchronized(lock) {
            ensureInitialized()
            val before = _downloads.value
            val after = before.map { if (it.id == id) transform(it) else it }.sortedBy { it.order }
            changed = after != before
            _downloads.value = after
        }
        if (persist && changed) schedulePersist()
    }

    private fun startPersistenceWorkerLocked() {
        if (persistenceWorkerStarted) return
        persistenceWorkerStarted = true
        persistenceScope.launch {
            for (ignored in persistSignals) {
                delay(PERSIST_DEBOUNCE_MS)
                while (persistSignals.tryReceive().isSuccess) {
                    // Coalesce bursts from UI/service updates into one atomic disk write.
                }
                persistSnapshot()
            }
        }
    }

    private fun schedulePersist() {
        if (!initialized) return
        persistSignals.trySend(Unit)
    }

    private fun persistSnapshot() {
        if (!initialized) return
        val snapshot = _downloads.value.sortedBy { it.order }
        val array = JSONArray()
        snapshot.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("filename", item.filename)
                put("url", item.url)
                put("kind", item.kind.name)
                put("status", item.status.name)
                put("bytesDownloaded", item.bytesDownloaded)
                put("totalBytes", item.totalBytes)
                put("speedBytesPerSecond", item.speedBytesPerSecond)
                put("order", item.order)
                put("outputPath", item.outputPath ?: JSONObject.NULL)
                put("error", item.error ?: JSONObject.NULL)
                put("detail", item.detail ?: JSONObject.NULL)
                put("cookie", item.cookie ?: JSONObject.NULL)
                put("userAgent", item.userAgent ?: JSONObject.NULL)
                put("referer", item.referer ?: JSONObject.NULL)
                put("expectedSha256", item.expectedSha256 ?: JSONObject.NULL)
                put("actualSha256", item.actualSha256 ?: JSONObject.NULL)
                put("originalSourceUrl", item.originalSourceUrl ?: JSONObject.NULL)
                put("sourceFormatId", item.sourceFormatId ?: JSONObject.NULL)
                put("secondaryUrl", item.secondaryUrl ?: JSONObject.NULL)
                put("secondarySourceFormatId", item.secondarySourceFormatId ?: JSONObject.NULL)
                put("muxContainer", item.muxContainer ?: JSONObject.NULL)
                put("sourceProfile", item.sourceProfile ?: JSONObject.NULL)
            })
        }

        val atomicFile = AtomicFile(queueFile())
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (_: Throwable) {
            if (stream != null) runCatching { atomicFile.failWrite(stream) }
        }
    }

    private fun load(): List<DownloadTask> {
        val file = queueFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val content = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(content)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.getString("url")
                    add(
                        DownloadTask(
                            id = obj.getString("id"),
                            filename = sanitizeFilename(obj.optString("filename", "descarga")),
                            url = url,
                            kind = runCatching {
                                DownloadKind.valueOf(obj.optString("kind", detectKind(url).name))
                            }.getOrDefault(detectKind(url)),
                            status = runCatching {
                                DownloadStatus.valueOf(obj.getString("status"))
                            }.getOrDefault(DownloadStatus.QUEUED),
                            bytesDownloaded = obj.optLong("bytesDownloaded", 0L).coerceAtLeast(0L),
                            totalBytes = obj.optLong("totalBytes", -1L),
                            speedBytesPerSecond = 0L,
                            order = obj.optInt("order", i),
                            outputPath = obj.optNullableString("outputPath"),
                            error = obj.optNullableString("error"),
                            detail = obj.optNullableString("detail"),
                            cookie = obj.optNullableString("cookie"),
                            userAgent = obj.optNullableString("userAgent"),
                            referer = obj.optNullableString("referer"),
                            expectedSha256 = normalizeSha256(obj.optNullableString("expectedSha256")),
                            actualSha256 = normalizeSha256(obj.optNullableString("actualSha256")),
                            originalSourceUrl = obj.optNullableString("originalSourceUrl"),
                            sourceFormatId = obj.optNullableString("sourceFormatId"),
                            secondaryUrl = obj.optNullableString("secondaryUrl"),
                            secondarySourceFormatId = obj.optNullableString("secondarySourceFormatId"),
                            muxContainer = obj.optNullableString("muxContainer"),
                            sourceProfile = obj.optNullableString("sourceProfile")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, null)

    private fun queueFile(): File = File(appContext.filesDir, FILE_NAME)

    private fun detectKind(url: String): DownloadKind {
        val lower = url.trim().lowercase()
        return if (
            lower.startsWith("magnet:") ||
            lower.startsWith("content:") ||
            lower.startsWith("file:") ||
            lower.substringBefore('?').endsWith(".torrent")
        ) DownloadKind.TORRENT else DownloadKind.HTTP
    }

    private fun filenameFromUrl(url: String, kind: DownloadKind): String {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val display = runCatching { Uri.parse(url).getQueryParameter("dn") }.getOrNull()
            return display?.takeIf { it.isNotBlank() } ?: "Magnet torrent"
        }
        val candidate = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        return candidate?.takeIf { it.isNotBlank() }
            ?: if (kind == DownloadKind.TORRENT) "Torrent-${System.currentTimeMillis()}"
            else "descarga-${System.currentTimeMillis()}"
    }

    private fun sanitizeFilename(value: String): String {
        var clean = value
            .replace("\u0000", "")
            .replace(Regex("""[\\/:*?\"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(' ', '.')
            .take(180)
            .trim(' ', '.')
        if (clean.isBlank() || clean == "." || clean == "..") {
            clean = "descarga-${System.currentTimeMillis()}"
        }
        return clean
    }

    private fun normalizeSha256(value: String?): String? {
        val clean = value?.trim()?.lowercase()?.replace(" ", "").orEmpty()
        return clean.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }

    private fun ensureInitialized() {
        check(initialized) { "DownloadRepository must be initialized first" }
    }
}
