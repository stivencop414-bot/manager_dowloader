package com.managerdownloader.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.download.DownloadService

@Composable
fun ManagerDownloaderApp(
    onSelectDownloadFolder: () -> Unit,
    onOpenTorrentFile: () -> Unit,
    onMoveCompleted: (String) -> Unit,
    incomingBrowserUrl: String? = null,
    onIncomingBrowserUrlConsumed: () -> Unit = {}
) {
    val settings by SettingsRepository.settings.collectAsState()
    ManagerTheme(settings.themeMode) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var browserLastUrl by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(incomingBrowserUrl) {
            if (incomingBrowserUrl != null) tab = 1
        }
        val context = LocalContext.current
        val selectedTree by StorageRepository.treeUri.collectAsState()
        var showStoragePrompt by remember { mutableStateOf(selectedTree == null && !StorageRepository.wasPrompted()) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        label = { Text("Descargas") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Language, contentDescription = null) },
                        label = { Text("Navegador") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        label = { Text("Ajustes") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Keep the browser composed across tab changes. This preserves WebView history,
                // session state and renderer resources instead of destroying/recreating Chromium.
                BrowserScreen(
                    contentPadding = padding,
                    onAdd = { url, name, cookie, userAgent, referer, originalSourceUrl, sourceFormatId ->
                        enqueue(
                            context = context,
                            url = url,
                            filename = name,
                            cookie = cookie,
                            userAgent = userAgent,
                            referer = referer,
                            originalSourceUrl = originalSourceUrl,
                            sourceFormatId = sourceFormatId
                        )
                    },
                    onAddBatch = { requests -> enqueueBatch(context, requests) },
                    initialUrl = browserLastUrl,
                    incomingUrl = incomingBrowserUrl,
                    isVisible = tab == 1,
                    onCurrentUrlChanged = { browserLastUrl = it },
                    onIncomingUrlConsumed = onIncomingBrowserUrlConsumed
                )

                when (tab) {
                    0 -> DownloadsScreen(
                        contentPadding = padding,
                        onAdd = { url, name, cookie, userAgent, expectedSha256 ->
                            enqueue(context, url, name, cookie, userAgent, expectedSha256)
                        },
                        onOpenTorrentFile = onOpenTorrentFile,
                        onMoveCompleted = onMoveCompleted
                    )
                    2 -> SettingsScreen(
                        contentPadding = padding,
                        onTransferSettingsChanged = { DownloadService.refreshSettings(context) },
                        onSelectDownloadFolder = onSelectDownloadFolder
                    )
                }
            }
        }

        if (showStoragePrompt && selectedTree == null) {
            AlertDialog(
                onDismissRequest = {
                    StorageRepository.markPrompted()
                    showStoragePrompt = false
                },
                title = { Text("¿Dónde quieres guardar tus descargas?") },
                text = {
                    Text("Elige una carpeta. Android otorgará acceso solo a esa ubicación y Manager Downloader creará subcarpetas para Videos, Imágenes, Audio, Comprimidos, Programas, Documentos, Torrents y Otros.")
                },
                confirmButton = {
                    Button(onClick = {
                        StorageRepository.markPrompted()
                        showStoragePrompt = false
                        onSelectDownloadFolder()
                    }) { Text("Elegir carpeta") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        StorageRepository.markPrompted()
                        showStoragePrompt = false
                    }) { Text("Después") }
                }
            )
        }

    }
}

fun enqueue(
    context: Context,
    url: String,
    filename: String? = null,
    cookie: String? = null,
    userAgent: String? = null,
    expectedSha256: String? = null,
    referer: String? = null,
    originalSourceUrl: String? = null,
    sourceFormatId: String? = null
) {
    DownloadRepository.add(
        url = url,
        suggestedFilename = filename,
        cookie = cookie,
        userAgent = userAgent,
        referer = referer,
        expectedSha256 = expectedSha256,
        originalSourceUrl = originalSourceUrl,
        sourceFormatId = sourceFormatId
    )
    DownloadService.process(context)
}
fun enqueueBatch(context: Context, requests: List<BrowserDownloadRequest>) {
    if (requests.isEmpty()) return
    requests.distinctBy { it.url }.forEach { request ->
        DownloadRepository.add(
            url = request.url,
            suggestedFilename = request.filename,
            cookie = request.cookie,
            userAgent = request.userAgent,
            referer = request.referer,
            originalSourceUrl = request.originalSourceUrl,
            sourceFormatId = request.sourceFormatId
        )
    }
    DownloadService.process(context)
}

