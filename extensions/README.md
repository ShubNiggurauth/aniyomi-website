# Aniyomi YouTube extension

An [Aniyomi](https://aniyomi.org) / Kuukiyomi anime extension for YouTube (`all.youtube`), built against
`extensions-lib` 14.

| YouTube | Aniyomi |
| --- | --- |
| Playlist (`/playlist?list=…`) | Series; every item is an episode (1, 2, 3, … in playlist order) |
| Channel (`/channel/UC…`, `/@handle`) | Series; the channel's uploads playlist (`UULF…` without Shorts, or `UU…`), oldest upload = episode 1 |
| Video (`/watch?v=…`) | Single-episode entry (`COMPLETED`) |

* **Browse**: Popular = the *default browse query* (setting) by relevance; Latest = the same query by upload date.
* **Search**: filters for type (All / Playlists / Channels / Videos), sort (Relevance / Upload date / View count / Rating)
  and duration. Pasting a YouTube link, `@handle` or channel/playlist id opens it directly.
* **Playback**: InnerTube `player` endpoint with `ANDROID_VR` → `IOS` → `WEB` fallback. Only direct (un-ciphered) stream
  URLs are used. Produces adaptive DASH video with separate audio tracks (all dubbed languages included), muxed
  progressive streams, and HLS when offered. YouTube captions (manual and, optionally, auto-generated) are attached
  as WebVTT subtitle tracks.
* **Settings**: preferred quality, preferred codec (H.264 / VP9 / AV1), default browse query, Popular/Latest content
  type, InnerTube client override, leave out Shorts from channels, include auto-generated captions.

## Build

Requires JDK 17+ and the Android SDK (API 34).

```sh
cd extensions
./gradlew assembleDebug
# → src/all/youtube/build/outputs/apk/debug/aniyomi-all.youtube-v14.1-debug.apk
```

For a signed release build, set `SIGNING_KEY_PATH`, `KEY_STORE_PASSWORD`, `ALIAS` and `KEY_PASSWORD`, then run
`./gradlew assembleRelease`.

CI (`.github/workflows/youtube-extension.yml`) builds the debug APK and uploads it as the
`aniyomi-all.youtube-debug` artifact.

## Source layout

```
src/all/youtube/src/eu/kanade/tachiyomi/animeextension/all/youtube/
├── YouTube.kt           ConfigurableAnimeSource: browse, search, details, episodes, settings
├── YouTubeDto.kt        @Serializable InnerTube request/response models + JSON helpers
├── YouTubeExtractor.kt  stream, audio track and subtitle extraction with client fallback
├── YouTubeFilters.kt    search filters and their protobuf `params` encoding
├── YouTubeParser.kt     renderer → SAnime/SEpisode mapping, URL parsing, dates
└── InnerTube.kt         InnerTube client identities and request builders
```

When YouTube changes client requirements, the client versions and user agents in `InnerTube.kt` are the first thing
to update.
