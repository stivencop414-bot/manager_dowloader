package com.managerdownloader.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadCategory(val folderName: String) {
    VIDEOS("Videos"),
    IMAGES("Imagenes"),
    AUDIO("Audio"),
    COMPRESSED("Comprimidos"),
    PROGRAMS("Programas"),
    DOCUMENTS("Documentos"),
    TORRENTS("Torrents"),
    OTHER("Otros")
}

object StorageRepository {
    private const val PREFS = "storage_settings"
    private const val KEY_TREE_URI = "download_tree_uri"
    private const val KEY_PROMPTED = "storage_prompted"

    private lateinit var appContext: Context
    private var initialized = false

    private val _treeUri = MutableStateFlow<String?>(null)
    val treeUri: StateFlow<String?> = _treeUri.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        _treeUri.value = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
        initialized = true
    }

    fun wasPrompted(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPTED, false)

    fun markPrompted() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    fun setTreeUri(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        _treeUri.value = uri.toString()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putBoolean(KEY_PROMPTED, true)
            .apply()
    }

    fun clearTreeUri() {
        _treeUri.value = null
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TREE_URI).apply()
    }

    fun selectedLabel(): String {
        val value = _treeUri.value ?: return "Carpeta interna de Manager Downloader"
        return runCatching {
            val uri = Uri.parse(value)
            val name = DocumentFile.fromTreeUri(appContext, uri)?.name
            if (name.isNullOrBlank()) value else "$name · $value"
        }.getOrDefault(value)
    }

    fun categoryFor(filename: String, kind: DownloadKind = DownloadKind.HTTP): DownloadCategory {
        if (kind == DownloadKind.TORRENT) return DownloadCategory.TORRENTS
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "mpeg", "mpg" -> DownloadCategory.VIDEOS
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "heic", "avif" -> DownloadCategory.IMAGES
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma" -> DownloadCategory.AUDIO
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst" -> DownloadCategory.COMPRESSED
            "apk", "apks", "xapk", "exe", "msi", "deb", "rpm", "dmg", "pkg", "iso" -> DownloadCategory.PROGRAMS
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "odt" -> DownloadCategory.DOCUMENTS
            "torrent" -> DownloadCategory.TORRENTS
            else -> DownloadCategory.OTHER
        }
    }

    /**
     * Publishes a finished HTTP file to the user's selected SAF tree. Partial data stays in
     * app-specific storage so segmented/resumable transfers can still use RandomAccessFile.
     */
    fun publishFile(source: File, filename: String, fallbackRoot: File): String {
        val category = categoryFor(filename)
        val uri = _treeUri.value?.let(Uri::parse)
        if (uri != null) {
            val root = DocumentFile.fromTreeUri(appContext, uri)
            if (root != null && root.canWrite()) {
                val folder = findOrCreateDirectory(root, category.folderName)
                val target = createUniqueFile(folder, filename)
                appContext.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output, 512 * 1024) }
                } ?: throw IOException("No se pudo escribir en la carpeta seleccionada")
                val size = source.length()
                if (size > 0L) source.delete()
                return target.uri.toString()
            }
        }

        val folder = File(fallbackRoot, category.folderName).apply { mkdirs() }
        val target = uniqueFile(folder, filename)
        if (source.absolutePath != target.absolutePath) {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
        }
        return target.absolutePath
    }

    fun publishTorrentDirectory(sourceDir: File, displayName: String, fallbackRoot: File): String {
        val uri = _treeUri.value?.let(Uri::parse)
        if (uri != null) {
            val root = DocumentFile.fromTreeUri(appContext, uri)
            if (root != null && root.canWrite()) {
                val torrentRoot = findOrCreateDirectory(root, DownloadCategory.TORRENTS.folderName)
                val destination = createUniqueDirectory(torrentRoot, safeName(displayName))
                copyDirectoryToTree(sourceDir, destination)
                sourceDir.deleteRecursively()
                return destination.uri.toString()
            }
        }

        val torrentRoot = File(fallbackRoot, DownloadCategory.TORRENTS.folderName).apply { mkdirs() }
        val target = uniqueDirectory(torrentRoot, safeName(displayName))
        if (sourceDir.absolutePath != target.absolutePath) {
            if (!sourceDir.renameTo(target)) {
                sourceDir.copyRecursively(target, overwrite = false)
                sourceDir.deleteRecursively()
            }
        }
        return target.absolutePath
    }

    fun openCompleted(context: Context, task: DownloadTask): Boolean {
        val uri = shareableUri(context, task) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(task.filename))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun shareCompleted(context: Context, task: DownloadTask): Boolean {
        val uri = shareableUri(context, task) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(task.filename)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Compartir ${task.filename}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /** Moves a completed HTTP file to a user-selected SAF directory. */
    fun moveCompletedFile(context: Context, task: DownloadTask, destinationTree: Uri): String {
        require(task.status == DownloadStatus.COMPLETED) { "La descarga aún no ha terminado" }
        require(task.kind == DownloadKind.HTTP) { "Mover carpetas torrent se añadirá en una fase posterior" }
        val sourcePath = task.outputPath ?: throw IOException("No se encontró el archivo terminado")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(destinationTree, flags) }

        val root = DocumentFile.fromTreeUri(context, destinationTree)
            ?: throw IOException("No se pudo abrir la carpeta elegida")
        if (!root.canWrite()) throw IOException("La carpeta elegida no permite escritura")
        val target = createUniqueFile(root, task.filename)

        openSourceInputStream(context, sourcePath).use { input ->
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                input.copyTo(output, 1024 * 1024)
            } ?: throw IOException("No se pudo crear el archivo de destino")
        }

        deleteSource(context, sourcePath)
        return target.uri.toString()
    }

    fun deleteCompleted(context: Context, task: DownloadTask): Boolean {
        val path = task.outputPath ?: return false
        return deleteSource(context, path)
    }

    private fun shareableUri(context: Context, task: DownloadTask): Uri? {
        val value = task.outputPath?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("content://", true) -> Uri.parse(value)
            value.startsWith("file://", true) -> {
                val file = File(Uri.parse(value).path ?: return null)
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            else -> {
                val file = File(value)
                if (!file.exists() || file.isDirectory) return null
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
        }
    }

    private fun openSourceInputStream(context: Context, value: String): java.io.InputStream {
        return if (value.startsWith("content://", true)) {
            context.contentResolver.openInputStream(Uri.parse(value))
                ?: throw IOException("No se pudo leer el archivo original")
        } else {
            val path = if (value.startsWith("file://", true)) Uri.parse(value).path else value
            FileInputStream(File(path ?: throw IOException("Ruta inválida")))
        }
    }

    private fun deleteSource(context: Context, value: String): Boolean = runCatching {
        if (value.startsWith("content://", true)) {
            DocumentFile.fromSingleUri(context, Uri.parse(value))?.delete() == true
        } else {
            val path = if (value.startsWith("file://", true)) Uri.parse(value).path else value
            File(path ?: return@runCatching false).deleteRecursively()
        }
    }.getOrDefault(false)

    private fun copyDirectoryToTree(source: File, destination: DocumentFile) {
        source.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val dir = destination.createDirectory(safeName(child.name))
                    ?: throw IOException("No se pudo crear ${child.name}")
                copyDirectoryToTree(child, dir)
            } else {
                val target = createUniqueFile(destination, child.name)
                appContext.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    FileInputStream(child).use { input -> input.copyTo(output, 512 * 1024) }
                } ?: throw IOException("No se pudo copiar ${child.name}")
            }
        }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IOException("No se pudo crear la carpeta $name")

    private fun createUniqueDirectory(parent: DocumentFile, name: String): DocumentFile {
        var candidate = name
        var index = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$name ($index)"
            index++
        }
        return parent.createDirectory(candidate)
            ?: throw IOException("No se pudo crear la carpeta de destino")
    }

    private fun createUniqueFile(parent: DocumentFile, filename: String): DocumentFile {
        val safe = safeName(filename)
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var candidate = safe
        var index = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$base ($index)$ext"
            index++
        }
        val mime = mimeType(candidate)
        return parent.createFile(mime, candidate)
            ?: throw IOException("No se pudo crear $candidate")
    }

    private fun mimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uniqueFile(dir: File, filename: String): File {
        val safe = safeName(filename)
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun uniqueDirectory(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$name ($index)")
            index++
        }
        return candidate
    }

    private fun safeName(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|]"""), "_")
        .trim()
        .take(180)
        .ifBlank { "Descarga" }
}
