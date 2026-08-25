package com.managerdownloader.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.managerdownloader.app.data.DownloadKind
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.download.DownloadService
import com.managerdownloader.app.ui.ManagerDownloaderApp
import com.managerdownloader.app.youtube.YouTubeUrlParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var incomingBrowserUrl by mutableStateOf<String?>(null)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val folderPickerLauncher =
        registerForActivityResult(OpenDocumentTree()) { uri ->
            if (uri != null) StorageRepository.setTreeUri(this, uri)
            StorageRepository.markPrompted()
        }

    private var pendingMoveTaskId: String? = null

    private val moveFileLauncher =
        registerForActivityResult(OpenDocumentTree()) { uri ->
            val taskId = pendingMoveTaskId
            pendingMoveTaskId = null
            if (uri == null || taskId == null) return@registerForActivityResult
            val task = DownloadRepository.find(taskId) ?: return@registerForActivityResult
            val appContext = applicationContext
            fileOpsScope.launch {
                val result = runCatching { StorageRepository.moveCompletedFile(appContext, task, uri) }
                result.onSuccess { newPath ->
                    DownloadRepository.updateOutputPath(taskId, newPath)
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        Toast.makeText(appContext, "Archivo movido", Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        Toast.makeText(
                            appContext,
                            error.message ?: "No se pudo mover el archivo",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    private val torrentFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                enqueueTorrent(uri.toString(), queryDisplayName(uri))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIncomingIntent(intent)

        setContent {
            ManagerDownloaderApp(
                onSelectDownloadFolder = { folderPickerLauncher.launch(null) },
                onOpenTorrentFile = {
                    torrentFileLauncher.launch(arrayOf(
                        "application/x-bittorrent",
                        "application/octet-stream"
                    ))
                },
                onMoveCompleted = { taskId ->
                    pendingMoveTaskId = taskId
                    moveFileLauncher.launch(null)
                },
                incomingBrowserUrl = incomingBrowserUrl,
                onIncomingBrowserUrlConsumed = { incomingBrowserUrl = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val youtube = YouTubeUrlParser.findInText(text)
                    if (youtube != null) {
                        incomingBrowserUrl = youtube.canonicalUrl ?: youtube.rawUrl
                    } else {
                        val sharedUrl = text?.trim()?.takeIf {
                            it.startsWith("http://", true) || it.startsWith("https://", true)
                        }
                        if (sharedUrl != null) incomingBrowserUrl = sharedUrl
                    }
                }
            }

            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                when {
                    uri.scheme.equals("magnet", true) -> enqueueTorrent(uri.toString(), magnetName(uri))
                    intent.type.equals("application/x-bittorrent", true) ||
                        uri.toString().substringBefore('?').endsWith(".torrent", true) -> {
                        importTorrentUri(uri)?.let { (localUri, name) ->
                            enqueueTorrent(localUri, name)
                        }
                    }
                    YouTubeUrlParser.parse(uri.toString()) != null -> incomingBrowserUrl = uri.toString()
                }
            }
        }
    }

    private fun enqueueTorrent(url: String, name: String?) {
        DownloadRepository.add(
            url = url,
            suggestedFilename = name,
            kind = DownloadKind.TORRENT
        )
        DownloadService.process(this)
    }

    private fun importTorrentUri(uri: Uri): Pair<String, String?>? = runCatching {
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            return@runCatching uri.toString() to uri.lastPathSegment
        }

        val name = queryDisplayName(uri) ?: "imported-${UUID.randomUUID()}.torrent"
        val dir = File(filesDir, "imported-torrents").apply { mkdirs() }
        val destination = File(dir, "${UUID.randomUUID()}.torrent")
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching null
        Uri.fromFile(destination).toString() to name
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun magnetName(uri: Uri): String? = uri.getQueryParameter("dn")

    companion object {
        // File moves must outlive Activity recreation; SAF grants and repository state are
        // application-scoped, so this scope is intentionally not tied to lifecycleScope.
        private val fileOpsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

}
