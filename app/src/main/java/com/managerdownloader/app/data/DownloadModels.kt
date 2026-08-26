package com.managerdownloader.app.data

enum class DownloadStatus {
    QUEUED,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class DownloadKind {
    HTTP,
    TORRENT,
    YOUTUBE_JIT,
    YOUTUBE_MUXED
}

data class DownloadTask(
    val id: String,
    val filename: String,
    val url: String,
    val kind: DownloadKind = DownloadKind.HTTP,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSecond: Long = 0L,
    val order: Int = 0,
    val outputPath: String? = null,
    val error: String? = null,
    val detail: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val expectedSha256: String? = null,
    val actualSha256: String? = null,
    /** Original page URL used to refresh short-lived extracted stream URLs (for example YouTube). */
    val originalSourceUrl: String? = null,
    /** Extractor-specific primary format id/itag used to refresh an expired stream URL. */
    val sourceFormatId: String? = null,
    /** Optional second stream used by native video+audio mux jobs. */
    val secondaryUrl: String? = null,
    val secondarySourceFormatId: String? = null,
    /** mp4 or webm for native MediaMuxer jobs. */
    val muxContainer: String? = null,
    /** Logical JIT profile used for playlist/channel queue entries. */
    val sourceProfile: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) {
            (bytesDownloaded.toDouble() / totalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        } else {
            0f
        }
}
