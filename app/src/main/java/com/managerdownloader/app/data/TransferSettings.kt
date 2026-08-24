package com.managerdownloader.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QueueMode {
    SEQUENTIAL,
    PARALLEL
}

data class TransferSettings(
    val queueMode: QueueMode = QueueMode.SEQUENTIAL,
    val maxParallelDownloads: Int = 3,
    val segmentsPerFile: Int = 4,
    val adBlockEnabled: Boolean = true,
    val blockTrackers: Boolean = true
) {
    val activeTransferLimit: Int
        get() = if (queueMode == QueueMode.SEQUENTIAL) 1 else maxParallelDownloads.coerceIn(2, 6)
}

object SettingsRepository {
    private const val PREFS = "transfer_settings"
    private const val KEY_QUEUE_MODE = "queue_mode"
    private const val KEY_MAX_PARALLEL = "max_parallel"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_ADBLOCK = "adblock"
    private const val KEY_TRACKERS = "trackers"

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
            segmentsPerFile = prefs.getInt(KEY_SEGMENTS, 6).coerceIn(1, 12),
            adBlockEnabled = prefs.getBoolean(KEY_ADBLOCK, true),
            blockTrackers = prefs.getBoolean(KEY_TRACKERS, true)
        )
        initialized = true
    }

    fun setQueueMode(value: QueueMode) = update { it.copy(queueMode = value) }

    fun setMaxParallelDownloads(value: Int) =
        update { it.copy(maxParallelDownloads = value.coerceIn(2, 6)) }

    fun setSegmentsPerFile(value: Int) =
        update { it.copy(segmentsPerFile = value.coerceIn(1, 12)) }

    fun setAdBlockEnabled(value: Boolean) = update { it.copy(adBlockEnabled = value) }

    fun setBlockTrackers(value: Boolean) = update { it.copy(blockTrackers = value) }

    private fun update(transform: (TransferSettings) -> TransferSettings) {
        check(initialized) { "SettingsRepository must be initialized first" }
        val next = transform(_settings.value)
        _settings.value = next
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE_MODE, next.queueMode.name)
            .putInt(KEY_MAX_PARALLEL, next.maxParallelDownloads)
            .putInt(KEY_SEGMENTS, next.segmentsPerFile)
            .putBoolean(KEY_ADBLOCK, next.adBlockEnabled)
            .putBoolean(KEY_TRACKERS, next.blockTrackers)
            .apply()
    }
}
