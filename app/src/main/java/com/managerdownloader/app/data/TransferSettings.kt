package com.managerdownloader.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QueueMode {
    SEQUENTIAL,
    PARALLEL
}

enum class SearchEngine {
    DUCKDUCKGO,
    GOOGLE,
    BING,
    BRAVE
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AdBlockMode {
    STANDARD,
    STRICT
}

data class TransferSettings(
    val queueMode: QueueMode = QueueMode.SEQUENTIAL,
    val maxParallelDownloads: Int = 3,
    val segmentsPerFile: Int = 6,
    val turboMode: Boolean = true,
    val segmentRetryCount: Int = 2,
    val adBlockEnabled: Boolean = true,
    val blockTrackers: Boolean = true,
    val adBlockMode: AdBlockMode = AdBlockMode.STANDARD,
    val wifiOnly: Boolean = false,
    /** 0 = unlimited. */
    val bandwidthLimitMbps: Int = 0,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val chromeCompatUserAgent: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val mediaSnifferEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
) {
    val activeTransferLimit: Int
        get() = if (queueMode == QueueMode.SEQUENTIAL) 1 else maxParallelDownloads.coerceIn(2, 6)

    val bandwidthLimitBytesPerSecond: Long
        get() = if (bandwidthLimitMbps <= 0) 0L else bandwidthLimitMbps.toLong() * 1024L * 1024L
}

object SettingsRepository {
    private const val PREFS = "transfer_settings"
    private const val KEY_QUEUE_MODE = "queue_mode"
    private const val KEY_MAX_PARALLEL = "max_parallel"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_TURBO = "turbo_mode"
    private const val KEY_RETRIES = "segment_retries"
    private const val KEY_ADBLOCK = "adblock"
    private const val KEY_TRACKERS = "trackers"
    private const val KEY_ADBLOCK_MODE = "adblock_mode"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_BANDWIDTH = "bandwidth_mbps"
    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_CHROME_UA = "chrome_compat_ua"
    private const val KEY_THIRD_PARTY_COOKIES = "third_party_cookies"
    private const val KEY_MEDIA_SNIFFER = "media_sniffer"
    private const val KEY_THEME_MODE = "theme_mode"

    private lateinit var appContext: Context
    private var initialized = false

    private val _settings = MutableStateFlow(TransferSettings())
    val settings: StateFlow<TransferSettings> = _settings.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = TransferSettings(
            queueMode = runCatching {
                QueueMode.valueOf(prefs.getString(KEY_QUEUE_MODE, QueueMode.SEQUENTIAL.name)!!)
            }.getOrDefault(QueueMode.SEQUENTIAL),
            maxParallelDownloads = prefs.getInt(KEY_MAX_PARALLEL, 3).coerceIn(2, 6),
            segmentsPerFile = prefs.getInt(KEY_SEGMENTS, 6).coerceIn(1, 8),
            turboMode = prefs.getBoolean(KEY_TURBO, true),
            segmentRetryCount = prefs.getInt(KEY_RETRIES, 2).coerceIn(0, 5),
            adBlockEnabled = prefs.getBoolean(KEY_ADBLOCK, true),
            blockTrackers = prefs.getBoolean(KEY_TRACKERS, true),
            adBlockMode = runCatching {
                AdBlockMode.valueOf(prefs.getString(KEY_ADBLOCK_MODE, AdBlockMode.STANDARD.name)!!)
            }.getOrDefault(AdBlockMode.STANDARD),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            bandwidthLimitMbps = prefs.getInt(KEY_BANDWIDTH, 0).coerceIn(0, 100),
            searchEngine = runCatching {
                SearchEngine.valueOf(prefs.getString(KEY_SEARCH_ENGINE, SearchEngine.DUCKDUCKGO.name)!!)
            }.getOrDefault(SearchEngine.DUCKDUCKGO),
            chromeCompatUserAgent = prefs.getBoolean(KEY_CHROME_UA, true),
            thirdPartyCookies = prefs.getBoolean(KEY_THIRD_PARTY_COOKIES, false),
            mediaSnifferEnabled = prefs.getBoolean(KEY_MEDIA_SNIFFER, true),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)!!)
            }.getOrDefault(ThemeMode.SYSTEM)
        )
        initialized = true
    }

    fun setQueueMode(value: QueueMode) = update { it.copy(queueMode = value) }

    fun setMaxParallelDownloads(value: Int) =
        update { it.copy(maxParallelDownloads = value.coerceIn(2, 6)) }

    fun setSegmentsPerFile(value: Int) =
        update { it.copy(segmentsPerFile = value.coerceIn(1, 8)) }

    fun setTurboMode(value: Boolean) = update { it.copy(turboMode = value) }

    fun setSegmentRetryCount(value: Int) =
        update { it.copy(segmentRetryCount = value.coerceIn(0, 5)) }

    fun setAdBlockEnabled(value: Boolean) = update { it.copy(adBlockEnabled = value) }

    fun setBlockTrackers(value: Boolean) = update { it.copy(blockTrackers = value) }

    fun setAdBlockMode(value: AdBlockMode) = update { it.copy(adBlockMode = value) }

    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnly = value) }

    fun setBandwidthLimitMbps(value: Int) =
        update { it.copy(bandwidthLimitMbps = value.coerceIn(0, 100)) }

    fun setSearchEngine(value: SearchEngine) = update { it.copy(searchEngine = value) }

    fun setChromeCompatUserAgent(value: Boolean) = update { it.copy(chromeCompatUserAgent = value) }

    fun setThirdPartyCookies(value: Boolean) = update { it.copy(thirdPartyCookies = value) }

    fun setMediaSnifferEnabled(value: Boolean) = update { it.copy(mediaSnifferEnabled = value) }

    fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }

    private fun update(transform: (TransferSettings) -> TransferSettings) {
        check(initialized) { "SettingsRepository must be initialized first" }
        val next = transform(_settings.value)
        _settings.value = next
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE_MODE, next.queueMode.name)
            .putInt(KEY_MAX_PARALLEL, next.maxParallelDownloads)
            .putInt(KEY_SEGMENTS, next.segmentsPerFile)
            .putBoolean(KEY_TURBO, next.turboMode)
            .putInt(KEY_RETRIES, next.segmentRetryCount)
            .putBoolean(KEY_ADBLOCK, next.adBlockEnabled)
            .putBoolean(KEY_TRACKERS, next.blockTrackers)
            .putString(KEY_ADBLOCK_MODE, next.adBlockMode.name)
            .putBoolean(KEY_WIFI_ONLY, next.wifiOnly)
            .putInt(KEY_BANDWIDTH, next.bandwidthLimitMbps)
            .putString(KEY_SEARCH_ENGINE, next.searchEngine.name)
            .putBoolean(KEY_CHROME_UA, next.chromeCompatUserAgent)
            .putBoolean(KEY_THIRD_PARTY_COOKIES, next.thirdPartyCookies)
            .putBoolean(KEY_MEDIA_SNIFFER, next.mediaSnifferEnabled)
            .putString(KEY_THEME_MODE, next.themeMode.name)
            .apply()
    }
}
