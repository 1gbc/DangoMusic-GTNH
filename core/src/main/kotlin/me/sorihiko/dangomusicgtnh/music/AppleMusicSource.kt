package me.sorihiko.dangomusicgtnh.music

import com.google.gson.JsonObject
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import java.util.concurrent.ConcurrentHashMap

/** Catalog/library calls execute in the signed-in player. Only filtered metadata returns. */
object AppleMusicSource : MusicSourceApi {
    override val source = MusicSource.APPLE
    private val ids = ConcurrentHashMap<Long, String>()
    private val libraryId = MusicSources.stableId(source, "library:songs")
    fun clear() { ids.clear() }
    override val libraryWarning get() = "Apple Music: sign in once, then search and play in the game"
    override fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? = null
    override fun lyrics(track: NeteaseTrack): Lyrics? = null
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
        return listOf(NeteasePlaylist(libraryId, me.sorihiko.dangomusicgtnh.i18n.I18n.text("Library songs"), 0, "", true)) + lists
    }
    override fun playlistTracks(id: Long): List<NeteaseTrack> =
        if (id == libraryId) rows("songs").map(::track)
        else rows("playlistTracks", ids[id] ?: throw SourceAccessException("Refresh your Apple Music library")).map(::track)
}
