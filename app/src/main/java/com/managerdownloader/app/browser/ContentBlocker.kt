package com.managerdownloader.app.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.managerdownloader.app.data.AdBlockMode
import com.managerdownloader.app.data.SettingsRepository
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Request-level content blocker for the embedded WebView.
 *
 * STANDARD mode is intentionally conservative: it never blocks the main document,
 * non-GET requests or same-site resources. This prevents the common "blank page"
 * failure mode of simplistic host blockers. STRICT mode blocks matching subresources
 * regardless of first/third party, while still never replacing the main frame.
 */
object ContentBlocker {
    private const val PREFS = "content_blocker"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_ALLOWED_SITES = "allowed_sites"
    private const val AD_CACHE = "easylist_hosts.txt"
    private const val HOSTS_CACHE = "stevenblack_hosts.txt"
    private const val PRIVACY_CACHE = "easyprivacy_hosts.txt"
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    private const val STEVENBLACK_HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"

    private lateinit var appContext: Context
    private var initialized = false

    @Volatile
    private var adHosts: Set<String> = emptySet()

    @Volatile
    private var trackerHosts: Set<String> = emptySet()

    @Volatile
    private var allowedSites: Set<String> = emptySet()

    @Volatile
    private var activePageHost: String? = null

    private val blocked = AtomicLong(0L)
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        allowedSites = prefs.getStringSet(KEY_ALLOWED_SITES, emptySet())
            ?.map { it.lowercase(Locale.US).trimEnd('.') }
            ?.toSet()
            .orEmpty()
        adHosts = DEFAULT_AD_HOSTS + readCache(AD_CACHE) + readCache(HOSTS_CACHE)
        trackerHosts = DEFAULT_TRACKER_HOSTS + readCache(PRIVACY_CACHE)
        initialized = true
        refreshIfStale()
    }

    fun refreshIfStale(force: Boolean = false) {
        if (!initialized) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (!force && System.currentTimeMillis() - updatedAt < REFRESH_INTERVAL_MS) return

        executor.execute {
            val downloadedEasyList = downloadRules(EASYLIST_URL)
            val downloadedHosts = downloadRules(STEVENBLACK_HOSTS_URL)
            val downloadedPrivacy = downloadRules(EASYPRIVACY_URL)

            val easyList = downloadedEasyList.ifEmpty { readCache(AD_CACHE) }
            val hosts = downloadedHosts.ifEmpty { readCache(HOSTS_CACHE) }
            val privacy = downloadedPrivacy.ifEmpty { readCache(PRIVACY_CACHE) }

            adHosts = DEFAULT_AD_HOSTS + easyList + hosts
            trackerHosts = DEFAULT_TRACKER_HOSTS + privacy

            if (downloadedEasyList.isNotEmpty()) writeCache(AD_CACHE, downloadedEasyList)
            if (downloadedHosts.isNotEmpty()) writeCache(HOSTS_CACHE, downloadedHosts)
            if (downloadedPrivacy.isNotEmpty()) writeCache(PRIVACY_CACHE, downloadedPrivacy)

            if (downloadedEasyList.isNotEmpty() || downloadedHosts.isNotEmpty() || downloadedPrivacy.isNotEmpty()) {
                prefs.edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
            }
        }
    }

    fun setActivePage(url: String?) {
        activePageHost = hostOf(url)
    }

    fun isCurrentSiteAllowed(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return matchesHost(host, allowedSites)
    }

    fun setCurrentSiteAllowed(url: String?, allowed: Boolean) {
        if (!initialized) return
        val host = hostOf(url) ?: return
        val next = allowedSites.toMutableSet().apply {
            if (allowed) add(host) else remove(host)
        }.toSet()
        allowedSites = next
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ALLOWED_SITES, HashSet(next))
            .apply()
    }

    fun shouldBlock(
        url: String?,
        isMainFrame: Boolean = false,
        method: String? = "GET"
    ): Boolean {
        if (!initialized || url.isNullOrBlank()) return false
        val settings = SettingsRepository.settings.value
        if (!settings.adBlockEnabled) return false

        // Never replace the main HTML document. Returning a synthetic response here can
        // make the complete WebView appear blank.
        if (isMainFrame) return false

        // Do not interfere with form submissions / API writes.
        if (!method.isNullOrBlank() && !method.equals("GET", ignoreCase = true)) return false

        val topHost = activePageHost
        if (topHost != null && matchesHost(topHost, allowedSites)) return false

        val host = hostOf(url) ?: return false
        if (host == "localhost" || host.endsWith(".local")) return false
        if (matchesHost(host, ESSENTIAL_COMPAT_HOSTS)) return false

        val matched = matchesHost(host, adHosts) ||
            (settings.blockTrackers && matchesHost(host, trackerHosts))
        if (!matched) return false

        if (settings.adBlockMode == AdBlockMode.STANDARD && topHost != null && sameSite(host, topHost)) {
            return false
        }

        blocked.incrementAndGet()
        return true
    }

    fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    fun ruleCount(): Int = adHosts.size + trackerHosts.size

    fun blockedCount(): Long = blocked.get()

    private fun hostOf(url: String?): String? = runCatching {
        Uri.parse(url).host
    }.getOrNull()
        ?.lowercase(Locale.US)
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }

    private fun sameSite(host: String, topHost: String): Boolean =
        host == topHost || host.endsWith(".$topHost") || topHost.endsWith(".$host")

    private fun matchesHost(host: String, rules: Set<String>): Boolean {
        if (host in rules) return true
        var dot = host.indexOf('.')
        while (dot >= 0 && dot + 1 < host.length) {
            val parent = host.substring(dot + 1)
            if (parent in rules) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    private fun downloadRules(url: String): Set<String> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ManagerDownloader/0.6")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptySet<String>()
            parseHosts(response.body?.string().orEmpty())
        }
    }.getOrDefault(emptySet<String>())

    /** Extract the host-based subset of EasyList/EasyPrivacy/hosts sources. */
    private fun parseHosts(text: String): Set<String> = buildSet {
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) {
                return@forEach
            }

            if (line.startsWith("||")) {
                val options = line.substringAfter('$', "")
                if (
                    options.contains("domain=", true) ||
                    options.contains("redirect", true) ||
                    options.contains("csp", true) ||
                    options.contains("badfilter", true) ||
                    options.contains("removeparam", true)
                ) return@forEach

                val rulePart = line.substringBefore('$')
                val domain = rulePart
                    .removePrefix("||")
                    .substringBefore('^')
                    .substringBefore('/')
                    .trim()
                    .lowercase(Locale.US)
                if (isDomain(domain)) add(domain)
                return@forEach
            }

            if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                val domain = line.substringAfter(' ').trim().substringBefore(' ').lowercase(Locale.US)
                if (isDomain(domain)) add(domain)
            }
        }
    }

    private fun isDomain(value: String): Boolean =
        value.length in 4..253 &&
            value.contains('.') &&
            value.none { it == '*' || it == '/' || it == '|' || it == ' ' } &&
            value.split('.').all { part ->
                part.isNotBlank() && part.length <= 63 &&
                    part.firstOrNull()?.isLetterOrDigit() == true &&
                    part.lastOrNull()?.isLetterOrDigit() == true
            }

    private fun readCache(name: String): Set<String> = runCatching {
        val file = java.io.File(appContext.filesDir, name)
        if (!file.exists()) return@runCatching emptySet<String>()
        file.readLines()
            .map { it.trim().lowercase(Locale.US) }
            .filter(::isDomain)
            .toSet()
    }.getOrDefault(emptySet<String>())

    private fun writeCache(name: String, hosts: Set<String>) {
        runCatching {
            java.io.File(appContext.filesDir, name)
                .writeText(hosts.sorted().joinToString("\n"))
        }
    }

    private val ESSENTIAL_COMPAT_HOSTS = setOf(
        "gstatic.com",
        "googleusercontent.com",
        "googleapis.com"
    )

    private val DEFAULT_AD_HOSTS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "amazon-adsystem.com",
        "adsrvr.org",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net"
    )

    private val DEFAULT_TRACKER_HOSTS = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "segment.io",
        "mixpanel.com"
    )
}
