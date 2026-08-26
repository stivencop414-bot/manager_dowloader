package com.managerdownloader.app.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Foreground-only clipboard helper. Android 10+ restricts background clipboard reads, so callers
 * should invoke [checkClipboard] only while the app owns focus (onResume/onWindowFocusChanged).
 */
class ClipboardMonitor(
    context: Context,
    private val onUrlDetected: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var lastHash: String? = null
    private val dismissed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
    }

    @Synchronized
    fun checkClipboard() {
        runCatching {
            val description = clipboard.primaryClipDescription ?: return
            val textMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
            if (!textMime) return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val text = clip.getItemAt(0).coerceToText(appContext)?.toString()?.trim().orEmpty()
            if (!isSupportedUrl(text)) return
            val hash = hash(text)
            if (hash == lastHash || dismissed.containsKey(hash)) return
            lastHash = hash
            onUrlDetected(text)
        }
    }

    @Synchronized
    fun dismiss(url: String) {
        dismissed[hash(url)] = System.currentTimeMillis()
    }

    private fun isSupportedUrl(value: String): Boolean =
        value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("magnet:", true)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
