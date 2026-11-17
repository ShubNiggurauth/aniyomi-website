package eu.kanade.tachiyomi.animeextension.all.youtube

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.util.concurrent.ConcurrentHashMap

class YouTube : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "YouTube"

    override val baseUrl = "https://www.youtube.com"

    override val lang = "all"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }

    private val innerTube by lazy { InnerTube(json) }

    private val parser by lazy { YouTubeParser(json) }

    private val extractor by lazy { YouTubeExtractor(client, json, innerTube) }

    private val preferences: SharedPreferences by lazy { getSourcePreferences() }

    /** Search continuation tokens, keyed by "<query+params>#<page>". */
    private val continuations = ConcurrentHashMap<String, String>()

    /** Request tags, used to route responses to the right parser. */
    private data class SearchTag(val key: String, val page: Int)
    private data class RefTag(val ref: YtRef)
    private data class VideoTag(val videoId: String)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        searchRequest(browseQuery, YouTubeFilters.Selection(type = browseType), page)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchResponse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchRequest(
        browseQuery,
        YouTubeFilters.Selection(type = browseType, sort = YouTubeFilters.SortBy.UPLOAD_DATE),
        page,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = parseSearchResponse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmed = query.trim()
        // A pasted link / id opens that video, playlist or channel directly.
        if (page <= 1) YtRef.parse(trimmed)?.let { return lookupRequest(it) }
        return searchRequest(trimmed.ifEmpty { browseQuery }, YouTubeFilters.parse(filters), page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchResponse(response)

    override fun getFilterList(): AnimeFilterList = YouTubeFilters.getFilterList()

    private fun searchRequest(query: String, selection: YouTubeFilters.Selection, page: Int): Request {
        val params = YouTubeFilters.encode(selection)
        val key = "$query\u0000${params.orEmpty()}"
        if (page <= 1) return innerTube.search(query, params, SearchTag(key, 1))
        val token = continuations["$key#$page"] ?: throw Exception("No more results")
        return innerTube.searchContinuation(token, SearchTag(key, page))
    }

    private fun parseSearchResponse(response: Response): AnimesPage {
        when (val tag = response.request.tag(Any::class.java)) {
            is RefTag -> return AnimesPage(listOf(parseDetails(response, tag.ref)), false)
            else -> {
                val (animes, token) = parser.parseSearch(response.toJson())
                if (tag is SearchTag && token != null) {
                    if (continuations.size > MAX_CACHED_CONTINUATIONS) continuations.clear()
                    continuations["${tag.key}#${tag.page + 1}"] = token
                }
                return AnimesPage(animes, token != null && animes.isNotEmpty())
            }
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = lookupRequest(anime.ref())

    override fun animeDetailsParse(response: Response): SAnime = parseDetails(response, response.refTag())

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    /** One request whose response identifies [ref]: `player` for videos, `browse`/`resolve_url` otherwise. */
    private fun lookupRequest(ref: YtRef): Request {
        val tag = RefTag(ref)
        return when (ref) {
            is YtRef.Video -> innerTube.player(ref.id, InnerTubeClient.WEB, tag)
            is YtRef.Playlist -> innerTube.browse("VL${ref.id}", tag)
            is YtRef.Channel -> innerTube.browse(ref.id, tag)
            is YtRef.ChannelPath -> innerTube.resolveUrl(ref.path, tag)
        }
    }

    private fun parseDetails(response: Response, ref: YtRef): SAnime = when (ref) {
        is YtRef.Video -> parser.parseVideoDetails(videoPlayer(response, ref.id), ref)
        is YtRef.Playlist -> parser.parsePlaylistDetails(response.toJson(), ref)
        is YtRef.Channel -> parser.parseChannelDetails(response.toJson(), ref.id)
        is YtRef.ChannelPath -> {
            val channel = resolveChannel(response)
            val root = executeJson(innerTube.browse(channel.id)) ?: throw Exception("Channel not found")
            parser.parseChannelDetails(root, channel.id)
        }
    }

    /** Player metadata; falls back to a mobile client when WEB withholds `videoDetails`. */
    private fun videoPlayer(response: Response, videoId: String): PlayerResponse {
        val web = runCatching { extractor.parsePlayer(response) }.getOrNull()
        if (web?.videoDetails != null) return web
        val mobile = runCatching { extractor.fetchPlayer(videoId, InnerTubeClient.ANDROID_VR) }.getOrNull()
        return mobile?.copy(microformat = mobile.microformat ?: web?.microformat)
            ?: web ?: throw Exception("Video unavailable")
    }

    private fun resolveChannel(response: Response): YtRef.Channel {
        val browseId = parser.resolvedBrowseId(response.toJson())
            ?.takeIf { it.startsWith("UC") }
            ?: throw Exception("Could not resolve channel")
        return YtRef.Channel(browseId)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = lookupRequest(anime.ref())

    override fun episodeListParse(response: Response): List<SEpisode> =
        when (val ref = response.refTag()) {
            is YtRef.Video -> {
                val player = videoPlayer(response, ref.id)
                val details = player.videoDetails
                val micro = player.microformat?.playerMicroformatRenderer
                listOf(
                    SEpisode.create().apply {
                        url = ref.path
                        name = details?.title ?: "Video"
                        episode_number = 1f
                        date_upload = YouTubeParser.parseIsoDate(micro?.publishDate ?: micro?.uploadDate)
                        scanlator = details?.lengthSeconds?.toLongOrNull()
                            ?.takeIf { it > 0 }?.let(YouTubeParser::formatDuration)
                    },
                )
            }
            is YtRef.Playlist -> {
                val items = loadPlaylist(ref.id, response.toJson())
                // Playlist order: first item is episode 1; newest-numbered first, as the app expects.
                items.mapIndexed { index, item -> item.toEpisode(index + 1) }.reversed()
            }
            is YtRef.Channel -> {
                response.close()
                channelEpisodes(ref)
            }
            is YtRef.ChannelPath -> channelEpisodes(resolveChannel(response))
        }

    /** Every upload of a channel, via its `UU...` uploads playlist (`UULF...` to leave out Shorts). */
    private fun channelEpisodes(channel: YtRef.Channel): List<SEpisode> {
        var items = if (excludeShorts) loadPlaylist(channel.uploadsPlaylist(longFormOnly = true)) else emptyList()
        if (items.isEmpty()) items = loadPlaylist(channel.uploadsPlaylist(longFormOnly = false))
        // Uploads are newest first; number them chronologically so the oldest upload is episode 1.
        val total = items.size
        return items.mapIndexed { index, item -> item.toEpisode(total - index) }
    }

    /** Loads all items of a playlist, following continuation tokens. */
    private fun loadPlaylist(playlistId: String, firstPage: JsonElement? = null): List<PlaylistItem> {
        var root = firstPage ?: executeJson(innerTube.browse("VL$playlistId")) ?: return emptyList()
        val all = mutableListOf<PlaylistItem>()
        var pages = 0
        while (true) {
            val (items, token) = parser.parsePlaylistPage(root)
            all += items
            pages++
            if (token == null || items.isEmpty() || pages >= MAX_PLAYLIST_PAGES) break
            root = executeJson(innerTube.browseContinuation(token)) ?: break
        }
        return all.distinctBy { it.videoId }
    }

    private fun PlaylistItem.toEpisode(number: Int) = SEpisode.create().apply {
        url = YtRef.Video(videoId).path
        name = title
        episode_number = number.toFloat()
        date_upload = YouTubeParser.parseRelativeDate(published)
        scanlator = duration
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        withContext(Dispatchers.IO) { loadVideos(episode) }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getVideoList"))
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.fromCallable { loadVideos(episode) }

    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.videoId()
        return innerTube.player(id, extractorOptions().clients.first(), VideoTag(id))
    }

    override fun videoListParse(response: Response): List<Video> {
        val tag = response.request.tag(Any::class.java) as? VideoTag
            ?: throw Exception("Unexpected response")
        return extractor.extractVideos(tag.videoId, response, extractorOptions()).sort()
    }

    /** Runs the whole client-fallback chain, so an HTTP error from one client does not abort playback. */
    private fun loadVideos(episode: SEpisode): List<Video> =
        extractor.extractVideos(episode.videoId(), null, extractorOptions()).sort()

    private fun extractorOptions() = YouTubeExtractor.Options(
        clients = InnerTubeClient.playbackOrder(preferences.getString(PREF_CLIENT_KEY, PREF_CLIENT_DEFAULT)!!),
        preferredCodec = preferences.getString(PREF_CODEC_KEY, PREF_CODEC_DEFAULT)!!,
        includeAutoCaptions = preferences.getBoolean(PREF_AUTO_CAPTIONS_KEY, PREF_AUTO_CAPTIONS_DEFAULT),
    )

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val preferredHeight = preferred.filter(Char::isDigit).toIntOrNull() ?: 1080
        fun height(v: Video) = QUALITY_REGEX.find(v.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        fun fps(v: Video) = QUALITY_REGEX.find(v.quality)?.groupValues?.get(2)?.toIntOrNull() ?: 30
        return sortedWith(
            compareByDescending<Video> { height(it) == preferredHeight }
                .thenByDescending { height(it) in 1..preferredHeight }
                .thenByDescending { !it.quality.contains("muxed") && !it.quality.startsWith("Auto") }
                .thenByDescending { height(it) }
                .thenByDescending { fps(it) },
        )
    }

    // ============================= Utilities ==============================

    private fun SAnime.ref(): YtRef = YtRef.parse(url) ?: throw Exception("Unsupported YouTube URL: $url")

    private fun SEpisode.videoId(): String =
        (YtRef.parse(url) as? YtRef.Video)?.id ?: throw Exception("Unsupported YouTube URL: $url")

    private fun Response.refTag(): YtRef =
        (request.tag(Any::class.java) as? RefTag)?.ref ?: throw Exception("Unexpected response")

    private fun Response.toJson(): JsonElement = use { json.parseToJsonElement(it.body.string()) }

    private fun executeJson(request: Request): JsonElement? =
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) json.parseToJsonElement(response.body.string()) else null
        }

    private val browseQuery: String
        get() = preferences.getString(PREF_BROWSE_QUERY_KEY, PREF_BROWSE_QUERY_DEFAULT)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: PREF_BROWSE_QUERY_DEFAULT

    private val browseType: YouTubeFilters.SearchType
        get() = YouTubeFilters.SearchType.fromName(preferences.getString(PREF_BROWSE_TYPE_KEY, PREF_BROWSE_TYPE_DEFAULT))

    private val excludeShorts: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_SHORTS_KEY, PREF_EXCLUDE_SHORTS_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CODEC_KEY
            title = "Preferred video codec"
            entries = arrayOf("H.264 (AVC, best compatibility, max 1080p)", "VP9", "AV1 (needs a recent device)")
            entryValues = arrayOf("avc1", "vp9", "av01")
            setDefaultValue(PREF_CODEC_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BROWSE_QUERY_KEY
            title = "Default browse query / home feed"
            dialogTitle = "Search query used by the Popular and Latest tabs"
            setDefaultValue(PREF_BROWSE_QUERY_DEFAULT)
            summary = browseQuery
            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: PREF_BROWSE_QUERY_DEFAULT
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_BROWSE_TYPE_KEY
            title = "Popular / Latest content type"
            entries = YouTubeFilters.SearchType.values().map { it.label }.toTypedArray()
            entryValues = YouTubeFilters.SearchType.values().map { it.name }.toTypedArray()
            setDefaultValue(PREF_BROWSE_TYPE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CLIENT_KEY
            title = "InnerTube client override"
            entries = arrayOf("Auto (ANDROID_VR + IOS fallback)") +
                InnerTubeClient.values().map { it.displayName }.toTypedArray()
            entryValues = arrayOf(PREF_CLIENT_DEFAULT) + InnerTubeClient.values().map { it.key }.toTypedArray()
            setDefaultValue(PREF_CLIENT_DEFAULT)
            summary = "%s\nThe chosen client is tried first; the others are kept as a fallback."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EXCLUDE_SHORTS_KEY
            title = "Leave Shorts out of channel episode lists"
            setDefaultValue(PREF_EXCLUDE_SHORTS_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_CAPTIONS_KEY
            title = "Include auto-generated captions"
            setDefaultValue(PREF_AUTO_CAPTIONS_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val MAX_PLAYLIST_PAGES = 50 // 100 items per page
        private const val MAX_CACHED_CONTINUATIONS = 500
        private val QUALITY_REGEX = Regex("""^(\d{3,4})p(\d{2})?""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_CODEC_KEY = "preferred_codec"
        private const val PREF_CODEC_DEFAULT = "avc1"

        private const val PREF_BROWSE_QUERY_KEY = "browse_query"
        private const val PREF_BROWSE_QUERY_DEFAULT = "anime full episodes"

        private const val PREF_BROWSE_TYPE_KEY = "browse_type"
        private const val PREF_BROWSE_TYPE_DEFAULT = "PLAYLIST"

        private const val PREF_CLIENT_KEY = "innertube_client"
        private const val PREF_CLIENT_DEFAULT = "AUTO"

        private const val PREF_EXCLUDE_SHORTS_KEY = "exclude_shorts"
        private const val PREF_EXCLUDE_SHORTS_DEFAULT = true

        private const val PREF_AUTO_CAPTIONS_KEY = "auto_captions"
        private const val PREF_AUTO_CAPTIONS_DEFAULT = true
    }
}
