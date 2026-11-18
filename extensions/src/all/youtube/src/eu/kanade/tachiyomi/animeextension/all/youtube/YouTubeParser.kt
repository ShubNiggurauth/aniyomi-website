package eu.kanade.tachiyomi.animeextension.all.youtube

import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What an `SAnime.url` / pasted link points at. */
sealed class YtRef {
    abstract val path: String

    data class Video(val id: String) : YtRef() {
        override val path get() = "/watch?v=$id"
    }

    data class Playlist(val id: String) : YtRef() {
        override val path get() = "/playlist?list=$id"
    }

    /** Channel addressed by its canonical `UC...` id. */
    data class Channel(val id: String) : YtRef() {
        override val path get() = "/channel/$id"

        /** Uploads playlist; the `UULF` variant leaves out Shorts. */
        fun uploadsPlaylist(longFormOnly: Boolean) = (if (longFormOnly) "UULF" else "UU") + id.removePrefix("UC")
    }

    /** Channel addressed by handle or legacy path (`/@name`, `/c/name`, `/user/name`); needs resolving. */
    data class ChannelPath(override val path: String) : YtRef()

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val PLAYLIST_ID = Regex("^(PL|OLAK5uy_|UU|FL|LL)[A-Za-z0-9_-]{16,}$")

        /** Parses a stored path (`/watch?v=..`) or any YouTube link / raw id. */
        fun parse(input: String): YtRef? {
            val text = input.trim()
            if (text.isEmpty()) return null
            if (CHANNEL_ID.matches(text)) return Channel(text)
            if (PLAYLIST_ID.matches(text)) return Playlist(text)
            if (text.startsWith("@") && !text.contains(' ')) return ChannelPath("/$text")

            val absolute = when {
                text.startsWith("/") -> "https://www.youtube.com$text"
                text.startsWith("http://") || text.startsWith("https://") -> text
                text.contains("youtube.com/") || text.contains("youtu.be/") -> "https://$text"
                else -> return null
            }
            val url = absolute.toHttpUrlOrNull() ?: return null
            val host = url.host.removePrefix("www.").removePrefix("m.").removePrefix("music.")
            val segments = url.pathSegments.filter { it.isNotEmpty() }

            if (host == "youtu.be") {
                return segments.firstOrNull()?.takeIf(VIDEO_ID::matches)?.let(::Video)
            }
            if (host != "youtube.com" && host != "youtube-nocookie.com") return null

            val list = url.queryParameter("list")?.takeIf { it.isNotBlank() && !it.startsWith("RD") }
            val first = segments.firstOrNull() ?: return null
            return when {
                first == "watch" -> {
                    // A watch link inside a playlist opens the whole playlist.
                    list?.let(::Playlist) ?: url.queryParameter("v")?.takeIf(VIDEO_ID::matches)?.let(::Video)
                }
                first == "playlist" -> list?.let(::Playlist)
                first in setOf("shorts", "live", "embed", "v") ->
                    segments.getOrNull(1)?.takeIf(VIDEO_ID::matches)?.let(::Video)
                first == "channel" -> segments.getOrNull(1)?.takeIf(CHANNEL_ID::matches)?.let(::Channel)
                first.startsWith("@") -> ChannelPath("/$first")
                first == "c" || first == "user" -> segments.getOrNull(1)?.let { ChannelPath("/$first/$it") }
                else -> null
            }
        }
    }
}

/** One entry of a playlist / uploads list. */
data class PlaylistItem(
    val videoId: String,
    val title: String,
    val duration: String?,
    val published: String?,
)

class YouTubeParser(private val json: Json) {

    // ============================== Search ==============================

    /** Parses a search (or search continuation) response into entries plus the next-page token. */
    fun parseSearch(root: JsonElement): Pair<List<SAnime>, String?> {
        val results = LinkedHashMap<String, SAnime>()
        var continuation: String? = null

        root.walk { key, value ->
            when (key) {
                in SKIPPED_RENDERERS -> true
                "videoRenderer" -> {
                    decode<VideoRenderer>(value)?.let { results.addIfAbsent(YtRef.Video(it.videoId).path, it.toSAnime()) }
                    true
                }
                "channelRenderer" -> {
                    decode<ChannelRenderer>(value)?.let { results.addIfAbsent(YtRef.Channel(it.channelId).path, it.toSAnime()) }
                    true
                }
                "playlistRenderer" -> {
                    decode<PlaylistRenderer>(value)?.let { results.addIfAbsent(YtRef.Playlist(it.playlistId).path, it.toSAnime()) }
                    true
                }
                "lockupViewModel" -> {
                    lockupToSAnime(value)?.let { results.addIfAbsent(it.url, it) }
                    true
                }
                "continuationItemRenderer" -> {
                    if (continuation == null) continuation = continuationToken(value)
                    true
                }
                else -> false
            }
        }
        return results.values.toList() to continuation
    }

    private fun VideoRenderer.toSAnime() = SAnime.create().apply {
        url = YtRef.Video(videoId).path
        title = this@toSAnime.title?.text ?: "Untitled video"
        thumbnail_url = videoThumbnail(videoId)
        author = channel
        genre = "Video"
        status = SAnime.COMPLETED
        update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        description = buildString {
            snippet?.let { append(it).append("\n\n") }
            append(listOfNotNull(lengthText?.text, viewCountText?.text, publishedTimeText?.text).joinToString(" • "))
        }.trim()
    }

    private fun ChannelRenderer.toSAnime() = SAnime.create().apply {
        url = YtRef.Channel(channelId).path
        title = this@toSAnime.title?.text ?: channelId
        thumbnail_url = thumbnail?.best
        author = title
        genre = "Channel"
        status = SAnime.ONGOING
        // Since 2024 these two fields hold "@handle" and "N subscribers"; classify by content.
        val stats = listOfNotNull(subscriberCountText?.text, videoCountText?.text)
        description = buildString {
            descriptionSnippet?.text?.let { append(it).append("\n\n") }
            append(stats.joinToString(" • "))
        }.trim()
    }

    private fun PlaylistRenderer.toSAnime() = SAnime.create().apply {
        url = YtRef.Playlist(playlistId).path
        title = this@toSAnime.title?.text ?: "Playlist"
        thumbnail_url = thumbnails?.firstOrNull()?.best ?: thumbnail?.best
        author = shortBylineText?.text ?: longBylineText?.text
        genre = "Playlist"
        status = SAnime.UNKNOWN
        val count = videoCountText?.text ?: videoCount?.let { "$it videos" }
        description = count.orEmpty()
    }

    /** New-style `lockupViewModel` (playlists, and some videos). */
    private fun lockupToSAnime(lockup: JsonObject): SAnime? {
        val id = lockup["contentId"].string ?: return null
        val type = lockup["contentType"].string.orEmpty()
        val meta = lockup["metadata"]["lockupMetadataViewModel"]
        val title = meta["title"]["content"].string ?: return null
        val parts = meta["metadata"]?.collectStrings("content").orEmpty()
        val badges = lockup["contentImage"]?.collectStrings("text").orEmpty()
        val thumb = lockup["contentImage"]?.findFirst("image")?.let { image ->
            image["sources"].array.lastOrNull()?.get("url").string?.normalizeUrl()
        }

        return when {
            type == "LOCKUP_CONTENT_TYPE_VIDEO" -> SAnime.create().apply {
                url = YtRef.Video(id).path
                this.title = title
                thumbnail_url = videoThumbnail(id)
                author = parts.firstOrNull()
                genre = "Video"
                status = SAnime.COMPLETED
                update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                description = (badges + parts.drop(1)).joinToString(" • ")
            }
            id.startsWith("RD") -> null // auto-generated mixes cannot be browsed as playlists
            type.startsWith("LOCKUP_CONTENT_TYPE_") -> SAnime.create().apply {
                url = YtRef.Playlist(id).path
                this.title = title
                thumbnail_url = thumb
                author = parts.firstOrNull { !it.contains("playlist", ignoreCase = true) }
                genre = "Playlist"
                status = SAnime.UNKNOWN
                description = badges.filter { COUNT_REGEX.containsMatchIn(it) }.joinToString(" • ")
            }
            else -> null
        }
    }

    // ============================== Playlists ==============================

    /** Parses a playlist browse (or continuation) response into its items plus the next-page token. */
    fun parsePlaylistPage(root: JsonElement): Pair<List<PlaylistItem>, String?> {
        val items = mutableListOf<PlaylistItem>()
        var continuation: String? = null
        root.walk { key, value ->
            when (key) {
                in SKIPPED_RENDERERS -> true
                "playlistVideoRenderer" -> {
                    decode<PlaylistVideoRenderer>(value)
                        ?.takeIf { it.isPlayable != false }
                        ?.let { r ->
                            val info = r.videoInfo?.runs?.mapNotNull { it.text?.trim() }
                                ?.filter { it.isNotEmpty() && it != "•" }.orEmpty()
                            items += PlaylistItem(
                                videoId = r.videoId,
                                title = r.title?.text ?: "Video",
                                duration = r.lengthText?.text ?: r.lengthSeconds?.toLongOrNull()?.let(::formatDuration),
                                published = info.firstOrNull { RELATIVE_DATE.containsMatchIn(it) },
                            )
                        }
                    true
                }
                "lockupViewModel" -> {
                    if (value["contentType"].string == "LOCKUP_CONTENT_TYPE_VIDEO") {
                        val id = value["contentId"].string
                        val meta = value["metadata"]["lockupMetadataViewModel"]
                        val title = meta["title"]["content"].string
                        if (id != null && title != null) {
                            val parts = meta["metadata"]?.collectStrings("content").orEmpty()
                            val badges = value["contentImage"]?.collectStrings("text").orEmpty()
                            items += PlaylistItem(
                                videoId = id,
                                title = title,
                                duration = badges.firstOrNull { DURATION_REGEX.matches(it) },
                                published = parts.firstOrNull { RELATIVE_DATE.containsMatchIn(it) },
                            )
                        }
                    }
                    true
                }
                "continuationItemRenderer" -> {
                    if (continuation == null) continuation = continuationToken(value)
                    true
                }
                else -> false
            }
        }
        return items.distinctBy { it.videoId } to continuation
    }

    /** Playlist details from a `browse VL...` response. */
    fun parsePlaylistDetails(root: JsonElement, ref: YtRef.Playlist) = SAnime.create().apply {
        url = ref.path
        val micro = root.findFirst("microformatDataRenderer")?.let { decode<MicroformatDataRenderer>(it) }
        val header = root["header"] ?: JsonObject(emptyMap())
        val headerStrings = header.collectStrings("content") + header.collectStrings("text") +
            header.collectStrings("simpleText")

        title = micro?.title?.removeSuffix(" - YouTube")
            ?: header.findFirst("pageHeaderViewModel")?.get("title")?.collectStrings("content")?.firstOrNull()
            ?: "Playlist"
        thumbnail_url = micro?.thumbnail?.best
            ?: header.findFirst("image")?.get("sources")?.array?.lastOrNull()?.get("url").string?.normalizeUrl()
        author = (root.findFirst("videoOwnerRenderer")?.get("title")?.let { decode<YtText>(it) }?.text)
            ?: header.findFirst("playlistHeaderRenderer")?.get("ownerText")?.let { decode<YtText>(it) }?.text
            ?: headerStrings.firstOrNull { it.startsWith("by ") }?.removePrefix("by ")
        genre = "Playlist"
        status = SAnime.UNKNOWN
        val counts = headerStrings.filter { COUNT_REGEX.containsMatchIn(it) && it.length < 40 }.distinct()
        description = listOfNotNull(
            micro?.description?.takeIf { it.isNotBlank() },
            counts.joinToString(" • ").takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
    }

    // ============================== Channels ==============================

    /** Channel details from a `browse UC...` response. */
    fun parseChannelDetails(root: JsonElement, fallbackId: String?) = SAnime.create().apply {
        val meta = root.findFirst("channelMetadataRenderer")?.let { decode<ChannelMetadataRenderer>(it) }
        val channelId = meta?.externalId ?: fallbackId
        url = channelId?.let { YtRef.Channel(it).path } ?: meta?.vanityChannelUrl.orEmpty()
        title = meta?.title ?: "Channel"
        author = meta?.title
        thumbnail_url = meta?.avatar?.best
        genre = "Channel"
        status = SAnime.ONGOING
        val header = root["header"] ?: JsonObject(emptyMap())
        val stats = (header.collectStrings("content") + header.collectStrings("simpleText") + header.collectStrings("text"))
            .filter { it.length < 40 && (it.startsWith("@") || COUNT_REGEX.containsMatchIn(it)) }
            .distinct()
        description = listOfNotNull(
            meta?.description?.takeIf { it.isNotBlank() },
            stats.joinToString(" • ").takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
    }

    fun channelIdFrom(root: JsonElement): String? =
        root.findFirst("channelMetadataRenderer")?.get("externalId").string

    fun resolvedBrowseId(root: JsonElement): String? =
        decode<ResolveUrlResponse>(root)?.endpoint?.browseEndpoint?.browseId

    // ============================== Videos ==============================

    fun parseVideoDetails(player: PlayerResponse, ref: YtRef.Video) = SAnime.create().apply {
        val details = player.videoDetails
        val micro = player.microformat?.playerMicroformatRenderer
        url = ref.path
        title = details?.title ?: "Video"
        author = details?.author
        thumbnail_url = videoThumbnail(ref.id)
        genre = listOfNotNull("Video", micro?.category).joinToString()
        status = SAnime.COMPLETED
        update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
        val stats = listOfNotNull(
            details?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0 }?.let { "Duration: ${formatDuration(it)}" },
            details?.viewCount?.toLongOrNull()?.let { "Views: %,d".format(Locale.US, it) },
            (micro?.publishDate ?: micro?.uploadDate)?.take(10)?.let { "Published: $it" },
        )
        description = listOfNotNull(
            details?.shortDescription?.takeIf { it.isNotBlank() },
            stats.joinToString(" • ").takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
    }

    // ============================== Helpers ==============================

    /** `putIfAbsent` without the API 24 requirement. */
    private fun <K, V> MutableMap<K, V>.addIfAbsent(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }

    private fun continuationToken(renderer: JsonObject): String? =
        decode<ContinuationItemRenderer>(renderer)?.continuationEndpoint?.continuationCommand?.token
            ?: renderer.findFirst("continuationCommand")?.get("token").string

    private inline fun <reified T> decode(element: JsonElement): T? =
        runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()

    companion object {
        /** Renderers whose subtrees hold Shorts, ads or paid content, which cannot be mapped. */
        private val SKIPPED_RENDERERS = setOf(
            "reelShelfRenderer",
            "shortsLockupViewModel",
            "reelItemRenderer",
            "adSlotRenderer",
            "promotedSparklesWebRenderer",
            "movieRenderer",
            "radioRenderer",
        )

        private val COUNT_REGEX = Regex("""\d.*\b(videos?|episodes?|lessons?|subscribers?|views?)\b""", RegexOption.IGNORE_CASE)
        private val DURATION_REGEX = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
        private val RELATIVE_DATE = Regex(
            """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
            RegexOption.IGNORE_CASE,
        )

        fun videoThumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
        }

        /** Converts "3 weeks ago" / "Streamed 2 years ago" into an approximate epoch millis (0 if unknown). */
        fun parseRelativeDate(text: String?): Long {
            val match = text?.let { RELATIVE_DATE.find(it) } ?: return 0L
            val amount = match.groupValues[1].toIntOrNull() ?: return 0L
            val field = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "second" -> Calendar.SECOND
                "minute" -> Calendar.MINUTE
                "hour" -> Calendar.HOUR_OF_DAY
                "day" -> Calendar.DAY_OF_YEAR
                "week" -> Calendar.WEEK_OF_YEAR
                "month" -> Calendar.MONTH
                else -> Calendar.YEAR
            }
            return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
        }

        /** Parses "2021-03-04" or an ISO-8601 timestamp's date part (0 if unknown). */
        fun parseIsoDate(text: String?): Long {
            val date = text?.take(10) ?: return 0L
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(date)?.time
            }.getOrNull() ?: 0L
        }
    }
}
