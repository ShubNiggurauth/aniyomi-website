package eu.kanade.tachiyomi.animeextension.all.youtube

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Locale

/**
 * Resolves playable streams for a video through the InnerTube `player` endpoint.
 *
 * Clients are tried in order (by default ANDROID_VR, then IOS, then WEB) until one returns
 * streams that are actually reachable. Only formats with a direct `url` are used; formats that
 * carry a `signatureCipher` are skipped instead of being scraped with fragile regexes.
 */
class YouTubeExtractor(
    private val client: OkHttpClient,
    private val json: Json,
    private val innerTube: InnerTube,
) {

    data class Options(
        val clients: List<InnerTubeClient>,
        /** "avc1", "vp9" or "av01". */
        val preferredCodec: String,
        val includeAutoCaptions: Boolean,
    )

    fun parsePlayer(response: Response): PlayerResponse =
        response.use { json.decodeFromString(PlayerResponse.serializer(), it.body.string()) }

    fun fetchPlayer(videoId: String, innerTubeClient: InnerTubeClient): PlayerResponse {
        val response = client.newCall(innerTube.player(videoId, innerTubeClient)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }
        return parsePlayer(response)
    }

    /**
     * @param firstResponse the already-executed `player` response for `options.clients.first()`.
     */
    fun extractVideos(videoId: String, firstResponse: Response?, options: Options): List<Video> {
        val errors = mutableListOf<String>()
        for ((index, innerTubeClient) in options.clients.withIndex()) {
            val player = try {
                if (index == 0 && firstResponse != null) {
                    parsePlayer(firstResponse)
                } else {
                    fetchPlayer(videoId, innerTubeClient)
                }
            } catch (e: Exception) {
                errors += "${innerTubeClient.key}: ${e.message}"
                continue
            }

            val status = player.playabilityStatus
            if (status?.status != null && status.status != "OK") {
                errors += "${innerTubeClient.key}: ${status.reason ?: status.status}"
                continue
            }

            val videos = buildVideos(player, innerTubeClient, options)
            if (videos.isNotEmpty()) return videos
            errors += "${innerTubeClient.key}: no playable streams"
        }
        firstResponse?.close()
        throw Exception("Could not load YouTube streams (${errors.joinToString("; ")})")
    }

    private fun buildVideos(player: PlayerResponse, innerTubeClient: InnerTubeClient, options: Options): List<Video> {
        val data = player.streamingData ?: return emptyList()
        val headers = innerTube.mediaHeaders(innerTubeClient)
        val subtitles = subtitleTracks(player, options.includeAutoCaptions)
        val videos = mutableListOf<Video>()

        // ---- Adaptive (DASH) video-only streams + separate audio tracks ----
        val audio = audioTracks(data.adaptiveFormats)
        val adaptive = data.adaptiveFormats
            .filter { it.isVideo && it.url != null && it.qualityLabel != null }
            .groupBy { it.qualityLabel!! }
            .map { (_, formats) ->
                formats.sortedWith(
                    compareBy<YtFormat> { codecRank(codecFamily(it.codecs), options.preferredCodec) }
                        .thenByDescending { it.bitrate ?: 0L },
                ).first()
            }
            .sortedWith(compareByDescending<YtFormat> { it.height ?: 0 }.thenByDescending { it.fps ?: 0 })

        // Some clients hand out URLs that 403 without a PO token; probe one before trusting the set.
        val adaptiveUsable = audio.isNotEmpty() && adaptive.isNotEmpty() && isReachable(adaptive.first().url!!, headers)
        if (adaptiveUsable) {
            adaptive.forEach { format ->
                val url = format.url!!
                videos += Video(
                    url,
                    "${format.qualityLabel} • ${codecName(codecFamily(format.codecs))}",
                    url,
                    headers = headers,
                    subtitleTracks = subtitles,
                    audioTracks = audio,
                )
            }
        }

        // ---- Progressive (muxed audio+video) streams ----
        val muxed = data.formats.filter { it.url != null }
        if (muxed.isNotEmpty() && (adaptiveUsable || isReachable(muxed.first().url!!, headers))) {
            muxed.sortedByDescending { it.height ?: 0 }.forEach { format ->
                val url = format.url!!
                val label = format.qualityLabel ?: format.height?.let { "${it}p" } ?: "itag ${format.itag}"
                videos += Video(url, "$label • muxed", url, headers = headers, subtitleTracks = subtitles)
            }
        }

        // ---- Manifests (HLS from iOS clients and live streams; DASH as a last resort) ----
        data.hlsManifestUrl?.let { url ->
            videos += Video(url, "Auto (HLS)", url, headers = headers, subtitleTracks = subtitles)
        }
        if (videos.isEmpty()) {
            data.dashManifestUrl?.let { url ->
                videos += Video(url, "Auto (DASH)", url, headers = headers, subtitleTracks = subtitles)
            }
        }
        return videos
    }

    // ============================== Audio ==============================

    private fun audioTracks(formats: List<YtFormat>): List<Track> {
        val audio = formats.filter { it.isAudio && it.url != null }
        if (audio.isEmpty()) return emptyList()

        // Videos with dubbed audio expose one group per language; plain videos have a single group.
        val groups = audio.groupBy { it.audioTrack?.id.orEmpty() }.values.sortedWith(
            compareByDescending<List<YtFormat>> { group -> group.any { it.audioTrack?.audioIsDefault == true } }
                .thenByDescending { group -> group.any { it.audioTrack?.displayName?.contains("original", true) == true } }
                .thenBy { group -> group.first().audioTrack?.displayName.orEmpty() },
        )

        val tracks = mutableListOf<Track>()
        groups.forEachIndexed { index, group ->
            val ranked = group.sortedWith(
                compareBy<YtFormat> { it.isDrc == true }.thenByDescending { it.bitrate ?: 0L },
            )
            val best = ranked.first()
            tracks += best.toTrack()
            if (index == 0) {
                // Offer the best stream of the other codec too (Opus vs AAC) for the main language.
                val bestFamily = codecFamily(best.codecs)
                ranked.firstOrNull { codecFamily(it.codecs) != bestFamily && it.isDrc != true }?.let { tracks += it.toTrack() }
            }
        }
        return tracks
    }

    private fun YtFormat.toTrack(): Track {
        val kbps = (averageBitrate ?: bitrate)?.let { "${it / 1000}kbps" }
        val label = listOfNotNull(
            audioTrack?.displayName,
            listOfNotNull(codecName(codecFamily(codecs)), kbps).joinToString(" "),
            "DRC".takeIf { isDrc == true },
        ).joinToString(" • ")
        return Track(url!!, label)
    }

    // ============================== Subtitles ==============================

    private fun subtitleTracks(player: PlayerResponse, includeAuto: Boolean): List<Track> {
        val captions = player.captions?.playerCaptionsTracklistRenderer?.captionTracks.orEmpty()
        return captions
            .filter { includeAuto || it.kind != "asr" }
            .sortedBy { it.kind == "asr" } // manual captions first
            .mapNotNull { caption ->
                val base = caption.baseUrl.let { if (it.startsWith("/")) InnerTube.ORIGIN + it else it }
                val url = base.toHttpUrlOrNull()?.newBuilder()
                    ?.removeAllQueryParameters("fmt")
                    ?.addQueryParameter("fmt", "vtt")
                    ?.build()?.toString() ?: return@mapNotNull null
                val name = caption.name?.text ?: caption.languageCode ?: "Unknown"
                val label = if (caption.kind == "asr" && !name.contains("auto", true)) "$name (auto-generated)" else name
                Track(url, label)
            }
    }

    // ============================== Helpers ==============================

    private fun isReachable(url: String, headers: Headers): Boolean = try {
        val request = Request.Builder().url(url).headers(headers).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    companion object {
        private val CODEC_FALLBACK = listOf("avc1", "vp9", "av01")

        fun codecFamily(codecs: String): String {
            val c = codecs.lowercase(Locale.ROOT)
            return when {
                c.startsWith("avc1") -> "avc1"
                c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
                c.startsWith("av01") -> "av01"
                c.startsWith("mp4a") -> "mp4a"
                c.startsWith("opus") -> "opus"
                else -> c.substringBefore('.')
            }
        }

        fun codecName(family: String) = when (family) {
            "avc1" -> "H.264"
            "vp9" -> "VP9"
            "av01" -> "AV1"
            "mp4a" -> "AAC"
            "opus" -> "Opus"
            else -> family.uppercase(Locale.ROOT)
        }

        private fun codecRank(family: String, preferred: String): Int = when (family) {
            preferred -> 0
            else -> CODEC_FALLBACK.indexOf(family).let { if (it < 0) CODEC_FALLBACK.size + 1 else it + 1 }
        }
    }
}
