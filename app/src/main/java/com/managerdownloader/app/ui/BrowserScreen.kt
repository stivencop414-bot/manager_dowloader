package com.managerdownloader.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.browser.MediaSnifferFilter
import com.managerdownloader.app.data.DownloadKind
import com.managerdownloader.app.data.SearchEngine
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.hls.HlsManifestClient
import com.managerdownloader.app.hls.HlsVariantStream
import com.managerdownloader.app.youtube.YouTubeExtractorClient
import com.managerdownloader.app.youtube.YouTubeFormatKind
import com.managerdownloader.app.youtube.YouTubeLink
import com.managerdownloader.app.youtube.YouTubePlaylistDetails
import com.managerdownloader.app.youtube.YouTubePlaylistExtractor
import com.managerdownloader.app.youtube.YouTubeUrlParser
import com.managerdownloader.app.youtube.YouTubeVideoDetails
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

data class BrowserDownloadRequest(
    val url: String,
    val filename: String?,
    val cookie: String?,
    val userAgent: String?,
    val referer: String?,
    val originalSourceUrl: String? = null,
    val sourceFormatId: String? = null,
    val kind: DownloadKind? = null,
    val secondaryUrl: String? = null,
    val secondarySourceFormatId: String? = null,
    val muxContainer: String? = null,
    val sourceProfile: String? = null
)

private enum class DetectedMediaKind(val label: String) {
    DIRECT("Directo"),
    STREAM("Stream"),
    BLOB("Blob")
}

private enum class MediaBatchFilter(val label: String) {
    ALL("Todos"),
    DIRECT("Descargables"),
    STREAM("Streams"),
    BLOB("Blob")
}

private data class DetectedDownload(
    val url: String,
    val filename: String,
    val cookie: String?,
    val userAgent: String?,
    val referer: String? = null,
    val mimeType: String? = null,
    val kind: DetectedMediaKind = DetectedMediaKind.DIRECT
) {
    val downloadable: Boolean get() = kind == DetectedMediaKind.DIRECT
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    contentPadding: PaddingValues,
    onAdd: (String, String?, String?, String?, String?, String?, String?) -> Unit,
    onAddBatch: (List<BrowserDownloadRequest>) -> Unit,
    initialUrl: String? = null,
    incomingUrl: String? = null,
    isVisible: Boolean = true,
    onCurrentUrlChanged: (String) -> Unit = {},
    onIncomingUrlConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by SettingsRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val initialBrowserUrl = remember(initialUrl, settings.searchEngine) {
        initialUrl?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: homeUrl(settings.searchEngine)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(initialBrowserUrl) }
    var currentUrl by remember { mutableStateOf(initialBrowserUrl) }
    var pageTitle by remember { mutableStateOf("Navegador") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf<DetectedDownload?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var blockerRevision by remember { mutableIntStateOf(0) }
    var lastSearchInput by remember { mutableStateOf<String?>(null) }
    val mediaDetected = remember { mutableStateListOf<DetectedDownload>() }
    val selectedMediaUrls = remember { mutableStateListOf<String>() }
    var showMediaDetected by remember { mutableStateOf(false) }
    var youtubeLink by remember { mutableStateOf<YouTubeLink?>(null) }
    var youtubeDetails by remember { mutableStateOf<YouTubeVideoDetails?>(null) }
    var youtubePlaylistDetails by remember { mutableStateOf<YouTubePlaylistDetails?>(null) }
    val selectedPlaylistUrls = remember { mutableStateListOf<String>() }
    var streamVariants by remember { mutableStateOf<List<HlsVariantStream>>(emptyList()) }
    var streamAnalyzedUrl by remember { mutableStateOf<String?>(null) }
    var streamLoading by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var youtubeError by remember { mutableStateOf<String?>(null) }
    var youtubeLoading by remember { mutableStateOf(false) }
    var showYouTubeDialog by remember { mutableStateOf(false) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var rendererRecoveryUrl by remember { mutableStateOf<String?>(null) }
    var browserSafeMode by remember { mutableStateOf(false) }
    val browserSafeModeRef = remember { AtomicBoolean(false) }
    val activePageUrlRef = remember { AtomicReference(currentUrl) }

    fun rememberMedia(item: DetectedDownload) {
        if (!SettingsRepository.settings.value.mediaSnifferEnabled) return
        if (item.url.isBlank()) return
        if (!MediaSnifferFilter.isCleanMediaCandidate(item.url)) return

        val canonicalKey = MediaSnifferFilter.canonicalMediaUrl(item.url)
        val duplicate = mediaDetected.any {
            MediaSnifferFilter.canonicalMediaUrl(it.url) == canonicalKey
        }
        if (!duplicate) {
            mediaDetected.add(item)
            while (mediaDetected.size > MAX_DETECTED_ITEMS) {
                val removed = mediaDetected.removeAt(0)
                selectedMediaUrls.remove(removed.url)
            }
        }
    }

    fun analyzeYouTube(link: YouTubeLink) {
        youtubeLoading = true
        youtubeError = null
        youtubeDetails = null
        youtubePlaylistDetails = null
        selectedPlaylistUrls.clear()
        showYouTubeDialog = true
        scope.launch {
            if (link.isVideo) {
                val result = YouTubeExtractorClient.analyze(link.canonicalUrl ?: link.rawUrl)
                youtubeLoading = false
                result.onSuccess { youtubeDetails = it }
                    .onFailure { error -> youtubeError = error.message ?: error.javaClass.simpleName }
            } else if (link.playlistId != null) {
                val result = YouTubePlaylistExtractor.analyze(link.rawUrl)
                youtubeLoading = false
                result.onSuccess { youtubePlaylistDetails = it }
                    .onFailure { error -> youtubeError = error.message ?: error.javaClass.simpleName }
            } else {
                youtubeLoading = false
                youtubeError = "El enlace de YouTube no contiene un video o playlist compatible."
            }
        }
    }

    fun analyzeStream(item: DetectedDownload) {
        if (item.kind != DetectedMediaKind.STREAM || !item.url.contains(".m3u8", true)) return
        streamLoading = true
        streamError = null
        streamVariants = emptyList()
        streamAnalyzedUrl = item.url
        scope.launch {
            val result = HlsManifestClient.analyze(item.url, item.cookie, item.userAgent, item.referer)
            streamLoading = false
            result.onSuccess { streamVariants = it }
                .onFailure { error -> streamError = error.message ?: error.javaClass.simpleName }
        }
    }

    fun updateYouTubeLink(url: String?) {
        youtubeLink = YouTubeUrlParser.parse(url)
        if (youtubeLink == null) {
            youtubeDetails = null
            youtubePlaylistDetails = null
            selectedPlaylistUrls.clear()
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
        ContentBlocker.setEmergencyBypass(false)
        installServiceWorkerBlocker()
    }

    LaunchedEffect(incomingUrl, webView, webViewGeneration) {
        val url = incomingUrl?.trim().orEmpty()
        if (url.isNotBlank() && webView != null) {
            navigate(url)
            YouTubeUrlParser.parse(url)?.let { analyzeYouTube(it) }
            onIncomingUrlConsumed()
        }
    }

    LaunchedEffect(isVisible, webView) {
        val view = webView ?: return@LaunchedEffect
        runCatching {
            if (isVisible) {
                view.onResume()
                view.resumeTimers()
            } else {
                view.onPause()
                view.pauseTimers()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (isVisible) 1f else 0f)
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

        if (browserSafeMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Modo seguro: AdBlock y sniffer pausados tras recuperar Chromium.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    browserSafeMode = false
                    browserSafeModeRef.set(false)
                    ContentBlocker.setEmergencyBypass(false)
                    pageError = null
                    webView?.reload()
                }) { Text("Reactivar") }
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            key(webViewGeneration) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        val host = FrameLayout(context)
                        var candidate: WebView? = null
                        runCatching {
                            WebView(context).also { candidate = it }.apply {
                                val createdView = this
                                runCatching { resumeTimers() }
                                runCatching { onResume() }

                                runCatching { this.settings.javaScriptEnabled = true }
                                runCatching { this.settings.domStorageEnabled = true }
                                runCatching { this.settings.databaseEnabled = true }
                                runCatching { this.settings.allowFileAccess = false }
                                runCatching { this.settings.allowContentAccess = true }
                                runCatching { this.settings.setSupportMultipleWindows(false) }
                                runCatching { this.settings.javaScriptCanOpenWindowsAutomatically = false }
                                runCatching { this.settings.mediaPlaybackRequiresUserGesture = true }
                                runCatching { this.settings.builtInZoomControls = true }
                                runCatching { this.settings.displayZoomControls = false }
                                runCatching { this.settings.loadWithOverviewMode = true }
                                runCatching { this.settings.useWideViewPort = true }
                                runCatching { this.settings.cacheMode = WebSettings.LOAD_DEFAULT }
                                runCatching { this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW }
                                runCatching { this.settings.safeBrowsingEnabled = true }

                                runCatching { CookieManager.getInstance().setAcceptCookie(true) }
                                runCatching {
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(
                                        this,
                                        SettingsRepository.settings.value.thirdPartyCookies
                                    )
                                }
                                val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }
                                    .getOrDefault(DEFAULT_BROWSER_UA)
                                runCatching {
                                    this.settings.userAgentString = if (SettingsRepository.settings.value.chromeCompatUserAgent) {
                                        chromeCompatibleUserAgent(defaultUa)
                                    } else {
                                        defaultUa
                                    }
                                }
                                runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false) }

                                runCatching {
                                    addJavascriptInterface(
                                        MediaSnifferBridge(
                                            webViewProvider = { createdView },
                                            onFound = { rawUrl, typeHint ->
                                                if (!browserSafeModeRef.get() && YouTubeUrlParser.parse(activePageUrlRef.get()) == null) {
                                                    val kind = mediaKindForUrl(rawUrl, typeHint)
                                                    if (kind != null) {
                                                        rememberMedia(
                                                            detectedItem(
                                                                url = rawUrl,
                                                                webView = createdView,
                                                                kind = kind,
                                                                referer = activePageUrlRef.get()
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        ),
                                        JS_BRIDGE_NAME
                                    )
                                }

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
                                        if (browserSafeModeRef.get()) return null
                                        val url = request?.url?.toString()
                                        val pageSnapshot = activePageUrlRef.get()
                                        val kind = url?.let { mediaKindForUrl(it, null) }
                                        if (
                                            SettingsRepository.settings.value.mediaSnifferEnabled &&
                                            YouTubeUrlParser.parse(pageSnapshot) == null &&
                                            request?.isForMainFrame != true &&
                                            url != null &&
                                            kind != null &&
                                            MediaSnifferFilter.isCleanMediaCandidate(url)
                                        ) {
                                            val headerReferer = request.requestHeaders.entries
                                                .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                                                ?.value
                                            view?.post {
                                                if (webView === view && view.isAttachedToWindow) {
                                                    rememberMedia(
                                                        detectedItem(
                                                            url = url,
                                                            webView = view,
                                                            kind = kind,
                                                            referer = headerReferer ?: activePageUrlRef.get()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        return if (ContentBlocker.shouldBlock(url, request?.isForMainFrame == true, request?.method)) {
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
                                        if (url.isBlank()) return false
                                        if (
                                            url.startsWith("magnet:", ignoreCase = true) ||
                                            url.substringBefore('?').endsWith(".torrent", ignoreCase = true)
                                        ) {
                                            detected = detectedItem(url, view, referer = activePageUrlRef.get())
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
                                        activePageUrlRef.set(url)
                                        onCurrentUrlChanged(url)
                                        address = url
                                        canGoBack = runCatching { view.canGoBack() }.getOrDefault(false)
                                        canGoForward = runCatching { view.canGoForward() }.getOrDefault(false)
                                        ContentBlocker.setActivePage(url)
                                        updateYouTubeLink(url)
                                        mediaDetected.clear()
                                        selectedMediaUrls.clear()
                                        showMediaDetected = false
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        currentUrl = url
                                        activePageUrlRef.set(url)
                                        onCurrentUrlChanged(url)
                                        rendererRecoveryUrl = null
                                        address = url
                                        canGoBack = runCatching { view.canGoBack() }.getOrDefault(false)
                                        canGoForward = runCatching { view.canGoForward() }.getOrDefault(false)
                                        ContentBlocker.setActivePage(url)
                                        if (!browserSafeModeRef.get() && SettingsRepository.settings.value.mediaSnifferEnabled && YouTubeUrlParser.parse(url) == null) {
                                            installBoundedMediaSniffer(view)
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
                                        val recovery = activePageUrlRef.get()
                                        rendererRecoveryUrl = recovery
                                        browserSafeMode = true
                                        browserSafeModeRef.set(true)
                                        ContentBlocker.setEmergencyBypass(true)
                                        if (webView === view) webView = null
                                        destroyDeadWebView(view)
                                        val crashed = detail?.didCrash() ?: true
                                        Log.e("WebViewStability", "Renderer terminado. didCrash=$crashed")
                                        pageError = if (crashed) {
                                            "El motor del navegador falló; se recreó en modo seguro."
                                        } else {
                                            "Android liberó Chromium por memoria; se recuperó en modo seguro."
                                        }
                                        webViewGeneration++
                                        return true
                                    }
                                }

                                setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                                    if (!isDownloadableScheme(url)) return@setDownloadListener
                                    if (!isRealFileDownload(url, mimeType, contentDisposition)) return@setDownloadListener

                                    val item = DetectedDownload(
                                        url = url,
                                        filename = if (url.startsWith("magnet:", true)) {
                                            magnetName(url)
                                        } else {
                                            URLUtil.guessFileName(url, contentDisposition, mimeType)
                                        },
                                        cookie = if (url.startsWith("http", true)) {
                                            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                                        } else {
                                            null
                                        },
                                        userAgent = userAgent,
                                        referer = activePageUrlRef.get(),
                                        mimeType = mimeType,
                                        kind = DetectedMediaKind.DIRECT
                                    )
                                    if (
                                        SettingsRepository.settings.value.mediaSnifferEnabled &&
                                        YouTubeUrlParser.parse(activePageUrlRef.get()) == null &&
                                        MediaSnifferFilter.isCleanMediaCandidate(url, contentLength.takeIf { it > 0L })
                                    ) {
                                        rememberMedia(item)
                                    }
                                    // A real user-triggered DownloadListener remains actionable even for a
                                    // small file; the 150 KB rule only cleans the passive sniffer list.
                                    detected = item
                                }

                                val initial = rendererRecoveryUrl
                                    ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                                    ?: activePageUrlRef.get().takeIf { it.isNotBlank() }
                                    ?: homeUrl(SettingsRepository.settings.value.searchEngine)
                                currentUrl = initial
                                activePageUrlRef.set(initial)
                                address = initial
                                webView = this
                                host.addView(
                                    this,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                                loadUrl(initial)
                            }
                        }.onFailure { error ->
                            val failedView = candidate
                            if (webView === failedView) webView = null
                            cleanupWebView(failedView)
                            pageError = "No se pudo iniciar el navegador integrado. Actualiza Android System WebView/Chrome y pulsa Reintentar."
                            Log.e("WebViewStability", "No se pudo crear/configurar WebView", error)
                        }
                        host
                    },
                    update = { _ ->
                        webView?.let { view ->
                            val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }
                                .getOrDefault(DEFAULT_BROWSER_UA)
                            val desiredUa = if (settings.chromeCompatUserAgent) chromeCompatibleUserAgent(defaultUa) else defaultUa
                            runCatching {
                                if (view.settings.userAgentString != desiredUa) {
                                    view.settings.userAgentString = desiredUa
                                }
                            }
                            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.thirdPartyCookies) }
                        }
                    }
                )
            }

            if (mediaDetected.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        triggerActiveQualityRescan(webView)
                        showMediaDetected = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(if (mediaDetected.size > 99) "99+" else mediaDetected.size.toString())
                            }
                        }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Descargas detectadas")
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ContentBlocker.setActivePage(null)
            ContentBlocker.setEmergencyBypass(false)
            val view = webView
            webView = null
            cleanupWebView(view)
        }
    }

    val visibleYouTubeLink = youtubeLink
    if (isVisible && showYouTubeDialog && visibleYouTubeLink != null) {
        val link = visibleYouTubeLink
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
                                        onAdd(
                                            option.url,
                                            option.filename,
                                            cookie,
                                            runCatching { webView?.settings?.userAgentString }.getOrNull(),
                                            details.sourceUrl,
                                            details.sourceUrl,
                                            option.id
                                        )
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
                                        onAdd(
                                            option.url,
                                            option.filename,
                                            cookie,
                                            runCatching { webView?.settings?.userAgentString }.getOrNull(),
                                            details.sourceUrl,
                                            details.sourceUrl,
                                            option.id
                                        )
                                        showYouTubeDialog = false
                                    }
                                ) { Text("Descargar ${option.label}") }
                            }
                        }

                        if (details.muxedHighQuality.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Alta calidad fusionada", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Descarga video y audio por separado y los fusiona localmente con MediaMuxer, sin recodificar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            details.muxedHighQuality.take(8).forEach { option ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        val cookie = CookieManager.getInstance().getCookie(details.sourceUrl)
                                        onAddBatch(
                                            listOf(
                                                BrowserDownloadRequest(
                                                    url = option.videoUrl,
                                                    filename = option.filename,
                                                    cookie = cookie,
                                                    userAgent = runCatching { webView?.settings?.userAgentString }.getOrNull(),
                                                    referer = details.sourceUrl,
                                                    originalSourceUrl = details.sourceUrl,
                                                    sourceFormatId = option.videoFormatId,
                                                    kind = DownloadKind.YOUTUBE_MUXED,
                                                    secondaryUrl = option.audioUrl,
                                                    secondarySourceFormatId = option.audioFormatId,
                                                    muxContainer = option.container
                                                )
                                            )
                                        )
                                        showYouTubeDialog = false
                                    }
                                ) { Text("Descargar ${option.label}") }
                            }
                        } else if (details.videoOnly.isNotEmpty()) {
                            HorizontalDivider()
                            Text("Calidad alta separada", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Hay pistas separadas, pero ninguna pareja es compatible con el muxer nativo del dispositivo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            "Úsalo únicamente con contenido que tengas permiso para descargar. No se intenta eludir DRM, contenido privado ni controles de acceso.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    youtubePlaylistDetails?.let { playlist ->
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                        if (playlist.author.isNotBlank()) Text(playlist.author, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${playlist.items.size} videos${if (playlist.truncated) " · límite de 200 por análisis" else ""}. Los enlaces directos se resuelven justo cuando cada video obtiene turno para reducir 403/429.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allSelected = playlist.items.isNotEmpty() && playlist.items.all { it.canonicalUrl in selectedPlaylistUrls }
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = { checked ->
                                    selectedPlaylistUrls.clear()
                                    if (checked) selectedPlaylistUrls.addAll(playlist.items.map { it.canonicalUrl })
                                }
                            )
                            Text(if (allSelected) "Deseleccionar todo" else "Seleccionar todo")
                        }
                        Column(
                            modifier = Modifier.height(280.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            playlist.items.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = item.canonicalUrl in selectedPlaylistUrls,
                                        onCheckedChange = { checked ->
                                            if (checked && item.canonicalUrl !in selectedPlaylistUrls) selectedPlaylistUrls.add(item.canonicalUrl)
                                            if (!checked) selectedPlaylistUrls.remove(item.canonicalUrl)
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text("${item.position}. ${item.title}", maxLines = 2)
                                        if (item.uploader.isNotBlank()) Text(item.uploader, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedPlaylistUrls.isNotEmpty(),
                            onClick = {
                                val selected = selectedPlaylistUrls.toSet()
                                val userAgent = runCatching { webView?.settings?.userAgentString }.getOrNull()
                                val requests = playlist.items.filter { it.canonicalUrl in selected }.map { item ->
                                    BrowserDownloadRequest(
                                        url = item.canonicalUrl,
                                        filename = "${item.title}.mp4",
                                        cookie = null,
                                        userAgent = userAgent,
                                        referer = item.canonicalUrl,
                                        originalSourceUrl = item.canonicalUrl,
                                        kind = DownloadKind.YOUTUBE_JIT,
                                        sourceProfile = "best_progressive"
                                    )
                                }
                                onAddBatch(requests)
                                selectedPlaylistUrls.clear()
                                showYouTubeDialog = false
                            }
                        ) { Text("Añadir selección a la cola") }
                    }
                }
            },
            confirmButton = {
                if (youtubeDetails == null && youtubePlaylistDetails == null && !youtubeLoading) {
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

    if (isVisible && showMediaDetected) {
        MediaBatchSheet(
            items = mediaDetected.toList(),
            selectedUrls = selectedMediaUrls.toSet(),
            onDismiss = { showMediaDetected = false },
            onReplaceSelection = { next ->
                selectedMediaUrls.clear()
                selectedMediaUrls.addAll(next)
            },
            onOpenItem = { item ->
                detected = item
                showMediaDetected = false
            },
            onDownloadSelected = {
                val selected = selectedMediaUrls.toSet()
                val requests = mediaDetected
                    .filter { it.downloadable && it.url in selected }
                    .map { item ->
                        BrowserDownloadRequest(
                            url = item.url,
                            filename = item.filename,
                            cookie = item.cookie,
                            userAgent = item.userAgent,
                            referer = item.referer,
                            originalSourceUrl = null,
                            sourceFormatId = null
                        )
                    }
                if (requests.isNotEmpty()) onAddBatch(requests)
                selectedMediaUrls.clear()
                showMediaDetected = false
            }
        )
    }

    if (isVisible) detected?.let { item ->
        AlertDialog(
            onDismissRequest = { detected = null },
            title = {
                Text(
                    when {
                        item.url.startsWith("magnet:", true) || item.url.substringBefore('?').endsWith(".torrent", true) -> "Torrent detectado"
                        item.kind == DetectedMediaKind.STREAM -> "Stream detectado"
                        item.kind == DetectedMediaKind.BLOB -> "Blob detectado"
                        else -> "Descarga detectada"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.filename, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4
                    )
                    if (!item.downloadable) {
                        Text(
                            if (item.kind == DetectedMediaKind.STREAM) {
                                "Es un manifiesto HLS/DASH, no un archivo de video completo. El detector puede analizar un HLS Master y mostrar sus variantes sin guardar el manifiesto como .txt."
                            } else {
                                "Es una URL blob interna de la página. WebView no expone sus bytes como un archivo HTTP directo; se muestra solo como diagnóstico."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.kind == DetectedMediaKind.STREAM && streamAnalyzedUrl == item.url) {
                            if (streamLoading) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator()
                                    Text("Analizando manifiesto…")
                                }
                            }
                            streamError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            streamVariants.take(12).forEach { variant ->
                                Text(
                                    "• ${variant.qualityLabel} · ${variant.bandwidth / 1000} kbps${variant.codecs?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!streamLoading && streamError == null && streamVariants.isEmpty()) {
                                Text("El HLS no expone variantes Master; puede ser una playlist de medios directa.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (item.kind == DetectedMediaKind.STREAM && item.url.contains(".m3u8", true)) {
                    Button(
                        enabled = !streamLoading,
                        onClick = { analyzeStream(item) }
                    ) { Text(if (streamAnalyzedUrl == item.url) "Reanalizar calidades" else "Analizar calidades") }
                } else if (item.downloadable) {
                    Button(
                        onClick = {
                            onAdd(item.url, item.filename, item.cookie, item.userAgent, item.referer, null, null)
                            detected = null
                        }
                    ) { Text("Añadir a la cola") }
                } else {
                    TextButton(onClick = { detected = null }) { Text("Cerrar") }
                }
            },
            dismissButton = {
                if (item.downloadable) {
                    TextButton(onClick = { detected = null }) { Text("Cancelar") }
                } else if (item.kind == DetectedMediaKind.STREAM) {
                    TextButton(onClick = { detected = null }) { Text("Cerrar") }
                }
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
                    return if (ContentBlocker.shouldBlock(url, request.isForMainFrame, request.method)) {
                        ContentBlocker.blockedResponse()
                    } else {
                        null
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaBatchSheet(
    items: List<DetectedDownload>,
    selectedUrls: Set<String>,
    onDismiss: () -> Unit,
    onReplaceSelection: (Set<String>) -> Unit,
    onOpenItem: (DetectedDownload) -> Unit,
    onDownloadSelected: () -> Unit
) {
    var filter by remember { mutableStateOf(MediaBatchFilter.ALL) }
    val filtered = remember(items, filter) {
        when (filter) {
            MediaBatchFilter.ALL -> items
            MediaBatchFilter.DIRECT -> items.filter { it.kind == DetectedMediaKind.DIRECT }
            MediaBatchFilter.STREAM -> items.filter { it.kind == DetectedMediaKind.STREAM }
            MediaBatchFilter.BLOB -> items.filter { it.kind == DetectedMediaKind.BLOB }
        }
    }
    val eligibleUrls = filtered.filter { it.downloadable }.map { it.url }.toSet()
    val allEligibleSelected = eligibleUrls.isNotEmpty() && eligibleUrls.all { it in selectedUrls }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Descargas detectadas", style = MaterialTheme.typography.titleLarge)
            Text(
                "${items.size} detectadas · ${selectedUrls.size} seleccionadas. Los HLS/DASH y blob se muestran como diagnóstico, pero no se guardan como archivos de texto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaBatchFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allEligibleSelected,
                    enabled = eligibleUrls.isNotEmpty(),
                    onCheckedChange = { checked ->
                        val next = selectedUrls.toMutableSet()
                        if (checked) next.addAll(eligibleUrls) else next.removeAll(eligibleUrls)
                        onReplaceSelection(next)
                    }
                )
                Text(if (allEligibleSelected) "Deseleccionar descargables" else "Seleccionar descargables")
            }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filtered.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.url in selectedUrls,
                            enabled = item.downloadable,
                            onCheckedChange = { checked ->
                                val next = selectedUrls.toMutableSet()
                                if (checked) next.add(item.url) else next.remove(item.url)
                                onReplaceSelection(next)
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.filename, maxLines = 1)
                            Text(
                                buildString {
                                    append(item.kind.label)
                                    item.mimeType?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onOpenItem(item) }) { Text("Ver") }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUrls.any { selected -> items.any { it.url == selected && it.downloadable } },
                onClick = onDownloadSelected
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Descargar selección")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private class MediaSnifferBridge(
    private val webViewProvider: () -> WebView?,
    private val onFound: (String, String) -> Unit
) {
    private val rateWindowStartedAt = AtomicLong(System.currentTimeMillis())
    private val callbacksInWindow = AtomicInteger(0)

    @JavascriptInterface
    fun onMediaFound(rawUrl: String?, typeHint: String?) {
        val url = rawUrl?.trim().orEmpty()
        if (url.isBlank() || url.length > MAX_SNIFFER_URL_LENGTH || url.startsWith("data:", true)) return
        if (
            !url.startsWith("http://", true) &&
            !url.startsWith("https://", true) &&
            !url.startsWith("blob:", true) &&
            !url.startsWith("magnet:", true)
        ) return

        // The JS interface is reachable by page JavaScript. Cap callbacks so a buggy or hostile
        // page cannot flood the Android main queue and make the browser look like it crashed.
        val now = System.currentTimeMillis()
        val windowStart = rateWindowStartedAt.get()
        if (now - windowStart >= SNIFFER_RATE_WINDOW_MS && rateWindowStartedAt.compareAndSet(windowStart, now)) {
            callbacksInWindow.set(0)
        }
        if (callbacksInWindow.incrementAndGet() > MAX_SNIFFER_CALLBACKS_PER_WINDOW) return

        val view = webViewProvider() ?: return
        view.post {
            if (view.isAttachedToWindow) {
                onFound(url, typeHint.orEmpty().take(24))
            }
        }
    }
}

private fun installBoundedMediaSniffer(view: WebView) {
    if (!view.isAttachedToWindow) return
    runCatching { view.evaluateJavascript(MEDIA_SNIFFER_SCRIPT, null) }
}

private fun triggerActiveQualityRescan(view: WebView?) {
    if (view == null || !view.isAttachedToWindow) return
    runCatching { view.evaluateJavascript("window.__rescanActivePlayerQualities && window.__rescanActivePlayerQualities();", null) }
}

private fun destroyDeadWebView(view: WebView?) {
    if (view == null) return
    runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    runCatching { view.removeJavascriptInterface(JS_BRIDGE_NAME) }
    runCatching { view.destroy() }
}

private fun cleanupWebView(view: WebView?) {
    if (view == null) return
    runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    runCatching { view.stopLoading() }
    runCatching { view.removeJavascriptInterface(JS_BRIDGE_NAME) }
    runCatching { view.webViewClient = WebViewClient() }
    runCatching { view.webChromeClient = null }
    runCatching { view.clearHistory() }
    runCatching { view.loadUrl("about:blank") }
    runCatching { view.onPause() }
    runCatching { view.pauseTimers() }
    runCatching { view.destroy() }
}

private fun detectedItem(
    url: String,
    webView: WebView?,
    kind: DetectedMediaKind = mediaKindForUrl(url, null) ?: DetectedMediaKind.DIRECT,
    mimeType: String? = null,
    referer: String? = null
): DetectedDownload {
    val name = when {
        url.startsWith("magnet:", true) -> magnetName(url)
        kind == DetectedMediaKind.BLOB -> "Blob temporal del reproductor"
        kind == DetectedMediaKind.STREAM && url.contains(".m3u8", true) -> "Stream HLS (.m3u8)"
        kind == DetectedMediaKind.STREAM && url.contains(".mpd", true) -> "Stream DASH (.mpd)"
        else -> URLUtil.guessFileName(url, null, mimeType)
    }
    return DetectedDownload(
        url = url,
        filename = name,
        cookie = if (url.startsWith("http", true)) {
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        } else {
            null
        },
        userAgent = runCatching { webView?.settings?.userAgentString }.getOrNull(),
        referer = referer,
        mimeType = mimeType,
        kind = kind
    )
}

private fun mediaKindForUrl(url: String, typeHint: String?): DetectedMediaKind? {
    val trimmed = url.trim()
    if (trimmed.startsWith("blob:", true) || typeHint.equals("blob", true)) {
        return DetectedMediaKind.BLOB
    }
    if (trimmed.startsWith("magnet:", true)) return DetectedMediaKind.DIRECT
    if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return null
    if (!MediaSnifferFilter.isCleanMediaCandidate(trimmed)) return null

    val lower = trimmed.lowercase()
    val clean = lower.substringBefore('#').substringBefore('?')
    if (MEDIA_FRAGMENT_EXTENSIONS.any { clean.endsWith(it) }) return null
    if (lower.contains(".m3u8") || lower.contains(".mpd")) return DetectedMediaKind.STREAM
    if (DIRECT_SNIFFER_EXTENSIONS.any { clean.endsWith(it) }) return DetectedMediaKind.DIRECT
    if (typeHint.equals("video", true) || typeHint.equals("audio", true) || typeHint.equals("source", true)) {
        return DetectedMediaKind.DIRECT
    }
    return null
}

private fun magnetName(url: String): String = runCatching {
    Uri.parse(url).getQueryParameter("dn")
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "Magnet torrent"

private fun isDownloadableScheme(url: String): Boolean =
    url.startsWith("http://", true) ||
        url.startsWith("https://", true) ||
        url.startsWith("magnet:", true)

private fun isLikelyMediaUrl(url: String): Boolean =
    mediaKindForUrl(url, null) == DetectedMediaKind.DIRECT

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

private val DIRECT_SNIFFER_EXTENSIONS = DIRECT_FILE_EXTENSIONS + setOf(".exe", ".msi", ".doc", ".docx", ".xlsx", ".pptx")
private val MEDIA_FRAGMENT_EXTENSIONS = setOf(".ts", ".m4s", ".cmfv", ".cmfa")

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
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme?.lowercase().orEmpty()

        val intent = if (scheme == "intent") {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // A web page must never be able to target a private component directly.
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                clipData = null
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            if (scheme !in SAFE_EXTERNAL_SCHEMES) return
            Intent(Intent.ACTION_VIEW, parsed).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        context.startActivity(intent)
    }
}

private val SAFE_EXTERNAL_SCHEMES = setOf(
    "mailto", "tel", "sms", "smsto", "geo", "market"
)

private val IPV4_REGEX = Regex("""^(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?$""")

private const val JS_BRIDGE_NAME = "ManagerSnifferBridge"
private const val MAX_DETECTED_ITEMS = 40
private const val MAX_SNIFFER_URL_LENGTH = 8_192
private const val SNIFFER_RATE_WINDOW_MS = 1_000L
private const val MAX_SNIFFER_CALLBACKS_PER_WINDOW = 80
private const val DEFAULT_BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

private const val MEDIA_SNIFFER_SCRIPT = """
(function() {
  try {
    if (window.__managerDownloaderSnifferCleanup) {
      try { window.__managerDownloaderSnifferCleanup(); } catch (_) {}
    }

    const bridge = window.ManagerSnifferBridge;
    if (!bridge || !bridge.onMediaFound) return;
    const seen = new Set();
    const MAX = 40;
    const mediaRx = /\.(mp4|mkv|webm|avi|mov|m4v|3gp|mp3|m4a|aac|flac|wav|ogg|opus|m3u8|mpd|zip|pdf|apk|torrent)(?:$|[?#])/i;
    const ignoredRx = /\.(ts|m4s|cmfv|cmfa|vtt|srt|key|ico|svg|png|jpe?g|gif|webp)(?:$|[?#])/i;
    const trackingRx = /(analytics|telemetry|beacon|pixel|tracker|log_event|\/collect(?:[/?#]|$)|metrics|measurement)/i;

    function canonicalKey(url) {
      try {
        if (!/^https?:/i.test(url)) return url;
        const u = new URL(url);
        ['range', 'bytes', 'start', 'end'].forEach(function(name) { u.searchParams.delete(name); });
        u.hash = '';
        return u.href;
      } catch (_) {
        return url;
      }
    }

    function report(raw, type) {
      try {
        if (!raw || typeof raw !== 'string' || seen.size >= MAX || raw.indexOf('data:') === 0) return;
        let absolute = raw;
        if (raw.indexOf('blob:') !== 0 && raw.indexOf('magnet:') !== 0) {
          absolute = new URL(raw, document.baseURI).href;
        }
        if (ignoredRx.test(absolute) || trackingRx.test(absolute)) return;
        if (!/^https?:/i.test(absolute) && absolute.indexOf('blob:') !== 0 && absolute.indexOf('magnet:') !== 0) return;
        const key = canonicalKey(absolute);
        if (seen.has(key)) return;
        seen.add(key);
        bridge.onMediaFound(absolute, type || 'unknown');
      } catch (_) {}
    }

    const streamRx = /\.(m3u8|mpd)(?:$|[?#])/i;
    const originalFetch = window.fetch;
    const originalXhrOpen = XMLHttpRequest.prototype.open;

    if (originalFetch) {
      window.fetch = function(...args) {
        try {
          const candidate = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
          if (candidate && streamRx.test(candidate)) report(candidate, 'stream_fetch');
        } catch (_) {}
        return originalFetch.apply(this, args);
      };
    }

    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
      try { if (url && streamRx.test(String(url))) report(String(url), 'stream_xhr'); } catch (_) {}
      return originalXhrOpen.call(this, method, url, ...rest);
    };

    window.__rescanActivePlayerQualities = function() {
      try {
        document.querySelectorAll('video,audio,source').forEach(function(el) {
          report(el.currentSrc || el.src || el.getAttribute('src'), 'active_player');
        });
        if (window.videojs && window.videojs.getAllPlayers) {
          window.videojs.getAllPlayers().forEach(function(player) {
            try { report(player.currentSrc(), 'videojs'); } catch (_) {}
          });
        }
      } catch (_) {}
    };

    function inspectElement(el) {
      if (!el || el.nodeType !== 1 || seen.size >= MAX) return;
      const tag = (el.tagName || '').toUpperCase();
      if (tag === 'VIDEO' || tag === 'AUDIO' || tag === 'SOURCE') {
        report(el.currentSrc || el.src || el.getAttribute('src'), tag.toLowerCase());
      } else if (tag === 'A') {
        const href = el.href || el.getAttribute('href');
        if (href && mediaRx.test(href)) report(href, 'link');
      }
    }

    function inspectNode(node) {
      if (!node || node.nodeType !== 1 || seen.size >= MAX) return;
      inspectElement(node);
      if (node.querySelectorAll && seen.size < MAX) {
        node.querySelectorAll('video,audio,source,a[href]').forEach(function(child) {
          if (seen.size < MAX) inspectElement(child);
        });
      }
    }

    document.querySelectorAll('video,audio,source,a[href]').forEach(function(el) {
      if (seen.size < MAX) inspectElement(el);
    });

    const observer = new MutationObserver(function(mutations) {
      if (seen.size >= MAX) {
        observer.disconnect();
        return;
      }
      mutations.forEach(function(mutation) {
        mutation.addedNodes.forEach(function(node) {
          if (seen.size < MAX) inspectNode(node);
        });
      });
    });
    observer.observe(document.documentElement || document.body, {childList:true, subtree:true});

    const originalCreate = URL.createObjectURL.bind(URL);
    const patchedCreate = function(obj) {
      const blobUrl = originalCreate(obj);
      report(blobUrl, 'blob');
      return blobUrl;
    };
    URL.createObjectURL = patchedCreate;

    const timer = setTimeout(function() {
      try { observer.disconnect(); } catch (_) {}
      try { if (URL.createObjectURL === patchedCreate) URL.createObjectURL = originalCreate; } catch (_) {}
      try { if (originalFetch && window.fetch !== originalFetch) window.fetch = originalFetch; } catch (_) {}
      try { if (XMLHttpRequest.prototype.open !== originalXhrOpen) XMLHttpRequest.prototype.open = originalXhrOpen; } catch (_) {}
    }, 15000);

    window.__managerDownloaderSnifferCleanup = function() {
      try { observer.disconnect(); } catch (_) {}
      try { clearTimeout(timer); } catch (_) {}
      try { if (URL.createObjectURL === patchedCreate) URL.createObjectURL = originalCreate; } catch (_) {}
      try { if (originalFetch && window.fetch !== originalFetch) window.fetch = originalFetch; } catch (_) {}
      try { if (XMLHttpRequest.prototype.open !== originalXhrOpen) XMLHttpRequest.prototype.open = originalXhrOpen; } catch (_) {}
      try { delete window.__rescanActivePlayerQualities; } catch (_) {}
    };
  } catch (_) {}
})();
"""
