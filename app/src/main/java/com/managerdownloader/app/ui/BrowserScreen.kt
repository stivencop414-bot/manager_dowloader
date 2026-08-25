package com.managerdownloader.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.SearchEngine
import com.managerdownloader.app.data.SettingsRepository

private data class DetectedDownload(
    val url: String,
    val filename: String,
    val cookie: String?,
    val userAgent: String?
)

private class MediaBridge(
    private val onMedia: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMedia(url: String?) {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        main.post { onMedia(clean) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    contentPadding: PaddingValues,
    onAdd: (String, String?, String?, String?) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by SettingsRepository.settings.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(homeUrl(settings.searchEngine)) }
    var currentUrl by remember { mutableStateOf(homeUrl(settings.searchEngine)) }
    var pageTitle by remember { mutableStateOf("Navegador") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<DetectedDownload?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var blockerRevision by remember { mutableIntStateOf(0) }
    var lastSearchInput by remember { mutableStateOf<String?>(null) }
    val mediaDetected = remember { mutableStateListOf<DetectedDownload>() }
    var showMediaDetected by remember { mutableStateOf(false) }

    fun rememberMedia(item: DetectedDownload) {
        if (!SettingsRepository.settings.value.mediaSnifferEnabled) return
        if (mediaDetected.none { it.url == item.url }) {
            mediaDetected.add(item)
            while (mediaDetected.size > 40) mediaDetected.removeAt(0)
        }
    }

    fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isBlank()) return
        focusManager.clearFocus()

        if (input.startsWith("magnet:", true)) {
            detected = detectedItem(input, webView)
            return
        }

        val normalized = browserUrl(input, SettingsRepository.settings.value.searchEngine)
        lastSearchInput = if (looksLikeAddress(input)) null else input
        if (normalized.substringBefore('?').endsWith(".torrent", true)) {
            detected = detectedItem(normalized, webView)
            return
        }
        address = normalized
        pageError = null
        webView?.loadUrl(normalized)
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
            placeholder = { Text("Buscar o escribir URL") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { navigate(address) }),
            trailingIcon = {
                IconButton(onClick = { navigate(address) }) {
                    Icon(Icons.Default.Search, contentDescription = "Buscar")
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
                IconButton(enabled = canGoBack, onClick = { webView?.goBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                }
                IconButton(enabled = canGoForward, onClick = { webView?.goForward() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Adelante")
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Recargar")
                }
                IconButton(onClick = {
                    val home = homeUrl(SettingsRepository.settings.value.searchEngine)
                    address = home
                    webView?.loadUrl(home)
                }) {
                    Icon(Icons.Default.Home, contentDescription = "Inicio")
                }
            }

            Row {
                TextButton(
                    onClick = {
                        SettingsRepository.setAdBlockEnabled(!settings.adBlockEnabled)
                        blockerRevision++
                        webView?.reload()
                    }
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = if (settings.adBlockEnabled) Success else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (settings.adBlockEnabled) "AdBlock ON" else "AdBlock OFF")
                }

                if (mediaDetected.isNotEmpty()) {
                    TextButton(onClick = { showMediaDetected = true }) {
                        Text("Medios ${mediaDetected.size}")
                    }
                }

                TextButton(
                    onClick = {
                        val url = currentUrl
                        if (isDownloadableScheme(url)) detected = detectedItem(url, webView)
                    }
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("A cola")
                }
            }
        }

        val siteAllowed = remember(currentUrl, blockerRevision) {
            ContentBlocker.isCurrentSiteAllowed(currentUrl)
        }
        if (settings.adBlockEnabled && currentUrl.startsWith("http", true)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    pageTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    ContentBlocker.setCurrentSiteAllowed(currentUrl, !siteAllowed)
                    blockerRevision++
                    webView?.reload()
                }) {
                    Text(if (siteAllowed) "Activar bloqueo aquí" else "Permitir este sitio")
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

        pageError?.let { error ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { webView?.reload() }) { Text("Reintentar") }
                    if (settings.adBlockEnabled) {
                        OutlinedButton(onClick = {
                            ContentBlocker.setCurrentSiteAllowed(currentUrl, true)
                            blockerRevision++
                            pageError = null
                            webView?.reload()
                        }) { Text("Cargar sin AdBlock") }
                    }
                    if (settings.searchEngine == SearchEngine.GOOGLE && lastSearchInput != null) {
                        OutlinedButton(onClick = {
                            val fallback = searchUrl(lastSearchInput.orEmpty(), SearchEngine.DUCKDUCKGO)
                            pageError = null
                            address = fallback
                            webView?.loadUrl(fallback)
                        }) { Text("Buscar alternativo") }
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.databaseEnabled = true
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = true
                    this.settings.setSupportMultipleWindows(true)
                    this.settings.javaScriptCanOpenWindowsAutomatically = true
                    this.settings.mediaPlaybackRequiresUserGesture = true
                    this.settings.builtInZoomControls = true
                    this.settings.displayZoomControls = false
                    this.settings.loadWithOverviewMode = true
                    this.settings.useWideViewPort = true
                    this.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    this.settings.safeBrowsingEnabled = true

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(
                        this,
                        SettingsRepository.settings.value.thirdPartyCookies
                    )
                    val defaultUa = WebSettings.getDefaultUserAgent(context)
                    this.settings.userAgentString = if (SettingsRepository.settings.value.chromeCompatUserAgent) {
                        chromeCompatibleUserAgent(defaultUa)
                    } else {
                        defaultUa
                    }
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)

                    val owner = this
                    addJavascriptInterface(
                        MediaBridge { url -> rememberMedia(detectedItem(url, owner)) },
                        "ManagerMediaBridge"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title?.takeIf { it.isNotBlank() } ?: "Navegador"
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message
                        ): Boolean {
                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                            val child = WebView(context)
                            child.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                                    owner.loadUrl(url)
                                    v.stopLoading()
                                    v.destroy()
                                }
                            }
                            transport.webView = child
                            resultMsg.sendToTarget()
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString()
                            if (
                                SettingsRepository.settings.value.mediaSnifferEnabled &&
                                url != null &&
                                isDirectMediaUrl(url)
                            ) {
                                view?.post { rememberMedia(detectedItem(url, view)) }
                            }
                            return if (ContentBlocker.shouldBlock(url, request?.isForMainFrame == true, request?.method)) ContentBlocker.blockedResponse() else null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString().orEmpty()
                            if (url.isBlank()) return false
                            if (
                                url.startsWith("magnet:", ignoreCase = true) ||
                                url.substringBefore('?').endsWith(".torrent", ignoreCase = true)
                            ) {
                                detected = detectedItem(url, view)
                                return true
                            }
                            if (url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("about:", true)) {
                                return false
                            }
                            openExternal(context, url)
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            pageError = null
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            ContentBlocker.setActivePage(url)
                            mediaDetected.clear()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            ContentBlocker.setActivePage(url)
                            if (SettingsRepository.settings.value.mediaSnifferEnabled) {
                                view.evaluateJavascript(MEDIA_SNIFFER_SCRIPT, null)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                pageError = "No se pudo cargar la página: ${error?.description ?: "error de red"}"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                                pageError = "La página respondió HTTP ${errorResponse?.statusCode}. Puedes reintentar o cargarla sin AdBlock."
                            }
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
                            if (
                                SettingsRepository.settings.value.mediaSnifferEnabled &&
                                (mimeType?.startsWith("video/") == true ||
                                    mimeType?.startsWith("audio/") == true ||
                                    mimeType?.startsWith("image/") == true ||
                                    isDirectMediaUrl(url))
                            ) {
                                rememberMedia(item)
                            }
                            detected = item
                        }
                    }

                    val initial = homeUrl(SettingsRepository.settings.value.searchEngine)
                    currentUrl = initial
                    address = initial
                    loadUrl(initial)
                    webView = this
                }
            },
            update = { view ->
                webView = view
                val defaultUa = WebSettings.getDefaultUserAgent(context)
                val desiredUa = if (settings.chromeCompatUserAgent) chromeCompatibleUserAgent(defaultUa) else defaultUa
                if (view.settings.userAgentString != desiredUa) {
                    view.settings.userAgentString = desiredUa
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.thirdPartyCookies)
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            ContentBlocker.setActivePage(null)
            webView?.stopLoading()
            webView?.removeJavascriptInterface("ManagerMediaBridge")
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
                        "Enlaces HTTP/HTTPS de video, audio e imagen observados por el navegador y el DOM. No intenta romper DRM ni descargar blob: internos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    mediaDetected.takeLast(12).reversed().forEach { item ->
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
                Text(
                    if (item.url.startsWith("magnet:", true) || item.url.substringBefore('?').endsWith(".torrent", true)) {
                        "Torrent detectado"
                    } else {
                        "Descarga detectada"
                    }
                )
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
                    return if (ContentBlocker.shouldBlock(url, request.isForMainFrame, request.method)) ContentBlocker.blockedResponse() else null
                }
            }
        )
    }
}

private fun detectedItem(url: String, webView: WebView?): DetectedDownload {
    val name = if (url.startsWith("magnet:", true)) magnetName(url) else URLUtil.guessFileName(url, null, null)
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
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".ts",
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus",
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"
)

private fun browserUrl(value: String, engine: SearchEngine): String {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    if (trimmed.startsWith("magnet:", true)) return trimmed
    return if (looksLikeAddress(trimmed)) "https://$trimmed" else searchUrl(trimmed, engine)
}

private fun looksLikeAddress(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("magnet:", true)) return true
    val noSpaces = !trimmed.contains(Regex("\\s"))
    return noSpaces && (
        trimmed.contains('.') ||
            trimmed.equals("localhost", true) ||
            IPV4_REGEX.matches(trimmed.substringBefore('/'))
        )
}

private fun homeUrl(engine: SearchEngine): String = when (engine) {
    SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/"
    SearchEngine.GOOGLE -> "https://www.google.com/"
    SearchEngine.BING -> "https://www.bing.com/"
    SearchEngine.BRAVE -> "https://search.brave.com/"
}

private fun searchUrl(query: String, engine: SearchEngine): String {
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    return when (engine) {
        SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
        SearchEngine.GOOGLE -> "https://www.google.com/search?hl=es&q=$encoded"
        SearchEngine.BING -> "https://www.bing.com/search?q=$encoded"
        SearchEngine.BRAVE -> "https://search.brave.com/search?q=$encoded"
    }
}

private fun chromeCompatibleUserAgent(defaultUa: String): String = defaultUa
    .replace("; wv", "")
    .replace("Version/4.0 ", "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun openExternal(context: android.content.Context, url: String) {
    runCatching {
        val intent = if (url.startsWith("intent:", true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private val IPV4_REGEX = Regex("""^(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?$""")

private const val MEDIA_SNIFFER_SCRIPT = """
(function() {
  try {
    if (window.__managerDownloaderSnifferInstalled) {
      if (window.__managerDownloaderScan) window.__managerDownloaderScan();
      return;
    }
    window.__managerDownloaderSnifferInstalled = true;
    const mediaRx = /\\.(mp4|mkv|webm|avi|mov|m4v|3gp|ts|mp3|m4a|aac|flac|wav|ogg|opus|jpg|jpeg|png|webp|gif|avif)(?:$|[?#])/i;
    const send = function(raw) {
      try {
        if (!raw || raw.indexOf('blob:') === 0 || raw.indexOf('data:') === 0) return;
        const absolute = new URL(raw, document.baseURI).href;
        if (/^https?:/i.test(absolute)) ManagerMediaBridge.onMedia(absolute);
      } catch (_) {}
    };
    const scan = function() {
      document.querySelectorAll('video,audio,source').forEach(function(el) {
        send(el.currentSrc || el.src || el.getAttribute('src'));
      });
      document.querySelectorAll('a[href]').forEach(function(el) {
        const href = el.href || el.getAttribute('href');
        if (el.hasAttribute('download') || mediaRx.test(href || '')) send(href);
      });
    };
    window.__managerDownloaderScan = scan;
    scan();
    const observer = new MutationObserver(function() { scan(); });
    observer.observe(document.documentElement || document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src','href']});
    setTimeout(scan, 900);
    setTimeout(scan, 2500);
  } catch (_) {}
})();
"""
