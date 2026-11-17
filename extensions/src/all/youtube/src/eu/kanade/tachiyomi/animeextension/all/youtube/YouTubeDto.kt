package eu.kanade.tachiyomi.animeextension.all.youtube

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ============================== Request ==============================

/** `context.client` of an InnerTube request. Null fields are omitted from the JSON body. */
@Serializable
data class ClientInfo(
    val clientName: String,
    val clientVersion: String,
    val hl: String = "en",
    val gl: String = "US",
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val platform: String? = null,
    val timeZone: String = "UTC",
    val utcOffsetMinutes: Int = 0,
)

// ============================== Shared ==============================

/** YouTube's text container: either `simpleText`, a list of `runs`, or a view-model `content`. */
@Serializable
data class YtText(
    val simpleText: String? = null,
    val runs: List<YtRun>? = null,
    val content: String? = null,
) {
    val text: String?
        get() = (simpleText ?: runs?.joinToString("") { it.text.orEmpty() } ?: content)
            ?.takeIf { it.isNotBlank() }
}

@Serializable
data class YtRun(
    val text: String? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
)

@Serializable
data class NavigationEndpoint(
    val browseEndpoint: BrowseEndpoint? = null,
    val watchEndpoint: WatchEndpoint? = null,
)

@Serializable
data class BrowseEndpoint(
    val browseId: String? = null,
    val canonicalBaseUrl: String? = null,
)

@Serializable
data class WatchEndpoint(
    val videoId: String? = null,
    val playlistId: String? = null,
)

@Serializable
data class Thumbnails(val thumbnails: List<Thumbnail> = emptyList()) {
    /** URL of the largest thumbnail, normalised to https. */
    val best: String?
        get() = thumbnails.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }?.url?.normalizeUrl()
}

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

// ============================== Search / browse renderers ==============================

@Serializable
data class VideoRenderer(
    val videoId: String,
    val title: YtText? = null,
    val ownerText: YtText? = null,
    val shortBylineText: YtText? = null,
    val longBylineText: YtText? = null,
    val lengthText: YtText? = null,
    val viewCountText: YtText? = null,
    val publishedTimeText: YtText? = null,
    val descriptionSnippet: YtText? = null,
    val detailedMetadataSnippets: List<MetadataSnippet>? = null,
) {
    val channel: String?
        get() = ownerText?.text ?: shortBylineText?.text ?: longBylineText?.text
    val snippet: String?
        get() = detailedMetadataSnippets?.firstNotNullOfOrNull { it.snippetText?.text } ?: descriptionSnippet?.text
}

@Serializable
data class MetadataSnippet(val snippetText: YtText? = null)

@Serializable
data class ChannelRenderer(
    val channelId: String,
    val title: YtText? = null,
    val thumbnail: Thumbnails? = null,
    val descriptionSnippet: YtText? = null,
    val subscriberCountText: YtText? = null,
    val videoCountText: YtText? = null,
)

@Serializable
data class PlaylistRenderer(
    val playlistId: String,
    val title: YtText? = null,
    val thumbnails: List<Thumbnails>? = null,
    val thumbnail: Thumbnails? = null,
    val videoCount: String? = null,
    val videoCountText: YtText? = null,
    val videoCountShortText: YtText? = null,
    val shortBylineText: YtText? = null,
    val longBylineText: YtText? = null,
)

@Serializable
data class PlaylistVideoRenderer(
    val videoId: String,
    val title: YtText? = null,
    val lengthText: YtText? = null,
    val lengthSeconds: String? = null,
    val videoInfo: YtText? = null,
    val shortBylineText: YtText? = null,
    val isPlayable: Boolean? = null,
)

@Serializable
data class ContinuationItemRenderer(val continuationEndpoint: ContinuationEndpoint? = null)

@Serializable
data class ContinuationEndpoint(val continuationCommand: ContinuationCommand? = null)

@Serializable
data class ContinuationCommand(val token: String? = null)

@Serializable
data class ResolveUrlResponse(val endpoint: NavigationEndpoint? = null)

@Serializable
data class ChannelMetadataRenderer(
    val title: String? = null,
    val description: String? = null,
    val externalId: String? = null,
    val vanityChannelUrl: String? = null,
    val avatar: Thumbnails? = null,
)

@Serializable
data class MicroformatDataRenderer(
    val title: String? = null,
    val description: String? = null,
    val thumbnail: Thumbnails? = null,
)

// ============================== Player ==============================

@Serializable
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
    val captions: Captions? = null,
    val videoDetails: VideoDetails? = null,
    val microformat: PlayerMicroformat? = null,
)

@Serializable
data class PlayabilityStatus(
    val status: String? = null,
    val reason: String? = null,
)

@Serializable
data class StreamingData(
    val formats: List<YtFormat> = emptyList(),
    val adaptiveFormats: List<YtFormat> = emptyList(),
    val hlsManifestUrl: String? = null,
    val dashManifestUrl: String? = null,
)

@Serializable
data class YtFormat(
    val itag: Int = 0,
    /** Present for clients that receive un-ciphered URLs (ANDROID_VR, IOS). */
    val url: String? = null,
    /** Present for WEB-like clients; requires player JS to decipher, so such formats are skipped. */
    val signatureCipher: String? = null,
    val mimeType: String = "",
    val bitrate: Long? = null,
    val averageBitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val qualityLabel: String? = null,
    val audioQuality: String? = null,
    val audioChannels: Int? = null,
    val contentLength: String? = null,
    val audioTrack: YtAudioTrack? = null,
    val isDrc: Boolean? = null,
) {
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")

    /** Codec string, e.g. `avc1.640028`, `vp9`, `av01.0.08M.08`, `opus`, `mp4a.40.2`. */
    val codecs: String
        get() = mimeType.substringAfter("codecs=\"", "").substringBefore('"')
}

@Serializable
data class YtAudioTrack(
    val displayName: String? = null,
    val id: String? = null,
    val audioIsDefault: Boolean? = null,
)

@Serializable
data class Captions(val playerCaptionsTracklistRenderer: CaptionTrackList? = null)

@Serializable
data class CaptionTrackList(val captionTracks: List<CaptionTrack> = emptyList())

@Serializable
data class CaptionTrack(
    val baseUrl: String,
    val name: YtText? = null,
    val languageCode: String? = null,
    /** `asr` for auto-generated captions. */
    val kind: String? = null,
)

@Serializable
data class VideoDetails(
    val videoId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val channelId: String? = null,
    val shortDescription: String? = null,
    val lengthSeconds: String? = null,
    val viewCount: String? = null,
    val isLiveContent: Boolean? = null,
    val keywords: List<String>? = null,
)

@Serializable
data class PlayerMicroformat(val playerMicroformatRenderer: PlayerMicroformatRenderer? = null)

@Serializable
data class PlayerMicroformatRenderer(
    val publishDate: String? = null,
    val uploadDate: String? = null,
    val category: String? = null,
)

// ============================== JSON helpers ==============================

internal fun String.normalizeUrl(): String = if (startsWith("//")) "https:$this" else this

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

internal val JsonElement?.array: List<JsonElement>
    get() = (this as? JsonArray) ?: emptyList()

/**
 * Depth-first walk over a JSON tree. [visit] is called for every `key -> object` pair; return
 * `true` from it to mark the subtree as consumed (it will not be descended into).
 */
internal fun JsonElement.walk(visit: (key: String, value: JsonObject) -> Boolean) {
    when (this) {
        is JsonObject -> for ((key, value) in this) {
            if (value is JsonObject && visit(key, value)) continue
            value.walk(visit)
        }
        is JsonArray -> forEach { it.walk(visit) }
        else -> Unit
    }
}

/** First object found anywhere under this element with the given key. */
internal fun JsonElement.findFirst(key: String): JsonObject? {
    var found: JsonObject? = null
    walk { k, v ->
        if (found == null && k == key) found = v
        found != null
    }
    return found
}

/** All non-blank string values found under the given property name (e.g. `content`, `text`). */
internal fun JsonElement.collectStrings(key: String): List<String> {
    val out = mutableListOf<String>()
    fun rec(e: JsonElement) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key && v is JsonPrimitive && v.isString) {
                    v.content.takeIf { it.isNotBlank() }?.let(out::add)
                } else {
                    rec(v)
                }
            }
            is JsonArray -> e.forEach(::rec)
            else -> Unit
        }
    }
    rec(this)
    return out
}
