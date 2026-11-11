package me.sorihiko.dangomusicgtnh.multiplayer

import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NearbyMedia
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
        const val GROUP_ACTION = 7
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
    val albumName: String? = null,
) {
    val key: String get() = "$source:$nativeId"

    fun toTrack(): NeteaseTrack {
        val src = MusicSource.values().firstOrNull { it.key == source } ?: throw IllegalArgumentException("Unsupported music source")
        val track = NeteaseTrack(
            id = id,
            name = name.take(NearbyProtocol.MAX_TRACK_NAME),
            artists = artists.take(NearbyProtocol.MAX_ARTISTS).map { it.take(120) },
            durationMs = durationMs.coerceIn(0L, 24L * 60L * 60L * 1000L),
            coverUrl = coverUrl.take(NearbyProtocol.MAX_URL).takeIf { NearbyMedia.isAllowedRelayHost(it, false) }.orEmpty(),
            source = src,
            nativeId = nativeId,
            mediaId = mediaId.ifEmpty { nativeId },
            albumId = albumId,
        )
        track.cloud = cloud
        track.cloudId = cloudId
        track.albumAudioId = albumAudioId
        track.albumName = albumName.orEmpty().take(NearbyProtocol.MAX_TRACK_NAME)
        return track
    }

    companion object {
        fun from(track: NeteaseTrack): SharedTrack = SharedTrack(
            source = track.source.key,
            nativeId = track.nativeId,
            id = track.id,
            name = track.name.take(NearbyProtocol.MAX_TRACK_NAME),
            artists = track.artists.take(NearbyProtocol.MAX_ARTISTS).map { it.take(120) },
            durationMs = track.durationMs,
            coverUrl = track.coverUrl.take(NearbyProtocol.MAX_URL),
            cloud = track.cloud,
            cloudId = track.cloudId,
            albumId = track.albumId,
            mediaId = track.mediaId,
            albumAudioId = track.albumAudioId,
            albumName = track.albumName.take(NearbyProtocol.MAX_TRACK_NAME).takeIf { it.isNotBlank() },
        )
    }
}

data class SharedLyricLine(
    val timeMs: Long = 0L,
    val text: String = "",
    val translation: String? = null,
    val romanization: String? = null,
)

data class LyricsStyle(
    val font: String = "",
    val smooth: Boolean = false,
    val textColor: Int = 0xF7E1D7,
    val translationColor: Int = 0x803D66,
    val scale: Float = 1f,
    val distance: Float = 8.7f,
    val maxLines: Int = 1,
    val glow: Boolean = false,
    val glowStrength: Float = 0.3f,
    val glowRadius: Float = 0.5f,
    val translation: Boolean = false,
    val romanization: Boolean = false,
    val ktv: Boolean = false,
    val ktvBaseColor: Int = 0xD8D8D8,
    val ktvSungColor: Int = 0xFF345E,
    val wiggle: Boolean = true,
    val wiggleFps: Int = 8,
    val wiggleAmount: Float = 0.8f,
)

data class LyricsAnchor(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val scale: Float = 1f,
    /** Host's wiggle frame at capture time; -1 when unavailable. */
    val wiggleFrame: Long = -1L,
)

/** Keeps remote-controlled style values inside the range the renderer can handle. */
fun LyricsStyle.sanitized(): LyricsStyle {
    fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
    return copy(
        font = font.take(120),
        scale = scale.finiteOr(1f).coerceIn(0.3f, 3f),
        distance = distance.finiteOr(8.7f).coerceIn(3f, 40f),
        maxLines = maxLines.coerceIn(1, 8),
        glowStrength = glowStrength.finiteOr(0.3f).coerceIn(0f, 3f),
        glowRadius = glowRadius.finiteOr(0.5f).coerceIn(0f, 1f),
        wiggleFps = wiggleFps.coerceIn(1, 24),
        wiggleAmount = wiggleAmount.finiteOr(0.8f).coerceIn(0f, 2f),
    )
}

fun LyricsAnchor.sanitized(): LyricsAnchor = copy(
    x = x.takeIf { it.isFinite() }?.coerceIn(-3.0e7, 3.0e7) ?: 0.0,
    y = y.takeIf { it.isFinite() }?.coerceIn(-3.0e7, 3.0e7) ?: 0.0,
    z = z.takeIf { it.isFinite() }?.coerceIn(-3.0e7, 3.0e7) ?: 0.0,
    yaw = yaw.takeIf { it.isFinite() } ?: 0f,
    pitch = pitch.takeIf { it.isFinite() } ?: 0f,
    // The host's world scale is around 0.004..0.55; keep the real range instead
    // of clamping it to 0.1 (which would make remote text visibly larger).
    scale = scale.takeIf { it.isFinite() }?.coerceIn(0.001f, 2f) ?: 0.05f,
    wiggleFrame = wiggleFrame.coerceAtLeast(-1L),
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
    /** Optional server capabilities, e.g. "rooms" (remembers each player's room). Missing on older servers. */
    val features: List<String>? = null,
)

data class Prefs(
    val shareMusic: Boolean = false,
    val hearNearby: Boolean = true,
    val shareLyrics: Boolean = false,
    val seeLyrics: Boolean = true,
    val hearGroup: Boolean = true,
    val shareGroup: Boolean = true,
    /** Group id this player is currently listening to; empty = no active group. */
    val activeGroup: String = "",
)

data class NowPlayingState(
    val track: SharedTrack? = null,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    /** Short-lived URL resolved under the host's account; optional. */
    val url: String? = null,
    /** World anchor of the host's current 3D lyric line; optional. */
    val anchor: LyricsAnchor? = null,
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
    val anchor: LyricsAnchor? = null,
    /** Client-local receive time; not sent by the server. */
    val receivedAtMs: Long = 0L,
)

data class NearbyUpdate(
    val hosts: List<RemoteHost> = emptyList(),
    val group: GroupView? = null,
    val groupUi: GroupUi? = null,
)

data class GroupPlayer(
    val name: String = "",
    val uuid: String = "",
)

data class GroupSummary(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val members: Int = 0,
    val max: Int = 0,
)

data class GroupInvite(
    val groupId: String = "",
    val groupName: String = "",
    val from: String = "",
)

data class GroupAction(
    val action: String = "",
    val name: String = "",
    val target: String = "",
)

data class GroupUi(
    /** Currently selected/listened group, if any. */
    val group: GroupView? = null,
    val players: List<GroupPlayer> = emptyList(),
    val invites: List<GroupInvite> = emptyList(),
    /** Open groups the player has not joined. */
    val groups: List<GroupSummary> = emptyList(),
    /** All groups this player is a member of, including offline members. */
    val groupsJoined: List<GroupView> = emptyList(),
    /** Room this player was last listening to (remembered by the server); clients rejoin it after reconnecting. */
    val resumeGroup: String = "",
)

data class GroupView(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val hostUuid: String = "",
    /** Current group speaker; any member can become it. */
    val speaker: String = "",
    val speakerUuid: String = "",
    val members: List<String> = emptyList(),
    val idle: Boolean = false,
)

data class LyricsData(
    val trackKey: String = "",
    val lines: List<SharedLyricLine> = emptyList(),
    val style: LyricsStyle? = null,
)

data class RelayRequest(
    val transferId: Int = 0,
    val trackKey: String = "",
    val url: String? = null,
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
                romanization = line.romanization?.trim()?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { it.isNotEmpty() },
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
    romanization = romanization?.trim()?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { it.isNotEmpty() },
)
