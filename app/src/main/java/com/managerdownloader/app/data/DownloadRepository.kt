package com.managerdownloader.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadRepository {
    private const val FILE_NAME = "download_queue.json"
    private val lock = Any()

    private lateinit var appContext: Context
    private var initialized = false

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            _downloads.value = load()
                .map {
                    if (it.status == DownloadStatus.ACTIVE) {
                        it.copy(
                            status = DownloadStatus.QUEUED,
                            speedBytesPerSecond = 0L,
                            detail = null
                        )
                    } else {
                        it
                    }
                }
                .sortedBy { it.order }
                .mapIndexed { index, item -> item.copy(order = index) }
            initialized = true
            persistLocked()
        }
    }

    fun add(
        url: String,
        suggestedFilename: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
        kind: DownloadKind? = null,
        expectedSha256: String? = null
    ): DownloadTask = synchronized(lock) {
        ensureInitialized()
        val resolvedKind = kind ?: detectKind(url)
        val filename = sanitizeFilename(
            suggestedFilename?.takeIf { it.isNotBlank() }
                ?: filenameFromUrl(url, resolvedKind)
        )
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            filename = filename,
            url = url,
            kind = resolvedKind,
            order = _downloads.value.size,
            cookie = cookie,
            userAgent = userAgent,
            expectedSha256 = normalizeSha256(expectedSha256)
        )
        _downloads.value = (_downloads.value + task).sortedBy { it.order }
        persistLocked()
        task
    }

    fun find(id: String): DownloadTask? =
        _downloads.value.firstOrNull { it.id == id }

    fun queued(limit: Int): List<DownloadTask> =
        _downloads.value
            .asSequence()
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedBy { it.order }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun nextQueued(): DownloadTask? = queued(1).firstOrNull()

    fun activeCount(): Int =
        _downloads.value.count { it.status == DownloadStatus.ACTIVE }

    fun hasQueued(): Boolean =
        _downloads.value.any { it.status == DownloadStatus.QUEUED }

    fun pause(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item
        else item.copy(
            status = DownloadStatus.PAUSED,
            speedBytesPerSecond = 0L,
            detail = "En pausa",
            error = null
        )
    }

    fun resume(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item
        else item.copy(
            status = DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            detail = "En cola",
            error = null
        )
    }

    fun waitForWifi(id: String) = update(id) { item ->
        if (item.status == DownloadStatus.COMPLETED) item
        else item.copy(
            status = DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            detail = "Esperando Wi-Fi",
            error = null
        )
    }

    fun markActive(id: String, detail: String? = null) = update(id) {
        it.copy(
            status = DownloadStatus.ACTIVE,
            error = null,
            detail = detail
        )
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
    ) = update(id, persist = false) {
        it.copy(
            bytesDownloaded = downloaded.coerceAtLeast(0L),
            totalBytes = total,
            speedBytesPerSecond = speed.coerceAtLeast(0L),
            detail = detail ?: it.detail
        )
    }

    fun updateMetadata(
        id: String,
        filename: String? = null,
        totalBytes: Long? = null,
        detail: String? = null
    ) = update(id) { item ->
        item.copy(
            filename = filename
                ?.takeIf { it.isNotBlank() }
                ?.let(::sanitizeFilename)
                ?: item.filename,
            totalBytes = totalBytes?.takeIf { it >= 0 } ?: item.totalBytes,
            detail = detail ?: item.detail
        )
    }

    fun updateUrl(
        id: String,
        url: String,
        cookie: String? = null,
        userAgent: String? = null
    ) = update(id) { item ->
        item.copy(
            url = url.trim(),
            cookie = cookie ?: item.cookie,
            userAgent = userAgent ?: item.userAgent,
            status = if (item.status == DownloadStatus.COMPLETED) item.status else DownloadStatus.QUEUED,
            speedBytesPerSecond = 0L,
            error = null,
            detail = "Enlace actualizado · reanudación conservada"
        )
    }

    fun updateHash(id: String, actualSha256: String) = update(id) { item ->
        item.copy(actualSha256 = normalizeSha256(actualSha256))
    }

    fun updateOutputPath(id: String, outputPath: String) = update(id) { item ->
        item.copy(outputPath = outputPath, detail = "Archivo movido")
    }

    fun moveToTop(id: String) = synchronized(lock) {
        ensureInitialized()
        val list = _downloads.value.sortedBy { it.order }.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        if (list[index].status == DownloadStatus.ACTIVE || list[index].status == DownloadStatus.COMPLETED) return
        val moving = list.removeAt(index)
        list.add(0, moving)
        _downloads.value = list.mapIndexed { newIndex, item -> item.copy(order = newIndex) }
        persistLocked()
    }

    fun flush() = synchronized(lock) {
        ensureInitialized()
        persistLocked()
    }

    fun remove(id: String) = synchronized(lock) {
        ensureInitialized()
        _downloads.value = _downloads.value
            .filterNot { it.id == id }
            .sortedBy { it.order }
            .mapIndexed { index, item -> item.copy(order = index) }
        persistLocked()
    }

    fun move(id: String, direction: Int) = synchronized(lock) {
        ensureInitialized()
        if (direction == 0) return
        val list = _downloads.value.sortedBy { it.order }.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return
        if (list[index].status == DownloadStatus.ACTIVE) return

        val target = (index + direction).coerceIn(0, list.lastIndex)
        if (target == index) return

        val moving = list.removeAt(index)
        list.add(target, moving)

        _downloads.value = list.mapIndexed { newIndex, item ->
            item.copy(order = newIndex)
        }
        persistLocked()
    }

    private fun update(
        id: String,
        persist: Boolean = true,
        transform: (DownloadTask) -> DownloadTask
    ) = synchronized(lock) {
        ensureInitialized()
        _downloads.value = _downloads.value.map {
            if (it.id == id) transform(it) else it
        }.sortedBy { it.order }
        if (persist) persistLocked()
    }

    private fun persistLocked() {
        if (!initialized) return
        val array = JSONArray()
        _downloads.value.sortedBy { it.order }.forEach { item ->
            array.put(
                JSONObject().apply {
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
                    put("expectedSha256", item.expectedSha256 ?: JSONObject.NULL)
                    put("actualSha256", item.actualSha256 ?: JSONObject.NULL)
                }
            )
        }
        runCatching { queueFile().writeText(array.toString()) }
    }

    private fun load(): List<DownloadTask> {
        val file = queueFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.getString("url")
                    add(
                        DownloadTask(
                            id = obj.getString("id"),
                            filename = obj.getString("filename"),
                            url = url,
                            kind = runCatching {
                                DownloadKind.valueOf(obj.optString("kind", detectKind(url).name))
                            }.getOrDefault(detectKind(url)),
                            status = runCatching {
                                DownloadStatus.valueOf(obj.getString("status"))
                            }.getOrDefault(DownloadStatus.QUEUED),
                            bytesDownloaded = obj.optLong("bytesDownloaded", 0L),
                            totalBytes = obj.optLong("totalBytes", -1L),
                            speedBytesPerSecond = 0L,
                            order = obj.optInt("order", i),
                            outputPath = obj.optNullableString("outputPath"),
                            error = obj.optNullableString("error"),
                            detail = obj.optNullableString("detail"),
                            cookie = obj.optNullableString("cookie"),
                            userAgent = obj.optNullableString("userAgent"),
                            expectedSha256 = normalizeSha256(obj.optNullableString("expectedSha256")),
                            actualSha256 = normalizeSha256(obj.optNullableString("actualSha256"))
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, null)

    private fun queueFile(): File =
        File(appContext.filesDir, FILE_NAME)

    private fun detectKind(url: String): DownloadKind {
        val lower = url.trim().lowercase()
        return if (
            lower.startsWith("magnet:") ||
            lower.startsWith("content:") ||
            lower.startsWith("file:") ||
            lower.substringBefore('?').endsWith(".torrent")
        ) {
            DownloadKind.TORRENT
        } else {
            DownloadKind.HTTP
        }
    }

    private fun filenameFromUrl(url: String, kind: DownloadKind): String {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val display = runCatching {
                Uri.parse(url).getQueryParameter("dn")
            }.getOrNull()
            return display?.takeIf { it.isNotBlank() } ?: "Magnet torrent"
        }

        val candidate = runCatching {
            Uri.parse(url).lastPathSegment
        }.getOrNull()

        return candidate?.takeIf { it.isNotBlank() }
            ?: if (kind == DownloadKind.TORRENT) {
                "Torrent-${System.currentTimeMillis()}"
            } else {
                "descarga-${System.currentTimeMillis()}"
            }
    }

    private fun sanitizeFilename(value: String): String {
        val clean = value
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(180)
        return clean.ifBlank { "descarga-${System.currentTimeMillis()}" }
    }

    private fun normalizeSha256(value: String?): String? {
        val clean = value?.trim()?.lowercase()?.replace(" ", "").orEmpty()
        return clean.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }

    private fun ensureInitialized() {
        check(initialized) { "DownloadRepository must be initialized first" }
    }
}
