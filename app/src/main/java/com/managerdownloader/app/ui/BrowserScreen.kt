package com.managerdownloader.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private data class DetectedDownload(
    val url: String,
    val filename: String,
    val cookie: String?,
    val userAgent: String?
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    contentPadding: PaddingValues,
    onAdd: (String, String?, String?, String?) -> Unit
) {
    val context = LocalContext.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<DetectedDownload?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding()
            )
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            label = { Text("Dirección") },
            trailingIcon = {
                Button(
                    onClick = {
                        val normalized = browserUrl(address)
                        address = normalized
                        webView?.loadUrl(normalized)
                    }
                ) {
                    Text("Ir")
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                IconButton(
                    enabled = canGoBack,
                    onClick = { webView?.goBack() }
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                }
                IconButton(
                    enabled = canGoForward,
                    onClick = { webView?.goForward() }
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Adelante")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                }
            }

            TextButton(
                onClick = {
                    val url = currentUrl
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        detected = DetectedDownload(
                            url = url,
                            filename = URLUtil.guessFileName(url, null, null),
                            cookie = CookieManager.getInstance().getCookie(url),
                            userAgent = webView?.settings?.userAgentString
                        )
                    }
                }
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Enviar URL a cola")
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            detected = DetectedDownload(
                                url = url,
                                filename = URLUtil.guessFileName(
                                    url,
                                    contentDisposition,
                                    mimeType
                                ),
                                cookie = CookieManager.getInstance().getCookie(url),
                                userAgent = userAgent
                            )
                        }
                    }

                    loadUrl(currentUrl)
                    webView = this
                }
            },
            update = {
                webView = it
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    detected?.let { item ->
        AlertDialog(
            onDismissRequest = { detected = null },
            title = { Text("Enlace descargable detectado") },
            text = {
                Column {
                    Text(item.filename, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAdd(
                            item.url,
                            item.filename,
                            item.cookie,
                            item.userAgent
                        )
                        detected = null
                    }
                ) {
                    Text("Añadir a la cola")
                }
            },
            dismissButton = {
                TextButton(onClick = { detected = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun browserUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        return trimmed
    }

    return if (trimmed.contains(".") && !trimmed.contains(" ")) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=" +
            java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
