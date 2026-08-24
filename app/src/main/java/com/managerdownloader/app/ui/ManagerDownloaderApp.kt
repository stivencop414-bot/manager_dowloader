package com.managerdownloader.app.ui

import android.content.Context
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.StorageRepository
import com.managerdownloader.app.download.DownloadService

@Composable
fun ManagerDownloaderApp(
    onSelectDownloadFolder: () -> Unit,
    onOpenTorrentFile: () -> Unit
) {
    ManagerTheme {
        var tab by remember { mutableIntStateOf(0) }
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
            when (tab) {
                0 -> DownloadsScreen(
                    contentPadding = padding,
                    onAdd = { url, name, cookie, userAgent ->
                        enqueue(context, url, name, cookie, userAgent)
                    },
                    onOpenTorrentFile = onOpenTorrentFile
                )

                1 -> BrowserScreen(
                    contentPadding = padding,
                    onAdd = { url, name, cookie, userAgent ->
                        enqueue(context, url, name, cookie, userAgent)
                    }
                )

                else -> SettingsScreen(
                    contentPadding = padding,
                    onTransferSettingsChanged = { DownloadService.process(context) },
                    onSelectDownloadFolder = onSelectDownloadFolder
                )
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
    userAgent: String? = null
) {
    DownloadRepository.add(
        url = url,
        suggestedFilename = filename,
        cookie = cookie,
        userAgent = userAgent
    )
    DownloadService.process(context)
}
