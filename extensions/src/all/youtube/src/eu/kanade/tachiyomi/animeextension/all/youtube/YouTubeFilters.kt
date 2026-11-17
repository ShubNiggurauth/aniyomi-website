package eu.kanade.tachiyomi.animeextension.all.youtube

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/** Search filters, encoded into YouTube's protobuf `params` (the `sp=` value on the website). */
object YouTubeFilters {

    /** What kind of result to search for. [proto] is the `type` field of YouTube's search filter. */
    enum class SearchType(val label: String, val proto: Int?) {
        ALL("All", null),
        PLAYLIST("Playlists (Series)", 3),
        CHANNEL("Channels", 2),
        VIDEO("Videos (Movies/Single)", 1),
        ;

        companion object {
            fun fromName(name: String?): SearchType = values().firstOrNull { it.name == name } ?: ALL
        }
    }

    /** Result order. [proto] is the top-level `sort` field (0 = relevance). */
    enum class SortBy(val label: String, val proto: Int) {
        RELEVANCE("Relevance", 0),
        UPLOAD_DATE("Upload Date", 2),
        VIEW_COUNT("View Count", 3),
        RATING("Rating", 1),
    }

    /** Video length. [proto] is the `duration` field of YouTube's search filter. */
    enum class Duration(val label: String, val proto: Int?) {
        ANY("Any", null),
        SHORT("Short (< 4 min)", 1),
        MEDIUM("Medium (4–20 min)", 3),
        LONG("Long (> 20 min)", 2),
    }

    open class EnumSelect<T : Enum<T>>(name: String, val entries: Array<T>, labels: Array<String>) :
        AnimeFilter.Select<String>(name, labels) {
        val selected: T get() = entries[state.coerceIn(entries.indices)]
    }

    class TypeFilter : EnumSelect<SearchType>(
        "Search type",
        SearchType.values(),
        SearchType.values().map { it.label }.toTypedArray(),
    )

    class SortFilter : EnumSelect<SortBy>(
        "Sort by",
        SortBy.values(),
        SortBy.values().map { it.label }.toTypedArray(),
    )

    class DurationFilter : EnumSelect<Duration>(
        "Duration",
        Duration.values(),
        Duration.values().map { it.label }.toTypedArray(),
    )

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Leave the search empty to use the default browse query."),
        AnimeFilter.Header("You can also paste a YouTube video, playlist or channel link."),
        TypeFilter(),
        SortFilter(),
        DurationFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Duration only applies to videos."),
    )

    data class Selection(
        val type: SearchType = SearchType.ALL,
        val sort: SortBy = SortBy.RELEVANCE,
        val duration: Duration = Duration.ANY,
    )

    fun parse(filters: AnimeFilterList): Selection {
        var selection = Selection()
        filters.forEach { filter ->
            selection = when (filter) {
                is TypeFilter -> selection.copy(type = filter.selected)
                is SortFilter -> selection.copy(sort = filter.selected)
                is DurationFilter -> selection.copy(duration = filter.selected)
                else -> selection
            }
        }
        return selection
    }

    /**
     * Encodes a [Selection] as YouTube's base64 protobuf `params`:
     * ```
     * message SearchParams { int32 sort = 1; Filters filters = 2; }
     * message Filters { int32 type = 2; int32 duration = 3; }
     * ```
     * Returns null when nothing differs from the defaults.
     */
    fun encode(selection: Selection): String? {
        val filters = mutableListOf<Byte>()
        selection.type.proto?.let { filters += varintField(2, it) }
        selection.duration.proto?.let { filters += varintField(3, it) }

        val message = mutableListOf<Byte>()
        if (selection.sort.proto != 0) message += varintField(1, selection.sort.proto)
        if (filters.isNotEmpty()) {
            message += tag(2, WIRE_LENGTH_DELIMITED)
            message += varint(filters.size)
            message += filters
        }
        if (message.isEmpty()) return null
        return Base64.encodeToString(message.toByteArray(), Base64.NO_WRAP)
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    private fun tag(field: Int, wire: Int) = varint((field shl 3) or wire)

    private fun varintField(field: Int, value: Int) = tag(field, WIRE_VARINT) + varint(value)

    private fun varint(value: Int): List<Byte> {
        val out = mutableListOf<Byte>()
        var v = value
        while (v and 0x7F.inv() != 0) {
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
        return out
    }
}
