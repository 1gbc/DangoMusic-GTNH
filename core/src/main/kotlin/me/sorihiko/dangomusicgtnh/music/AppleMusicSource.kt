package me.sorihiko.dangomusicgtnh.music

import com.google.gson.JsonObject
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import java.util.concurrent.ConcurrentHashMap

/** Catalog/library calls execute in the signed-in player. Only filtered metadata returns. */
object AppleMusicSource : MusicSourceApi {
    enum class Section(val label: String) {
        DISCOVER("New discoveries"), RADIO("Radio"), PINNED("Pinned"), RECENT("Recently added"),
        ARTISTS("Artists"), ALBUMS("Albums"), FOR_YOU("Made for You"), GENRES("Genres"),
        COMPILATIONS("Compilations")
    }
    override val source = MusicSource.APPLE
    private val ids = ConcurrentHashMap<Long, String>()
    private val cardRefs = ConcurrentHashMap<Long, JsonObject>()
    private val sections = ConcurrentHashMap<Long, String>()
    private val groupedTracks = ConcurrentHashMap<Long, List<NeteaseTrack>>()
    @Volatile var homeWarning = ""
        private set
    fun homeSection(id: Long) = sections[id] ?: "常用入口"
    private val libraryId = MusicSources.stableId(source, "library:songs")
    private val chartId = MusicSources.stableId(source, "catalog:charts")
    fun libraryEntry() = NeteasePlaylist(libraryId, I18n.text("Library songs"), 0, "", true)
    private val favoritesId = MusicSources.stableId(source, "library:favorites")
    fun favoritesEntry() = NeteasePlaylist(favoritesId, I18n.text("Favorite songs"), 0, "", true)
    fun browse(section: Section): List<NeteasePlaylist> = when (section) {
        Section.DISCOVER -> cards("discover") + chartEntry()
        Section.RADIO -> (cards("liveRadio") + runCatching { cards("radio") }.getOrDefault(emptyList())).distinctBy { it.id }
        Section.PINNED -> listOf(favoritesEntry())
        Section.RECENT -> cards("recentlyAdded")
        Section.ARTISTS, Section.GENRES -> libraryGroups(section)
        Section.ALBUMS -> cards("libraryAlbums")
        Section.FOR_YOU -> cards("recommend")
        Section.COMPILATIONS -> cards("libraryAlbums", compilations = true)
    }
    fun catalogGenres() = cards("genres")
    fun searchLibrary(query: String) = rows("librarySearch", query, 4).map(::track)

    data class EditorialItem(
        val card: NeteasePlaylist,
        val heading: String,
        val artist: String,
        val featuredCover: String,
        val song: NeteaseTrack?,
        val link: String
    )
    data class EditorialSection(val title: String, val layout: String, val items: List<EditorialItem>)

    fun editorial(section: Section): List<EditorialSection> {
        val op = if (section == Section.DISCOVER) "webDiscover" else "webRadio"
        val editorial = rows(op).groupBy { it.get("sectionIndex").asInt }.map { (index, items) ->
            EditorialSection(str(items.first(), "sectionTitle"), str(items.first(), "layout"), items.map { row ->
                val kind = str(row, "kind")
                val id = MusicSources.stableId(source, "$op:$index:$kind:${str(row, "id")}")
                val card = NeteasePlaylist(id, str(row, "title"), 0, str(row, "cover"), false)
                if (kind in listOf("albums", "playlists", "stations")) cardRefs[id] = row
                EditorialItem(card, str(row, "heading"), str(row, "artist"), str(row, "featuredCover"),
                    if (kind == "songs") track(row) else null, str(row, "link").takeIf { kind == "link" }.orEmpty())
            })
        }
        if (section != Section.RADIO || !AppleWebPlayer.authorized) return editorial
        val recent = runCatching { cards("radio") }.getOrDefault(emptyList())
        if (recent.isEmpty()) return editorial
        val shelf = EditorialSection(I18n.text("Recently played stations"), "squares", recent.map {
            EditorialItem(it, "", cardArtist(it.id), "", null, "")
        })
        val insert = if (editorial.firstOrNull()?.layout == "hero") 1 else 0
        return editorial.take(insert) + shelf + editorial.drop(insert)
    }

    fun cardArtist(id: Long) = cardRefs[id]?.let { str(it, "artist") }.orEmpty()
    fun featuredCover(id: Long) = cardRefs[id]?.let { str(it, "featuredCover") }.orEmpty()
    fun releaseDate(id: Long) = cardRefs[id]?.let { str(it, "releaseDate") }.orEmpty()

    private fun cards(op: String, compilations: Boolean = false): List<NeteasePlaylist> =
        rows(op, pages = if (op == "recommend" || op == "discover") 1 else 20).mapNotNull { row ->
            val type = str(row, "kind")
            if (compilations && row.get("compilation")?.asBoolean != true) return@mapNotNull null
            val id = MusicSources.stableId(source, "$type:${str(row, "id")}")
            cardRefs[id] = row
            NeteasePlaylist(id, str(row, "title"), 0, str(row, "cover"), type.startsWith("library-"))
        }

    private fun libraryGroups(section: Section): List<NeteasePlaylist> {
        val field = if (section == Section.ARTISTS) "artist" else "genre"
        return rows("songs").groupBy { str(it, field) }.filterKeys { it.isNotBlank() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (name, rows) ->
                val id = MusicSources.stableId(source, "$field:$name")
                groupedTracks[id] = rows.map(::track)
                NeteasePlaylist(id, name, rows.size, str(rows.first(), "cover"), true)
            }
    }

    fun favorite(track: NeteaseTrack, desired: Boolean? = null): Boolean {
        val id = track.mediaId.removePrefix("library:")
        val op = when (desired) {
            null -> "favoriteState"
            true -> "favoriteAdd"
            false -> "favoriteRemove"
        }
        val response = AppleWebPlayer.request(op, id, 0)
        val error = str(response, "error")
        if (error.isNotEmpty()) throw SourceAccessException(error)
        return response.get("favorite")?.asBoolean ?: throw SourceAccessException("Apple Music did not return favorite status")
    }
    fun chartEntry() = NeteasePlaylist(chartId, I18n.text("Apple Music popular songs"), 0, "", false)
    override fun recommend() = recommend(includePersonalSections = false)

    fun recommend(includePersonalSections: Boolean): List<NeteasePlaylist> {
        val result = ArrayList<NeteasePlaylist>()
        homeWarning = ""
        val feeds = buildList {
            add("recent" to I18n.text("Recently played"))
            add("recommend" to I18n.text("Recommended for you"))
            if (includePersonalSections) add("heavyRotation" to I18n.text("Heavy rotation"))
        }
        for ((op, defaultSection) in feeds) {
            try {
                for (row in rows(op, pages = if (includePersonalSections && op == "recent") 2 else 1)) {
                    val kind = str(row,"kind")
                    if (kind !in listOf("albums","playlists","library-albums","library-playlists","stations")) continue
                    val section = if (includePersonalSections && op == "recommend")
                        str(row, "sectionTitle").ifBlank { defaultSection } else defaultSection
                    val key = MusicSources.stableId(source, "$section:$kind:${str(row,"id")}")
                    cardRefs[key] = row
                    sections[key] = section
                    result += NeteasePlaylist(key,str(row,"title"),0,str(row,"cover"),kind.startsWith("library-"))
                }
            } catch (_: SourceAccessException) {
                if (op != "heavyRotation")
                    homeWarning = listOf(homeWarning,"${defaultSection}暂时不可用，可刷新重试").filter { it.isNotEmpty() }.joinToString("；")
            }
        }
        return result.distinctBy { it.id } + listOf(chartEntry(), libraryEntry())
    }
    override fun charts() = listOf(chartEntry())
    fun clear() { ids.clear();cardRefs.clear();sections.clear();groupedTracks.clear();homeWarning="" }
    override val libraryWarning get() = "Apple Music: sign in once, then search and play in the game"
    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = null
    override fun lyrics(track: NeteaseTrack): Lyrics? = AppleLyrics.load(track)
    private fun rows(op: String, value: String = "", pages: Int = 20): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        var offset = 0
        repeat(pages) {
            val response = AppleWebPlayer.request(op, value, offset)
            val error = response.get("error")?.asString.orEmpty()
            if (error.isNotEmpty()) throw SourceAccessException(error)
            val batch = response.getAsJsonArray("items").map { it.asJsonObject }
            result.addAll(batch)
            if (response.get("hasMore")?.asBoolean != true) return result
            val next = response.get("nextOffset")?.asInt ?: (offset + batch.size)
            if (next <= offset || next > 10000) return result
            offset = next
        }
        return result
    }
    private fun str(o: JsonObject, k: String) = o.get(k)?.asString.orEmpty()
    private fun track(o: JsonObject): NeteaseTrack {
        val id = str(o, "id")
        val playId = str(o, "playId").ifEmpty { id }
        val media = if (o.get("library")?.asBoolean == true) "library:$playId" else playId
        return NeteaseTrack(MusicSources.stableId(source, id), str(o, "title"), listOf(str(o, "artist")),
            o.get("durationMs")?.asLong ?: 0L, str(o, "cover"), source, id, media).apply { albumName = str(o, "album") }
    }
    override fun search(query: String) = rows("search", query, 4).map(::track)
    override fun playlists(): List<NeteasePlaylist> {
        val lists = rows("playlists").map { o ->
            val id = str(o, "id")
            val local = MusicSources.stableId(source, "playlist:$id")
            if (ids.size >= 1024) throw SourceAccessException("Too many Apple Music playlists; reconnect to refresh")
            ids[local] = id
            NeteasePlaylist(local, str(o, "title"), 0, str(o, "cover"), true)
        }
        return listOf(libraryEntry()) + lists
    }
    override fun playlistTracks(id: Long): List<NeteaseTrack> {
        stationTrack(id)?.let { return listOf(it) }
        groupedTracks[id]?.let { return it }
        if (id == favoritesId) {
            val favoriteList = rows("playlists").firstOrNull { it.get("favoriteList")?.asBoolean == true }
            return (if (favoriteList != null) rows("playlistTracks", str(favoriteList, "id")) else rows("favoriteSongs")).map(::track)
        }
        val card = cardRefs[id]
        if (card != null) {
            if (str(card, "kind") == "genres") return rows("genreCharts", str(card, "id"), 1).map(::track)
            return rows("resourceTracks", "${str(card,"kind")}:${str(card,"id")}").map(::track)
        }
        return if (id == chartId) rows("charts", pages = 1).map(::track)
        else if (id == libraryId) rows("songs").map(::track)
        else rows("playlistTracks", ids[id] ?: throw SourceAccessException("Refresh your Apple Music library")).map(::track)
    }

    fun stationTrack(id: Long): NeteaseTrack? = cardRefs[id]?.takeIf { str(it, "kind") == "stations" }?.let {
        val item = track(it)
        NeteaseTrack(item.id, item.name, item.artists, item.durationMs, item.coverUrl, source,
            item.nativeId, "station:${item.nativeId}")
    }
}
