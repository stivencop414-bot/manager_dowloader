package com.managerdownloader.app.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YouTubePlaylistItem(
    val canonicalUrl: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val position: Int
)

data class YouTubePlaylistDetails(
    val sourceUrl: String,
    val title: String,
    val author: String,
    val items: List<YouTubePlaylistItem>,
    val truncated: Boolean
)

object YouTubePlaylistExtractor {
    private const val MAX_ITEMS = 200

    suspend fun analyze(url: String): Result<YouTubePlaylistDetails> = withContext(Dispatchers.IO) {
        runCatching {
            YouTubeExtractorClient.ensureInitialized()
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val gathered = mutableListOf<StreamInfoItem>()
            gathered += info.relatedItems.filterIsInstance<StreamInfoItem>()
            var nextPage = info.nextPage

            while (nextPage != null && gathered.size < MAX_ITEMS) {
                val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                gathered += page.items.filterIsInstance<StreamInfoItem>()
                nextPage = page.nextPage
            }

            val items = gathered.take(MAX_ITEMS).mapIndexedNotNull { index, item ->
                val canonical = YouTubeUrlParser.parse(item.url)?.canonicalUrl ?: item.url.takeIf { it.startsWith("http") }
                canonical?.let {
                    YouTubePlaylistItem(
                        canonicalUrl = it,
                        title = item.name.ifBlank { "Video ${index + 1}" },
                        uploader = item.uploaderName.orEmpty(),
                        durationSeconds = item.duration.coerceAtLeast(0L),
                        position = index + 1
                    )
                }
            }

            YouTubePlaylistDetails(
                sourceUrl = url,
                title = info.name.ifBlank { "Playlist de YouTube" },
                author = info.uploaderName.orEmpty(),
                items = items,
                truncated = nextPage != null || gathered.size > MAX_ITEMS
            )
        }
    }
}
