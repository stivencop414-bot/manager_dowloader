package com.managerdownloader.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
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
import com.managerdownloader.app.youtube.YouTubeExtractorClient
import com.managerdownloader.app.youtube.YouTubeFormatKind
import com.managerdownloader.app.youtube.YouTubeLink
import com.managerdownloader.app.youtube.YouTubeUrlParser
import com.managerdownloader.app.youtube.YouTubeVideoDetails
import kotlinx.coroutines.launch
import org.json.JSONArray

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
    onAdd: (String, String?, String?, String?) -> Unit,
    incomingUrl: String? = null,
    onIncomingUrlConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by SettingsRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

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
    var youtubeLink by remember { mutableStateOf<YouTubeLink?>(null) }
    var youtubeDetails by remember { mutableStateOf<YouTubeVideoDetails?>(null) }
    var youtubeError by remember { mutableStateOf<String?>(null) }
    var youtubeLoading by remember { mutableStateOf(false) }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var webViewGeneration by remember { mutableIntStateOf(0) }

    fun rememberMedia(item: DetectedDownload) {
        if (!SettingsRepository.settings.value.mediaSnifferEnabled) return
        if (mediaDetected.none { it.url == item.url }) {
            mediaDetected.add(item)
            while (mediaDetected.size > 16) mediaDetected.removeAt(0)
        }
    }

    fun analyzeYouTube(link: YouTubeLink) {
        if (!link.isVideo) {
            youtubeError = "Se detectó una playlist, pero esta versión analiza videos individuales."
            showYouTubeDialog = true
            return
        }
        youtubeLoading = true
        youtubeError = null
        youtubeDetails = null
        showYouTubeDialog = true
        scope.launch {
            val result = YouTubeExtractorClient.analyze(link.canonicalUrl ?: link.rawUrl)
            youtubeLoading = false
            result.onSuccess { youtubeDetails = it }
                .onFailure { error ->
                    youtubeError = error.message ?: error.javaClass.simpleName
                }
        }
    }

    fun updateYouTubeLink(url: String?) {
        youtubeLink = YouTubeUrlParser.parse(url)
        if (youtubeLink == null) {
            youtubeDetails = null
            youtubeError = null
            showYouTubeDialog = false
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
        updateYouTubeLink(normalized)
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

    LaunchedEffect(incomingUrl) {
        val url = incomingUrl?.trim().orEmpty()
        if (url.isNotBlank()) {
            navigate(url)
            YouTubeUrlParser.parse(url)?.let { analyzeYouTube(it) }
            onIncomingUrlConsumed()
        }
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

                val activeYouTube = youtubeLink
                if (activeYouTube != null) {
                    TextButton(onClick = {
                        if (youtubeDetails == null && !youtubeLoading) analyzeYouTube(activeYouTube)
                        else showYouTubeDialog = true
                    }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("YouTube")
                    }
                } else if (isDirectDownloadCandidate(currentUrl)) {
                    TextButton(onClick = { detected = detectedItem(currentUrl, webView) }) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("A cola")
                    }
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
                    OutlinedButton(onClick = { if (webView != null) webView?.reload() else webViewGeneration++ }) { Text("Reintentar") }
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

        key(webViewGeneration) {
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
                    this.settings.setSupportMultipleWindows(false)
                    this.settings.javaScriptCanOpenWindowsAutomatically = false
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

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            pageTitle = title?.takeIf { it.isNotBlank() } ?: "Navegador"
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
                                YouTubeUrlParser.parse(view?.url) == null &&
                                request?.isForMainFrame != true &&
                                url != null &&
                                isLikelyMediaUrl(url)
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
                            updateYouTubeLink(url)
                            mediaDetected.clear()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            address = url
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            ContentBlocker.setActivePage(url)
                            if (SettingsRepository.settings.value.mediaSnifferEnabled && YouTubeUrlParser.parse(url) == null) {
                                scanMediaFromDom(view, url) { mediaUrl ->
                                    rememberMedia(detectedItem(mediaUrl, view))
                                }
                                view.postDelayed({
                                    if (view.isAttachedToWindow && view.url == url && SettingsRepository.settings.value.mediaSnifferEnabled) {
                                        scanMediaFromDom(view, url) { mediaUrl ->
                                            rememberMedia(detectedItem(mediaUrl, view))
                                        }
                                    }
                                }, 1500L)
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

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            runCatching { view?.destroy() }
                            webView = null
                            pageError = "El proceso del navegador se reinició para evitar que se cierre toda la app. Pulsa Reintentar."
                            webViewGeneration++
                            return true
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        if (!isDownloadableScheme(url)) return@setDownloadListener
                        if (!isRealFileDownload(url, mimeType, contentDisposition)) return@setDownloadListener

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
                            YouTubeUrlParser.parse(currentUrl) == null &&
                            (mimeType?.startsWith("video/") == true ||
                                mimeType?.startsWith("audio/") == true ||
                                isLikelyMediaUrl(url))
                        ) {
                            rememberMedia(item)
                        }
                        detected = item
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
                runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.thirdPartyCookies) }
            }
        )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ContentBlocker.setActivePage(null)
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    if (showYouTubeDialog && youtubeLink != null) {
        val link = youtubeLink!!
        AlertDialog(
            onDismissRequest = { if (!youtubeLoading) showYouTubeDialog = false },
            title = { Text("YouTube detectado") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Analiza el video con un extractor específico; no se añade la página HTML como .txt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (link.playlistId != null && link.videoId == null) {
                        Text("Playlist detectada: ${link.playlistId}. El análisis por playlist se añadirá después.")
                    }
                    if (youtubeLoading) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("Analizando formatos disponibles…")
                        }
                    }
                    youtubeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    youtubeDetails?.let { details ->
                        Text(details.title, style = MaterialTheme.typography.titleMedium)
                        if (details.uploader.isNotBlank()) Text(details.uploader, style = MaterialTheme.typography.bodySmall)
                        if (details.durationSeconds > 0) Text("Duración: ${formatDuration(details.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                        details.warnings.forEach { warning ->
                            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        HorizontalDivider()
                        Text("Video con audio", style = MaterialTheme.typography.titleSmall)
                        if (details.progressiveVideo.isEmpty()) {
                            Text("No hay una pista combinada descargable disponible.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            details.progressiveVideo.take(6).forEach { option ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        val cookie = CookieManager.getInstance().getCookie(details.sourceUrl)
                                        onAdd(option.url, option.filename, cookie, webView?.settings?.userAgentString)
                                        showYouTubeDialog = false
                                    }
                                ) { Text("Descargar ${option.label}") }
                            }
                        }

                        if (details.audioOnly.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Solo audio", style = MaterialTheme.typography.titleSmall)
                            details.audioOnly.take(4).forEach { option ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        val cookie = CookieManager.getInstance().getCookie(details.sourceUrl)
                                        onAdd(option.url, option.filename, cookie, webView?.settings?.userAgentString)
                                        showYouTubeDialog = false
                                    }
                                ) { Text("Descargar ${option.label}") }
                            }
                        }

                        if (details.videoOnly.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Calidad alta separada", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Se detectaron ${details.videoOnly.size} pistas de video sin audio. No se descargan como video final todavía porque requieren fusionar una pista de audio compatible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            details.videoOnly.take(4).forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodySmall) }
                        }

                        Text(
                            "Úsalo únicamente con contenido que tengas permiso para descargar. No se intenta eludir DRM, contenido privado ni controles de acceso.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (youtubeDetails == null && !youtubeLoading && link.isVideo) {
                    Button(onClick = { analyzeYouTube(link) }) { Text("Analizar") }
                } else {
                    TextButton(enabled = !youtubeLoading, onClick = { showYouTubeDialog = false }) { Text("Cerrar") }
                }
            },
            dismissButton = {
                if (youtubeDetails == null && !youtubeLoading) {
                    TextButton(onClick = { showYouTubeDialog = false }) { Text("Cancelar") }
                }
            }
        )
    }

    if (showMediaDetected) {
        AlertDialog(
            onDismissRequest = { showMediaDetected = false },
            title = { Text("Medios detectados") },
            text = {
                Column {
                    Text(
                        "Enlaces HTTP/HTTPS de video y audio detectados por el reproductor o por URLs multimedia. Se omiten imágenes, iconos y recursos decorativos. No intenta romper DRM ni descargar blob: internos.",
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

private fun isLikelyMediaUrl(url: String): Boolean {
    val clean = url.substringBefore('#').substringBefore('?').lowercase()
    return MEDIA_EXTENSIONS.any { clean.endsWith(it) }
}

private val MEDIA_EXTENSIONS = setOf(
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp",
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"
)

private fun isRealFileDownload(url: String, mimeType: String?, contentDisposition: String?): Boolean {
    if (url.startsWith("magnet:", true)) return true
    if (YouTubeUrlParser.parse(url) != null) return false
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (mime in IGNORED_WEB_MIME_TYPES || mime.startsWith("text/")) return false
    if (contentDisposition?.contains("attachment", ignoreCase = true) == true) return true
    if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
    if (mime in DOWNLOADABLE_APPLICATION_MIME_TYPES) return true
    return isDirectDownloadCandidate(url)
}

private fun isDirectDownloadCandidate(url: String): Boolean {
    if (url.startsWith("magnet:", true)) return true
    val clean = url.substringBefore('#').substringBefore('?').lowercase()
    if (clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".txt")) return false
    return DIRECT_FILE_EXTENSIONS.any { clean.endsWith(it) }
}

private val IGNORED_WEB_MIME_TYPES = setOf(
    "text/plain", "text/html", "text/css", "text/javascript",
    "application/javascript", "application/json", "application/xml",
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml"
)

private val DOWNLOADABLE_APPLICATION_MIME_TYPES = setOf(
    "application/octet-stream", "application/pdf", "application/zip", "application/x-zip-compressed",
    "application/x-rar-compressed", "application/vnd.rar", "application/x-7z-compressed",
    "application/vnd.android.package-archive", "application/x-bittorrent"
)

private val DIRECT_FILE_EXTENSIONS = setOf(
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp",
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus",
    ".zip", ".rar", ".7z", ".pdf", ".apk", ".xapk", ".iso", ".torrent"
)

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

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

private fun scanMediaFromDom(
    view: WebView,
    expectedPageUrl: String,
    onMedia: (String) -> Unit
) {
    if (!view.isAttachedToWindow) return
    runCatching {
        view.evaluateJavascript(MEDIA_SNIFFER_SCRIPT) { raw ->
            if (view.url != expectedPageUrl || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@evaluateJavascript
            val count = minOf(array.length(), 12)
            for (index in 0 until count) {
                val url = array.optString(index).trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    onMedia(url)
                }
            }
        }
    }
}

private const val MEDIA_SNIFFER_SCRIPT = """
(function() {
  try {
    const found = [];
    const seen = new Set();
    const mediaRx = /\.(mp4|mkv|webm|avi|mov|m4v|3gp|mp3|m4a|aac|flac|wav|ogg|opus)(?:$|[?#])/i;
    const add = function(raw) {
      try {
        if (!raw || raw.indexOf('blob:') === 0 || raw.indexOf('data:') === 0) return;
        const absolute = new URL(raw, document.baseURI).href;
        if (!/^https?:/i.test(absolute) || seen.has(absolute)) return;
        seen.add(absolute);
        found.push(absolute);
      } catch (_) {}
    };
    document.querySelectorAll('video,audio,source').forEach(function(el) {
      add(el.currentSrc || el.src || el.getAttribute('src'));
    });
    document.querySelectorAll('a[href]').forEach(function(el) {
      const href = el.href || el.getAttribute('href');
      if (mediaRx.test(href || '')) add(href);
    });
    return found.slice(0, 12);
  } catch (_) {
    return [];
  }
})();
"""
