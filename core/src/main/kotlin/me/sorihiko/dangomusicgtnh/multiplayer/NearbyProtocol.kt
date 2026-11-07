package me.sorihiko.dangomusicgtnh.multiplayer

import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack

/**
 * Wire format shared by the client and the optional server component.
 *
 * Everything in this file is plain data; the Forge-specific packet classes live in
 * the root module so this core module stays free of Minecraft/LWJGL imports.
 */
object NearbyProtocol {
    const val VERSION = 1
    const val CHANNEL = "dangomusicgtnh"

    /** Client -> server. */
    object C2S {
        const val HELLO = 1
        const val PREFS = 2
        const val NOW_PLAYING = 3
        const val LYRICS = 4
        const val RELAY_REQUEST = 5
        const val RELAY_CANCEL = 6
    }

    /** Server -> client. */
    object S2C {
        const val WELCOME = 20
        const val NEARBY = 21
        const val LYRICS = 22
        const val RELAY_DATA = 23
        const val RELAY_ERROR = 24
    }

    const val MAX_PACKET_BYTES = 28 * 1024
    const val MAX_LYRICS_LINES = 300
    const val MAX_LYRIC_CHARS = 300
    const val MAX_URL = 2048
    const val MAX_TRACK_NAME = 200
    const val MAX_ARTISTS = 16
    const val MAX_RELAY_BYTES = 16 * 1024 * 1024
    const val RELAY_CHUNK_BYTES = 4096

    /** Sharing quality: 0 = 128k, 1 = 192k, 2 = 320k. */
    fun qualityId(quality: Int): Int = quality.coerceIn(0, 2)
}

/** A track as it travels over the network; platform IDs are preserved. */
data class SharedTrack(
    val source: String = MusicSource.NETEASE.key,
    val nativeId: String = "",
    val id: Long = 0L,
    val name: String = "",
    val artists: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val cloud: Boolean = false,
    val cloudId: Long = 0L,
    val albumId: String = "",
    val mediaId: String = "",
    val albumAudioId: String = "",
) {
    val key: String get() = "$source:$nativeId" + if (source == MusicSource.KUGOU.key && cloud) ":cloud" else ""

    fun toTrack(): NeteaseTrack {
        val src = MusicSource.values().firstOrNull { it.key == source } ?: MusicSource.NETEASE
        val track = NeteaseTrack(
            id = id,
            name = name.take(NearbyProtocol.MAX_TRACK_NAME),
            artists = artists.take(NearbyProtocol.MAX_ARTISTS).map { it.take(120) },
            durationMs = durationMs.coerceAtLeast(0L),
            coverUrl = coverUrl.take(NearbyProtocol.MAX_URL),
            source = src,
            nativeId = nativeId,
            mediaId = mediaId.ifEmpty { nativeId },
            albumId = albumId,
        )
        track.cloud = cloud
        track.cloudId = cloudId
        track.albumAudioId = albumAudioId
        return track
    }

    companion object {
        fun from(track: NeteaseTrack): SharedTrack = SharedTrack(
            source = track.source.key,
            nativeId = track.nativeId,
            id = track.id,
            name = track.name.take(NearbyProtocol.MAX_TRACK_NAME),
            artists = track.artists.take(NearbyProtocol.MAX_ARTISTS),
            durationMs = track.durationMs,
            coverUrl = track.coverUrl.take(NearbyProtocol.MAX_URL),
            cloud = track.cloud,
            cloudId = track.cloudId,
            albumId = track.albumId,
            mediaId = track.mediaId,
            albumAudioId = track.albumAudioId,
        )
    }
}

data class SharedLyricLine(
    val timeMs: Long = 0L,
    val text: String = "",
    val translation: String? = null,
)

data class Hello(
    val protocol: Int = NearbyProtocol.VERSION,
    val version: String = "",
)

data class Welcome(
    val protocol: Int = NearbyProtocol.VERSION,
    val enabled: Boolean = true,
    val radius: Int = 32,
    val maxGroup: Int = 8,
    val allowLyrics: Boolean = true,
    val relay: Boolean = false,
)

data class Prefs(
    val shareMusic: Boolean = false,
    val hearNearby: Boolean = true,
    val shareLyrics: Boolean = false,
    val seeLyrics: Boolean = true,
    val hearGroup: Boolean = true,
    val shareGroup: Boolean = true,
)

data class NowPlayingState(
    val track: SharedTrack? = null,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    /** Short-lived URL resolved under the host's account; optional. */
    val url: String? = null,
)

data class RemoteHost(
    val player: String = "",
    val uuid: String = "",
    val inGroup: Boolean = false,
    val dimension: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val track: SharedTrack? = null,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val shareLyrics: Boolean = false,
    val url: String? = null,
    val urlAtMs: Long = 0L,
    /** Client-local receive time; not sent by the server. */
    val receivedAtMs: Long = 0L,
)

data class NearbyUpdate(
    val hosts: List<RemoteHost> = emptyList(),
    val group: GroupView? = null,
)

data class GroupView(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val hostUuid: String = "",
    val members: List<String> = emptyList(),
    val idle: Boolean = false,
)

data class LyricsData(
    val trackKey: String = "",
    val lines: List<SharedLyricLine> = emptyList(),
)

data class RelayRequest(
    val transferId: Int = 0,
    val trackKey: String = "",
    val url: String? = null,
    val quality: Int = 1,
)

data class RelayChunk(
    val transferId: Int = 0,
    val index: Int = 0,
    val total: Int = 0,
    /** Base64; 4 KiB of audio becomes ~5.4 KiB on the wire. */
    val data: String = "",
)

data class RelayError(
    val transferId: Int = 0,
    val message: String = "",
)

/** Convert parsed lyrics into the line-level payload used by remote projections. */
fun shareableLyrics(lyrics: Lyrics?): List<SharedLyricLine>? {
    if (lyrics == null) return null
    val lines = lyrics.lines.asSequence()
        .filter { it.text.isNotBlank() }
        .take(NearbyProtocol.MAX_LYRICS_LINES)
        .map { line ->
            SharedLyricLine(
                timeMs = line.timeMs.coerceAtLeast(0L),
                text = line.text.trim().take(NearbyProtocol.MAX_LYRIC_CHARS),
                translation = line.translation?.trim()?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { it.isNotEmpty() },
            )
        }
        .toList()
    return lines.takeIf { it.isNotEmpty() }
}

/** Kept for callers that already have a single line. */
fun LyricLine.toShared(): SharedLyricLine = SharedLyricLine(
    timeMs = timeMs.coerceAtLeast(0L),
    text = text.trim().take(NearbyProtocol.MAX_LYRIC_CHARS),
    translation = translation?.trim()?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { it.isNotEmpty() },
)
