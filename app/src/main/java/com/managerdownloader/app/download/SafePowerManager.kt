package com.managerdownloader.app.download

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps long user-initiated transfers alive while the screen is off, with defensive timeouts.
 * Locks are acquired only while DownloadService has active work and are always released on idle,
 * timeout or service destruction.
 */
internal class SafePowerManager(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ManagerDownloader::TransferWakeLock"
            )?.apply {
                setReferenceCounted(false)
                runCatching { acquire(MAX_LOCK_MS) }
            }
        }

        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ManagerDownloader::TransferWifiLock"
            )?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
    }

    @Synchronized
    fun release() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        // Shorter than Android 15's dataSync service ceiling and renewed on next service cycle.
        private const val MAX_LOCK_MS = 5L * 60L * 60L * 1000L
    }
}
