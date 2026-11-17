package eu.kanade.tachiyomi.animeextension.all.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A YouTube InnerTube client identity.
 *
 * The values mirror the ones used by first-party YouTube apps. Mobile/VR clients receive direct,
 * un-ciphered stream URLs, which is what makes playback possible without running YouTube's
 * player JavaScript. When YouTube rotates its requirements, bumping [ClientInfo.clientVersion] /
 * [userAgent] here is normally all that is needed.
 */
enum class InnerTubeClient(
    val key: String,
    val displayName: String,
    /** Numeric id sent in the `X-YouTube-Client-Name` header. */
    val clientId: Int,
    val userAgent: String,
    val info: ClientInfo,
) {
    ANDROID_VR(
        key = "ANDROID_VR",
        displayName = "ANDROID_VR",
        clientId = 28,
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        info = ClientInfo(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            osName = "Android",
            osVersion = "12L",
        ),
    ),
    IOS(
        key = "IOS",
        displayName = "IOS",
        clientId = 5,
        userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        info = ClientInfo(
            clientName = "IOS",
            clientVersion = "20.10.4",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
        ),
    ),
    WEB(
        key = "WEB",
        displayName = "WEB",
        clientId = 1,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        info = ClientInfo(
            clientName = "WEB",
            clientVersion = "2.20251110.01.00",
            platform = "DESKTOP",
        ),
    ),
    ;

    companion object {
        fun fromKey(key: String): InnerTubeClient? = values().firstOrNull { it.key == key }

        /** Clients tried for playback, in order, for the given user preference ("AUTO" or a key). */
        fun playbackOrder(preference: String): List<InnerTubeClient> {
            val auto = listOf(ANDROID_VR, IOS, WEB)
            val preferred = fromKey(preference) ?: return auto
            // The override goes first; the others remain as a fallback.
            return listOf(preferred) + (auto - preferred)
        }
    }
}

/** Thin helper that builds InnerTube `youtubei/v1/...` POST requests. */
class InnerTube(private val json: Json) {

    fun request(
        endpoint: String,
        client: InnerTubeClient,
        tag: Any? = null,
        body: JsonObjectBuilder.() -> Unit,
    ): Request {
        val payload = buildJsonObject {
            put("context", context(client))
            body()
        }
        val builder = Request.Builder()
            .url("$API_URL/$endpoint?prettyPrint=false")
            .headers(headers(client))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (tag != null) builder.tag(Any::class.java, tag)
        return builder.build()
    }

    fun search(query: String, params: String?, tag: Any?) = request("search", InnerTubeClient.WEB, tag) {
        put("query", query)
        if (!params.isNullOrEmpty()) put("params", params)
    }

    fun searchContinuation(token: String, tag: Any?) = request("search", InnerTubeClient.WEB, tag) {
        put("continuation", token)
    }

    fun browse(browseId: String, tag: Any? = null, params: String? = null) =
        request("browse", InnerTubeClient.WEB, tag) {
            put("browseId", browseId)
            if (params != null) put("params", params)
        }

    fun browseContinuation(token: String, tag: Any? = null) = request("browse", InnerTubeClient.WEB, tag) {
        put("continuation", token)
    }

    fun resolveUrl(path: String, tag: Any? = null) =
        request("navigation/resolve_url", InnerTubeClient.WEB, tag) {
            put("url", "https://www.youtube.com$path")
        }

    fun player(videoId: String, client: InnerTubeClient, tag: Any? = null) = request("player", client, tag) {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        put(
            "playbackContext",
            buildJsonObject {
                put(
                    "contentPlaybackContext",
                    buildJsonObject { put("html5Preference", "HTML5_PREF_WANTS") },
                )
            },
        )
    }

    /** Headers to send when the player fetches media from googlevideo.com for [client]. */
    fun mediaHeaders(client: InnerTubeClient): Headers = Headers.Builder()
        .add("User-Agent", client.userAgent)
        .add("Origin", ORIGIN)
        .add("Referer", "$ORIGIN/")
        .build()

    private fun context(client: InnerTubeClient): JsonObject = buildJsonObject {
        put("client", json.encodeToJsonElement(client.info))
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
    }

    private fun headers(client: InnerTubeClient): Headers = Headers.Builder()
        .add("User-Agent", client.userAgent)
        .add("Content-Type", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Origin", ORIGIN)
        .add("X-YouTube-Client-Name", client.clientId.toString())
        .add("X-YouTube-Client-Version", client.info.clientVersion)
        .build()

    companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val API_URL = "$ORIGIN/youtubei/v1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
