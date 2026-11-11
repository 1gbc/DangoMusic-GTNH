package me.sorihiko.dangomusicgtnh.music

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** 歌手页用的歌手信息（各音源一样的格式）。[id] 是音源自己的歌手 ID（QQ 是 mid） */
class SourceArtist(
    val source: MusicSource,
    val id: String,
    val name: String,
    val picUrl: String,
    /** 别名、译名、外文名 */
    val alias: List<String>,
    val brief: String,
    val songCount: Int,
    val albumCount: Int,
    /** 热门歌曲（最多 50 首） */
    val hotSongs: List<NeteaseTrack>,
)

/** QQ 音乐的歌手、专辑（公开接口，不用登录） */
internal object QqArtists {
    /** 专辑当成歌单打开：界面里的歌单 ID → 专辑 mid */
    private val albums = ConcurrentHashMap<Long, String>()

    /** 这个歌单 ID 是歌手页里点开的专辑：返回它的歌，不是就返回 null */
    fun albumTracksFor(playlistId: Long): List<NeteaseTrack>? = albums[playlistId]?.let(::albumTracks)

    private fun cgi(module: String, method: String, params: JsonObject) = QqMusicSource.cgi(module, method, params)

    fun artist(mid: String): SourceArtist? {
        val detail = cgi("music.musichallSinger.SingerInfoInter", "GetSingerDetail", JsonObject().apply {
            add("singer_mids", com.google.gson.JsonArray().apply { add(com.google.gson.JsonPrimitive(mid)) })
            addProperty("pic", 1); addProperty("group_singer", 0); addProperty("wiki_singer", 0); addProperty("ex_singer", 1)
        })
        val singer = detail.array("singer_list").firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val basic = singer.obj("basic_info") ?: return null
        val ex = singer.obj("ex_info")
        val songs = cgi("musichall.song_list_server", "GetSingerSongList", JsonObject().apply {
            addProperty("singerMid", mid); addProperty("begin", 0); addProperty("num", 50); addProperty("order", 1)
        })
        val hot = songs.array("songList").mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.obj("songInfo")?.let(QqMusicSource::parseTrack)
        }
        val albumTotal = try {
            cgi("music.musichallAlbum.AlbumListServer", "GetAlbumList", albumParams(mid, 0, 1)).number("total").toInt()
        } catch (_: Exception) { 0 }
        return SourceArtist(
            source = MusicSource.QQ,
            id = mid,
            name = cleanSourceText(basic.text("name")),
            picUrl = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg",
            alias = listOfNotNull(ex?.text("foreign_name")?.takeIf { it.isNotBlank() }),
            brief = ex?.text("desc").orEmpty().trim(),
            songCount = songs.number("totalNum").toInt(),
            albumCount = albumTotal,
            hotSongs = hot,
        )
    }

    private fun albumParams(mid: String, begin: Int, num: Int) = JsonObject().apply {
        addProperty("singerMid", mid); addProperty("order", 0); addProperty("begin", begin); addProperty("num", num)
        addProperty("songNumTag", 0); addProperty("singerID", 0)
    }

    /** 歌手的专辑（新的在前），最多 300 张 */
    fun artistAlbums(mid: String): List<NeteasePlaylist> {
        val out = ArrayList<NeteasePlaylist>()
        while (out.size < 300) {
            val data = cgi("music.musichallAlbum.AlbumListServer", "GetAlbumList", albumParams(mid, out.size, 80))
            val list = data.array("albumList")
            for (el in list) {
                if (!el.isJsonObject) continue
                val al = el.asJsonObject
                val albumMid = al.text("albumMid").takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) } ?: continue
                val id = MusicSources.stableId(MusicSource.QQ, "album:$albumMid")
                albums[id] = albumMid
                out += NeteasePlaylist(id, cleanSourceText(al.text("albumName")), al.number("totalNum").toInt(),
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg", false)
            }
            if (list.size() == 0 || out.size >= data.number("total")) break
        }
        return out
    }

    /** 专辑里的歌 */
    fun albumTracks(albumMid: String): List<NeteaseTrack> {
        val out = LinkedHashMap<String, NeteaseTrack>()
        var begin = 0
        repeat(20) {
            val data = cgi("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", JsonObject().apply {
                addProperty("albumMid", albumMid); addProperty("albumID", 0); addProperty("begin", begin); addProperty("num", 100); addProperty("order", 2)
            })
            val list = data.array("songList")
            for (el in list) el.takeIf { it.isJsonObject }?.asJsonObject?.obj("songInfo")?.let(QqMusicSource::parseTrack)?.let { out[it.key] = it }
            begin += list.size()
            if (list.size() == 0 || begin >= data.number("totalNum")) return out.values.toList()
        }
        return out.values.toList()
    }

    /** 按名字找歌手 mid：名字完全一样的优先，没有就取第一个；找不到返回 "" */
    fun findArtist(name: String): String {
        if (name.isBlank()) return ""
        val root = SourceHttp.json("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&key=${SourceHttp.enc(name.trim())}", "https://y.qq.com/")
        val list = root.obj("data")?.obj("singer")?.array("itemlist")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: return ""
        val hit = list.firstOrNull { it.text("name").equals(name.trim(), ignoreCase = true) } ?: list.firstOrNull()
        return hit?.text("mid")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) }.orEmpty()
    }
}
