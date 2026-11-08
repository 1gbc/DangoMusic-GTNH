package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.musicui.Library
import me.sorihiko.dangomusicgtnh.network.NearbyClient

/**
 * One read-only view over the two possible playback owners.
 *
 * Priority is personal -> group -> nearby. While the local player has a track, the
 * nearby/group stream is paused (not stopped) so it can resume when local playback stops.
 * UI code should read this instead of MusicPlayer directly when it needs "the current song".
 */
object PlaybackView {

    data class Snapshot(
        val remote: Boolean = false,
        val track: NeteaseTrack? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val message: String = "",
        val sourceLabel: String = "",
    )

    fun localPresent(): Boolean =
        MusicPlayer.current != null && MusicPlayer.state != MusicPlayer.State.STOPPED &&
            // 因为群组接管而暂停的本地音乐让位给群组，不再压住远端
            !(NearbyClient.localYielded && MusicPlayer.state == MusicPlayer.State.PAUSED && NearbyClient.activeHost() != null)

    fun remoteActive(): Boolean =
        !localPresent() &&
            NearbyAudioPlayer.state != NearbyAudioPlayer.State.IDLE &&
            NearbyClient.activeHost() != null

    fun isActive(): Boolean = localPresent() || remoteActive()

    fun snapshot(): Snapshot {
        if (localPresent()) {
            val track = MusicPlayer.current
            return Snapshot(
                remote = false,
                track = track,
                positionMs = MusicPlayer.positionMs,
                durationMs = MusicPlayer.durationMs,
                playing = MusicPlayer.state == MusicPlayer.State.PLAYING,
                loading = MusicPlayer.state == MusicPlayer.State.LOADING,
                message = MusicPlayer.message,
                sourceLabel = I18n.text(if (track?.source == MusicSource.APPLE) "Apple Music" else "Local"),
            )
        }
        val host = NearbyClient.activeHost()
        if (host != null && NearbyAudioPlayer.state != NearbyAudioPlayer.State.IDLE) {
            val track = host.track?.toTrack()
            val prefix = I18n.text(if (host.inGroup) "Group" else "Nearby")
            return Snapshot(
                remote = true,
                track = track,
                positionMs = NearbyAudioPlayer.positionMs,
                durationMs = track?.durationMs ?: 0L,
                playing = NearbyAudioPlayer.state == NearbyAudioPlayer.State.PLAYING,
                loading = NearbyAudioPlayer.state == NearbyAudioPlayer.State.LOADING,
                message = NearbyAudioPlayer.message,
                sourceLabel = "$prefix · ${host.player}",
            )
        }
        return Snapshot()
    }

    fun togglePause() {
        if (remoteActive()) NearbyAudioPlayer.togglePause() else MusicPlayer.togglePause()
    }

    fun previous() {
        if (!remoteActive()) MusicPlayer.prev()
    }

    fun next() {
        if (!remoteActive()) MusicPlayer.next()
    }

    /** The listener cannot seek a host's stream; only local playback is seekable. */
    fun seek(positionMs: Long) {
        if (!remoteActive()) MusicPlayer.seek(positionMs)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        MusicPlayer.volume = v
        NearbyAudioPlayer.volume = v
    }

    fun stop() {
        if (remoteActive()) {
            // Also switch the listening toggles off, so the next server update
            // cannot immediately resume the same source. Re-enable in Settings.
            NearbyClient.stopForUser()
        } else {
            // Library.stopPlayback also cancels an in-flight "heart mode" request;
            // going straight to MusicPlayer.stop() could let it start afterwards.
            Library.stopPlayback()
        }
    }
}
