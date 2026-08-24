package com.managerdownloader.app.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.download.DownloadService

@Composable
fun ManagerDownloaderApp() {
    ManagerTheme {
        var tab by remember { mutableIntStateOf(0) }
        val context = LocalContext.current

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
                    }
                )

                1 -> BrowserScreen(
                    contentPadding = padding,
                    onAdd = { url, name, cookie, userAgent ->
                        enqueue(context, url, name, cookie, userAgent)
                    }
                )

                else -> SettingsScreen(
                    contentPadding = padding,
                    onTransferSettingsChanged = { DownloadService.process(context) }
                )
            }
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
