package com.managerdownloader.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.SettingsRepository

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
    var progress by remember { mutableIntStateOf(0) }
    val mediaDetected = remember { mutableStateListOf<DetectedDownload>() }
    var showMediaDetected by remember { mutableStateOf(false) }

    fun rememberMedia(item: DetectedDownload) {
        if (mediaDetected.none { it.url == item.url }) {
            mediaDetected.add(item)
            while (mediaDetected.size > 30) mediaDetected.removeAt(0)
        }
    }

    LaunchedEffect(Unit) {
        ContentBlocker.refreshIfStale()
        installServiceWorkerBlocker()
    }

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
            label = { Text("Dirección o búsqueda") },
            trailingIcon = {
                Button(
                    onClick = {
                        val normalized = browserUrl(address)
                        address = normalized
                        webView?.loadUrl(normalized)
                    }
                ) { Text("Ir") }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                IconButton(enabled = canGoBack, onClick = { webView?.goBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                }
                IconButton(enabled = canGoForward, onClick = { webView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Adelante")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                }
            }

            Row {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = if (SettingsRepository.settings.value.adBlockEnabled) Success
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Spacer(Modifier.width(4.dp))
                if (mediaDetected.isNotEmpty()) {
                    TextButton(onClick = { showMediaDetected = true }) {
                        Text("Medios ${mediaDetected.size}")
                    }
                }
                TextButton(
                    onClick = {
                        val url = currentUrl
                        if (isDownloadableScheme(url)) {
                            detected = detectedItem(url, webView)
                        }
                    }
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("A cola")
                }
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString()
                            if (url != null && isDirectMediaUrl(url)) {
                                view?.post {
                                    rememberMedia(detectedItem(url, view))
                                }
                            }
                            return if (ContentBlocker.shouldBlock(url)) {
                                ContentBlocker.blockedResponse()
                            } else {
                                null
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.startsWith("magnet:", ignoreCase = true) ||
                                url.substringBefore('?').endsWith(".torrent", ignoreCase = true)
                            ) {
                                detected = detectedItem(url, view)
                                return true
                            }
                            return !url.startsWith("http://", true) && !url.startsWith("https://", true)
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        if (isDownloadableScheme(url)) {
                            val item = DetectedDownload(
                                url = url,
                                filename = if (url.startsWith("magnet:", true)) {
                                    magnetName(url)
                                } else {
                                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                                },
                                cookie = if (url.startsWith("http", true)) CookieManager.getInstance().getCookie(url) else null,
                                userAgent = userAgent
                            )
                            if (mimeType?.startsWith("video/") == true ||
                                mimeType?.startsWith("audio/") == true ||
                                mimeType?.startsWith("image/") == true ||
                                isDirectMediaUrl(url)
                            ) {
                                rememberMedia(item)
                            }
                            detected = item
                        }
                    }

                    loadUrl(currentUrl)
                    webView = this
                }
            },
            update = { webView = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    if (showMediaDetected) {
        AlertDialog(
            onDismissRequest = { showMediaDetected = false },
            title = { Text("Medios detectados") },
            text = {
                Column {
                    Text(
                        "Enlaces directos de video, audio e imagen vistos por el navegador. Contenido DRM o blob: no se intenta eludir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    mediaDetected.takeLast(8).reversed().forEach { item ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                detected = item
                                showMediaDetected = false
                            }
                        ) {
                            Text(item.filename, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMediaDetected = false }) { Text("Cerrar") }
            }
        )
    }

    detected?.let { item ->
        AlertDialog(
            onDismissRequest = { detected = null },
            title = {
                Text(if (item.url.startsWith("magnet:", true) || item.url.endsWith(".torrent", true)) {
                    "Torrent detectado"
                } else {
                    "Descarga detectada"
                })
            },
            text = {
                Column {
                    Text(item.filename, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAdd(item.url, item.filename, item.cookie, item.userAgent)
                        detected = null
                    }
                ) { Text("Añadir a la cola") }
            },
            dismissButton = {
                TextButton(onClick = { detected = null }) { Text("Cancelar") }
            }
        )
    }
}

private fun installServiceWorkerBlocker() {
    runCatching {
        ServiceWorkerController.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url?.toString()
                    return if (ContentBlocker.shouldBlock(url)) ContentBlocker.blockedResponse() else null
                }
            }
        )
    }
}

private fun detectedItem(url: String, webView: WebView?): DetectedDownload {
    val name = if (url.startsWith("magnet:", true)) {
        magnetName(url)
    } else {
        URLUtil.guessFileName(url, null, null)
    }
    return DetectedDownload(
        url = url,
        filename = name,
        cookie = if (url.startsWith("http", true)) CookieManager.getInstance().getCookie(url) else null,
        userAgent = webView?.settings?.userAgentString
    )
}

private fun magnetName(url: String): String = runCatching {
    Uri.parse(url).getQueryParameter("dn")
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "Magnet torrent"

private fun isDownloadableScheme(url: String): Boolean =
    url.startsWith("http://", true) ||
        url.startsWith("https://", true) ||
        url.startsWith("magnet:", true)

private fun isDirectMediaUrl(url: String): Boolean {
    val clean = url.substringBefore('#').substringBefore('?').lowercase()
    return DIRECT_MEDIA_EXTENSIONS.any { clean.endsWith(it) }
}

private val DIRECT_MEDIA_EXTENSIONS = setOf(
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp",
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus",
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"
)

private fun browserUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    if (trimmed.startsWith("magnet:", true)) return trimmed

    return if (trimmed.contains(".") && !trimmed.contains(" ")) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
