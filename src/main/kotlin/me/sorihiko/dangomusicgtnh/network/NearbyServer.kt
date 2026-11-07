package me.sorihiko.dangomusicgtnh.network

import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsData
import me.sorihiko.dangomusicgtnh.multiplayer.NearbyProtocol
import me.sorihiko.dangomusicgtnh.multiplayer.NearbyUpdate
import me.sorihiko.dangomusicgtnh.multiplayer.NowPlayingState
import me.sorihiko.dangomusicgtnh.multiplayer.Prefs
import me.sorihiko.dangomusicgtnh.multiplayer.RelayChunk
import me.sorihiko.dangomusicgtnh.multiplayer.RelayError
import me.sorihiko.dangomusicgtnh.multiplayer.RelayRequest
import me.sorihiko.dangomusicgtnh.multiplayer.RemoteHost
import me.sorihiko.dangomusicgtnh.multiplayer.SharedLyricLine
import me.sorihiko.dangomusicgtnh.multiplayer.Welcome
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NearbyMedia
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.MinecraftServer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Optional server component. It never receives audio in the normal path: it only keeps
 * per-player state and forwards metadata/lyrics to nearby players and group members.
 * L3 (optional, off by default) fetches a host-provided URL and relays the file in chunks.
 */
object NearbyServer {
    private class PlayerState(val uuid: UUID) {
        @Volatile var player: EntityPlayerMP? = null
        @Volatile var prefs = Prefs()
        @Volatile var now = NowPlayingState()
        @Volatile var nowAt = 0L
        @Volatile var lyricsTrack = ""
        @Volatile var lyrics: List<SharedLyricLine>? = null
        @Volatile var lyricsVersion = 0
        @Volatile var capable = false

        /** Host UUID -> lyrics version already sent to this listener. */
        val sentLyrics = ConcurrentHashMap<UUID, Int>()
    }

    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val lastSent = ConcurrentHashMap<UUID, String>()
    private var ticks = 0

    fun handle(player: EntityPlayerMP, type: Int, json: String) {
        if (!ServerConfig.enabled) return
        val state = states.computeIfAbsent(player.uniqueID) { PlayerState(it) }
        state.player = player
        try {
            when (type) {
                NearbyProtocol.C2S.HELLO -> {
                    state.capable = true
                    sendWelcome(player)
                }
                NearbyProtocol.C2S.PREFS -> {
                    val prefs = NetworkBootstrap.gson.fromJson(json, Prefs::class.java) ?: return
                    state.prefs = prefs
                    if (!prefs.shareLyrics) {
                        state.lyrics = null
                        state.lyricsTrack = ""
                        state.lyricsVersion++
                    }
                    lastSent.remove(player.uniqueID)
                }
                NearbyProtocol.C2S.NOW_PLAYING -> {
                    val now = NetworkBootstrap.gson.fromJson(json, NowPlayingState::class.java) ?: return
                    state.now = sanitize(now)
                    state.nowAt = System.currentTimeMillis()
                    lastSent.remove(player.uniqueID)
                }
                NearbyProtocol.C2S.LYRICS -> {
                    val data = NetworkBootstrap.gson.fromJson(json, LyricsData::class.java) ?: return
                    if (!ServerConfig.allowLyrics || !state.prefs.shareLyrics) {
                        state.lyrics = null
                        state.lyricsTrack = ""
                    } else {
                        state.lyrics = data.lines.take(NearbyProtocol.MAX_LYRICS_LINES).map {
                            SharedLyricLine(
                                timeMs = it.timeMs.coerceAtLeast(0L),
                                text = it.text.take(NearbyProtocol.MAX_LYRIC_CHARS),
                                translation = it.translation?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { value -> value.isNotBlank() },
                            )
                        }
                        state.lyricsTrack = data.trackKey.take(200)
                    }
                    state.lyricsVersion++
                }
                NearbyProtocol.C2S.RELAY_REQUEST -> {
                    val request = NetworkBootstrap.gson.fromJson(json, RelayRequest::class.java) ?: return
                    Relay.start(player, request)
                }
                NearbyProtocol.C2S.RELAY_CANCEL -> {
                    val request = NetworkBootstrap.gson.fromJson(json, RelayRequest::class.java) ?: return
                    Relay.cancel(player.uniqueID, request.transferId)
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic packet type={} rejected: {}", type, e.javaClass.simpleName)
        }
    }

    fun tick() {
        if (!ServerConfig.enabled) return
        val server = MinecraftServer.getServer() ?: return
        val players = server.configurationManager.playerEntityList
        Relay.tick(players)
        if (++ticks % 10 != 0) return
        for (player in players) {
            val state = states[player.uniqueID] ?: continue
            if (!state.capable) continue
            state.player = player
            broadcastTo(player, state)
        }
        val online = players.mapTo(HashSet()) { it.uniqueID }
        states.keys.removeIf { it !in online }
        lastSent.keys.removeIf { it !in online }
    }

    fun onLogout(player: EntityPlayerMP) {
        val group = MusicGroups.leave(player.uniqueID)
        states.remove(player.uniqueID)
        lastSent.remove(player.uniqueID)
        Relay.cancelPlayer(player.uniqueID)
        if (group != null) invalidateAll()
    }

    fun isCapable(player: EntityPlayerMP): Boolean = states[player.uniqueID]?.capable == true

    fun groupCreate(player: EntityPlayerMP, name: String): String? {
        if (!isCapable(player)) return "请先安装并启用 DangoMusic 客户端"
        val error = MusicGroups.create(player.uniqueID, name, ServerConfig.maxGroup)
        if (error == null) invalidateAll()
        return error
    }

    fun groupJoin(player: EntityPlayerMP, name: String): String? {
        if (!isCapable(player)) return "请先安装并启用 DangoMusic 客户端"
        val error = MusicGroups.join(player.uniqueID, name, ServerConfig.maxGroup)
        if (error == null) invalidateAll()
        return error
    }

    fun groupLeave(player: EntityPlayerMP): String? {
        val group = MusicGroups.leave(player.uniqueID) ?: return null
        invalidateAll()
        return if (group.host == player.uniqueID) "已解散音乐群组：${group.name}" else "已离开音乐群组：${group.name}"
    }

    fun groupInfo(player: EntityPlayerMP): String {
        val group = MusicGroups.groupOf(player.uniqueID) ?: return "你不在音乐群组中"
        val host = states[group.host]?.player?.displayName ?: group.host.toString().take(8)
        val members = group.members.joinToString(", ") { states[it]?.player?.displayName ?: it.toString().take(8) }
        return "群组：${group.name} · host：$host · 成员：$members"
    }

    fun statusText(): String =
        "DangoMusic: enabled=${ServerConfig.enabled}, radius=${ServerConfig.radius}, " +
            "hosts=${states.values.count { it.now.track != null && it.prefs.shareMusic }}, " +
            "groups=${MusicGroups.all().size}, relay=${ServerConfig.relayEnabled}"

    private fun sendWelcome(player: EntityPlayerMP) {
        NetworkBootstrap.sendTo(
            player, NearbyProtocol.S2C.WELCOME,
            Welcome(
                protocol = NearbyProtocol.VERSION,
                enabled = ServerConfig.enabled,
                radius = ServerConfig.radius,
                maxGroup = ServerConfig.maxGroup,
                allowLyrics = ServerConfig.allowLyrics,
                relay = ServerConfig.relayEnabled,
            )
        )
    }

    private fun broadcastTo(listener: EntityPlayerMP, listenerState: PlayerState) {
        val group = MusicGroups.groupOf(listener.uniqueID)
        val visible = visibleHosts(listener, listenerState, group)
        val groupView = groupView(group, listener)
        val hosts = visible.mapNotNull { (state, inGroup) -> remoteHost(state, inGroup) }
        val update = NearbyUpdate(hosts, groupView)
        val json = NetworkBootstrap.gson.toJson(update)
        if (lastSent[listener.uniqueID] != json) {
            lastSent[listener.uniqueID] = json
            NetworkBootstrap.sendJsonTo(listener, NearbyProtocol.S2C.NEARBY, json)
        }

        if (listenerState.prefs.seeLyrics && hosts.isNotEmpty()) {
            for ((state, _) in visible) {
                if (!state.prefs.shareLyrics || state.lyrics == null || state.lyricsTrack.isEmpty()) continue
                val version = state.lyricsVersion
                if (listenerState.sentLyrics[state.uuid] == version) continue
                val data = fitLyrics(state.lyricsTrack, state.lyrics!!)
                NetworkBootstrap.sendTo(listener, NearbyProtocol.S2C.LYRICS, data)
                listenerState.sentLyrics[state.uuid] = version
            }
        } else {
            listenerState.sentLyrics.clear()
        }
    }

    private fun visibleHosts(
        listener: EntityPlayerMP,
        listenerState: PlayerState,
        group: MusicGroup?,
    ): List<Pair<PlayerState, Boolean>> {
        if (group != null) {
            if (!listenerState.prefs.hearGroup) return emptyList()
            // The host already plays locally; do not send its own track back to it.
            if (group.host == listener.uniqueID) return emptyList()
            val host = states[group.host] ?: return emptyList()
            if (host.player == null || host.now.track == null || !host.prefs.shareGroup) return emptyList()
            return listOf(host to true)
        }
        if (!listenerState.prefs.hearNearby) return emptyList()
        val radiusSq = ServerConfig.radius.toDouble() * ServerConfig.radius
        return states.values.asSequence()
            .filter {
                it.uuid != listener.uniqueID && it.capable && it.prefs.shareMusic && it.now.track != null
            }
            .mapNotNull { state ->
                val player = state.player ?: return@mapNotNull null
                if (player.dimension != listener.dimension) return@mapNotNull null
                val distance = listener.getDistanceSqToEntity(player)
                if (distance > radiusSq) return@mapNotNull null
                Triple(state, player, distance)
            }
            .sortedBy { it.third }
            .take(ServerConfig.maxHosts)
            .map { it.first to false }
            .toList()
    }

    private fun remoteHost(state: PlayerState, inGroup: Boolean): RemoteHost? {
        val player = state.player ?: return null
        val track = state.now.track ?: return null
        val elapsed = if (state.now.playing) (System.currentTimeMillis() - state.nowAt).coerceAtLeast(0L) else 0L
        var position = state.now.positionMs + elapsed
        if (track.durationMs > 0L) position = position.coerceAtMost(track.durationMs)
        return RemoteHost(
            player = player.displayName,
            uuid = state.uuid.toString(),
            inGroup = inGroup,
            dimension = player.dimension,
            x = player.posX,
            y = player.posY,
            z = player.posZ,
            track = track,
            positionMs = position,
            playing = state.now.playing,
            shareLyrics = state.prefs.shareLyrics,
            url = state.now.url,
            urlAtMs = state.nowAt,
        )
    }

    private fun groupView(group: MusicGroup?, listener: EntityPlayerMP): GroupView? {
        group ?: return null
        val host = states[group.host]
        val idle = host?.player == null || host.now.track == null || !host.prefs.shareGroup
        return MusicGroups.view(group, { uuid ->
            states[uuid]?.player?.displayName ?: uuid.toString().take(8)
        }, idle)
    }

    private fun fitLyrics(trackKey: String, lines: List<SharedLyricLine>): LyricsData {
        var take = lines
        while (take.size > 1) {
            val data = LyricsData(trackKey, take)
            if (NetworkBootstrap.gson.toJson(data).toByteArray(Charsets.UTF_8).size <= 24 * 1024) return data
            take = take.subList(0, take.size / 2)
        }
        return LyricsData(trackKey, take)
    }

    private fun sanitize(now: NowPlayingState): NowPlayingState {
        val track = now.track?.let {
            it.copy(
                source = it.source.take(16),
                nativeId = it.nativeId.take(128),
                name = it.name.take(NearbyProtocol.MAX_TRACK_NAME),
                artists = it.artists.take(NearbyProtocol.MAX_ARTISTS).map { artist -> artist.take(120) },
                durationMs = it.durationMs.coerceIn(0L, 24L * 60L * 60L * 1000L),
                coverUrl = it.coverUrl.take(NearbyProtocol.MAX_URL),
                albumId = it.albumId.take(128),
                mediaId = it.mediaId.take(128),
                albumAudioId = it.albumAudioId.take(128),
            )
        }
        val url = now.url?.takeIf { it.isNotBlank() && it.length <= NearbyProtocol.MAX_URL }
        val duration = track?.durationMs ?: 0L
        var position = now.positionMs.coerceAtLeast(0L)
        if (duration > 0L) position = position.coerceAtMost(duration)
        return NowPlayingState(
            track = track,
            positionMs = position,
            playing = now.playing,
            url = url,
        )
    }

    private fun invalidateAll() {
        lastSent.clear()
        states.values.forEach { it.sentLyrics.clear() }
    }

    /** Optional L3: the server fetches the host URL and sends the file to one listener. */
    private object Relay {
        private class Transfer(
            val id: Int,
            val listener: EntityPlayerMP,
            val trackKey: String,
            val bytes: ByteArray,
            var next: Int = 0,
        )

        private val transfers = ConcurrentHashMap<Pair<UUID, Int>, Transfer>()
        private val cache = LinkedHashMap<String, ByteArray>(4, 0.75f, true)

        fun start(listener: EntityPlayerMP, request: RelayRequest) {
            if (!ServerConfig.relayEnabled) {
                error(listener, request.transferId, "服务器未启用 L3 中转")
                return
            }
            if (transfers.size >= ServerConfig.relayMaxListeners) {
                error(listener, request.transferId, "服务器中转人数已满")
                return
            }
            val listenerState = states[listener.uniqueID] ?: return
            val group = MusicGroups.groupOf(listener.uniqueID)
            val host = visibleHosts(listener, listenerState, group)
                .map { it.first }
                .firstOrNull { it.now.track?.key == request.trackKey }
            if (host == null) {
                error(listener, request.transferId, "该曲目不在可共享范围内")
                return
            }
            val track = host.now.track
            val url = request.url?.takeIf { it.isNotBlank() } ?: host.now.url
            if (url.isNullOrBlank()) {
                error(listener, request.transferId, "主机没有可用的共享地址")
                return
            }
            if (!NearbyMedia.isAllowedRelayHost(url, ServerConfig.relayAllowLocal)) {
                error(listener, request.transferId, "共享地址不在允许的域名内")
                return
            }
            val cached = synchronized(cache) { cache[request.trackKey] }
            if (cached != null) {
                accept(listener, request.transferId, request.trackKey, cached)
                return
            }
            val source = MusicSource.values().firstOrNull { it.key == track?.source } ?: MusicSource.NETEASE
            val maxBytes = ServerConfig.relayMaxMb * 1024 * 1024
            Thread({
                val bytes = NearbyMedia.download(url, NearbyMedia.referer(source), maxBytes)
                if (bytes == null || bytes.isEmpty()) {
                    error(listener, request.transferId, "服务器无法获取该音频地址")
                    return@Thread
                }
                synchronized(cache) {
                    cache[request.trackKey] = bytes
                    while (cache.size > 2) cache.remove(cache.keys.first())
                }
                accept(listener, request.transferId, request.trackKey, bytes)
            }, "Dango-RelayFetch").apply { isDaemon = true }.start()
        }

        private fun accept(listener: EntityPlayerMP, id: Int, trackKey: String, bytes: ByteArray) {
            transfers[listener.uniqueID to id] = Transfer(id, listener, trackKey, bytes)
            DangoLogger.info("DangoMusic relay accepted: bytes={} listeners={}", bytes.size, transfers.size)
        }

        fun tick(players: List<EntityPlayerMP>) {
            if (transfers.isEmpty()) return
            val online = players.mapTo(HashSet()) { it.uniqueID }
            val iterator = transfers.values.iterator()
            while (iterator.hasNext()) {
                val transfer = iterator.next()
                if (transfer.listener.uniqueID !in online) {
                    iterator.remove()
                    continue
                }
                repeat(ServerConfig.relayChunksPerTick) {
                    if (transfer.next >= transfer.bytes.size) return@repeat
                    val end = min(transfer.next + NearbyProtocol.RELAY_CHUNK_BYTES, transfer.bytes.size)
                    val chunk = Base64.getEncoder().encodeToString(transfer.bytes.copyOfRange(transfer.next, end))
                    NetworkBootstrap.sendTo(
                        transfer.listener, NearbyProtocol.S2C.RELAY_DATA,
                        RelayChunk(transfer.id, transfer.next, transfer.bytes.size, chunk)
                    )
                    transfer.next = end
                }
                if (transfer.next >= transfer.bytes.size) iterator.remove()
            }
        }

        fun cancel(listener: UUID, id: Int) {
            transfers.remove(listener to id)
        }

        fun cancelPlayer(listener: UUID) {
            transfers.keys.removeIf { it.first == listener }
        }

        private fun error(listener: EntityPlayerMP, id: Int, text: String) {
            NetworkBootstrap.sendTo(listener, NearbyProtocol.S2C.RELAY_ERROR, RelayError(id, text))
        }
    }
}
