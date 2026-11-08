package me.sorihiko.dangomusicgtnh.network

import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.GroupAction
import me.sorihiko.dangomusicgtnh.multiplayer.GroupInvite
import me.sorihiko.dangomusicgtnh.multiplayer.GroupPlayer
import me.sorihiko.dangomusicgtnh.multiplayer.GroupSummary
import me.sorihiko.dangomusicgtnh.multiplayer.GroupUi
import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsData
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsStyle
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
import me.sorihiko.dangomusicgtnh.multiplayer.sanitized
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NearbyMedia
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ChatComponentText
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
        /** When this player last started a new track; used to pick the group speaker. */
        @Volatile var playbackStartedAt = 0L
        /**
         * Track key this member was already playing when it joined / selected its
         * group. While set, the member ranks below every other sharer, so a joiner
         * follows the group instead of taking it over. Cleared on a new track or
         * when group sharing is switched on again.
         */
        @Volatile var followGroupKey: String? = null
        /**
         * Until this time a member that just joined still reports its old track for
         * the first time (clients only share with a group once membership arrives),
         * so that first track is also followed rather than treated as a new one.
         */
        @Volatile var followPendingUntil = 0L
        @Volatile var lyricsTrack = ""
        @Volatile var lyrics: List<SharedLyricLine>? = null
        @Volatile var lyricsStyle: LyricsStyle? = null
        @Volatile var lyricsVersion = 0
        @Volatile var capable = false
        @Volatile var lastActionAt = 0L

        /** Host UUID -> lyrics version already sent to this listener. */
        val sentLyrics = ConcurrentHashMap<UUID, Int>()
    }

    private val states = ConcurrentHashMap<UUID, PlayerState>()
    private val lastSent = ConcurrentHashMap<UUID, String>()
    private val invites = ConcurrentHashMap<UUID, MutableList<GroupInvite>>()
    private val groupActions = java.util.concurrent.ConcurrentLinkedQueue<Pair<EntityPlayerMP, GroupAction>>()
    private var ticks = 0

    fun handle(player: EntityPlayerMP, type: Int, json: String) {
        val state = states.computeIfAbsent(player.uniqueID) { PlayerState(it) }
        state.player = player
        // Always answer Hello, even while disabled: the client then knows the server
        // has the component, and /dangomusic reload can enable it without a reconnect.
        if (type == NearbyProtocol.C2S.HELLO) {
            state.capable = true
            MusicGroups.updateName(player.uniqueID, player.displayName)
            sendWelcome(player)
            return
        }
        if (!ServerConfig.enabled) return
        if (type == NearbyProtocol.C2S.GROUP_ACTION || type == NearbyProtocol.C2S.RELAY_REQUEST) {
            val now = System.currentTimeMillis()
            if (now - state.lastActionAt < 100L) return
            state.lastActionAt = now
        }
        try {
            when (type) {
                NearbyProtocol.C2S.PREFS -> {
                    val prefs = NetworkBootstrap.gson.fromJson(json, Prefs::class.java) ?: return
                    val wasSharingGroup = state.prefs.shareGroup
                    val previousGroup = state.prefs.activeGroup
                    state.prefs = prefs
                    if (prefs.activeGroup != previousGroup && prefs.activeGroup.isNotEmpty()) {
                        // Joining / selecting a group follows its current speaker: the
                        // joiner's already-running track must not take the group over.
                        // Starting a new track afterwards still makes them the speaker.
                        state.followGroupKey = state.now.track?.key
                        state.followPendingUntil = System.currentTimeMillis() + 5_000L
                    } else if (!wasSharingGroup && prefs.shareGroup && state.now.track != null) {
                        // Turning group sharing on while already playing should make this
                        // member the current speaker, not wait for the next track change.
                        state.followGroupKey = null
                        state.followPendingUntil = 0L
                        state.playbackStartedAt = System.currentTimeMillis()
                    }
                    if (!prefs.shareLyrics) {
                        state.lyrics = null
                        state.lyricsTrack = ""
                        state.lyricsVersion++
                    }
                    lastSent.remove(player.uniqueID)
                }
                NearbyProtocol.C2S.NOW_PLAYING -> {
                    val now = NetworkBootstrap.gson.fromJson(json, NowPlayingState::class.java) ?: return
                    val previousKey = state.now.track?.key
                    state.now = sanitize(now)
                    state.nowAt = System.currentTimeMillis()
                    val newKey = state.now.track?.key
                    if (newKey != previousKey) {
                        // The joiner's first report after joining is the track it was
                        // already playing: keep following. Any later change is a new track.
                        val firstReport = previousKey == null && newKey != null &&
                            state.nowAt < state.followPendingUntil
                        state.followGroupKey = if (firstReport) newKey else null
                        state.followPendingUntil = 0L
                    }
                    state.playbackStartedAt = when {
                        newKey == null -> 0L
                        newKey != previousKey || state.playbackStartedAt == 0L -> state.nowAt
                        else -> state.playbackStartedAt
                    }
                    // Do not keep projecting the previous song's lines while the new
                    // track's lyrics are still being fetched.
                    if (previousKey != newKey) {
                        state.lyrics = null
                        state.lyricsTrack = ""
                        state.lyricsStyle = null
                        state.lyricsVersion++
                    }
                    lastSent.remove(player.uniqueID)
                }
                NearbyProtocol.C2S.LYRICS -> {
                    val data = NetworkBootstrap.gson.fromJson(json, LyricsData::class.java) ?: return
                    if (!ServerConfig.allowLyrics || !state.prefs.shareLyrics) {
                        state.lyrics = null
                        state.lyricsTrack = ""
                        state.lyricsStyle = null
                    } else {
                        state.lyrics = data.lines.take(NearbyProtocol.MAX_LYRICS_LINES).map {
                            SharedLyricLine(
                                timeMs = it.timeMs.coerceAtLeast(0L),
                                text = it.text.take(NearbyProtocol.MAX_LYRIC_CHARS),
                                translation = it.translation?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { value -> value.isNotBlank() },
                                romanization = it.romanization?.take(NearbyProtocol.MAX_LYRIC_CHARS)?.takeIf { value -> value.isNotBlank() },
                            )
                        }
                        state.lyricsTrack = data.trackKey.take(200)
                        state.lyricsStyle = data.style?.sanitized()
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
                NearbyProtocol.C2S.GROUP_ACTION -> {
                    val action = NetworkBootstrap.gson.fromJson(json, GroupAction::class.java) ?: return
                    groupActions.add(player to action)
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
        while (true) {
            val (player, action) = groupActions.poll() ?: break
            handleGroupAction(player, action)
        }
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
        // Membership is persistent; logging out only makes the member offline.
        // The group remains available to the other members and across restarts.
        val groups = MusicGroups.groupsOf(player.uniqueID)
        states.remove(player.uniqueID)
        lastSent.remove(player.uniqueID)
        invites.remove(player.uniqueID)
        Relay.cancelPlayer(player.uniqueID)
        if (groups.isNotEmpty()) invalidateAll()
    }

    fun isCapable(player: EntityPlayerMP): Boolean = states[player.uniqueID]?.capable == true

    fun groupCreate(player: EntityPlayerMP, name: String): String? {
        if (!isCapable(player)) return "请先安装并启用 DangoMusic 客户端"
        val error = MusicGroups.create(player.uniqueID, player.displayName, name, ServerConfig.maxGroup)
        if (error == null) {
            setActiveGroup(player, MusicGroups.all().firstOrNull {
                it.name.equals(name.trim(), ignoreCase = true)
            }?.id.orEmpty())
            invalidateAll()
        }
        return error
    }

    fun groupJoin(player: EntityPlayerMP, name: String): String? {
        if (!isCapable(player)) return "请先安装并启用 DangoMusic 客户端"
        val error = MusicGroups.join(player.uniqueID, player.displayName, name, ServerConfig.maxGroup)
        if (error == null) {
            setActiveGroup(player, MusicGroups.all().firstOrNull {
                it.name.equals(name.trim(), ignoreCase = true)
            }?.id.orEmpty())
            invalidateAll()
        }
        return error
    }

    fun groupLeave(player: EntityPlayerMP, ref: String? = null): String? {
        val active = states[player.uniqueID]?.prefs?.activeGroup.orEmpty()
        if (ref.isNullOrBlank() && active.isEmpty() && MusicGroups.groupsOf(player.uniqueID).size > 1) {
            return "你有多个群组，请指定群组名或 id"
        }
        val result = MusicGroups.leave(player.uniqueID, ref?.takeIf { it.isNotBlank() } ?: active) ?: return null
        val group = result.group
        if (states[player.uniqueID]?.prefs?.activeGroup == group.id) {
            setActiveGroup(player, "")
        }
        invalidateAll()
        val nextName = result.newHost?.let { group.names[it] ?: it.toString().take(8) }
        return when {
            result.disbanded -> "已解散音乐群组：${group.name}"
            nextName != null -> "已离开音乐群组：${group.name}；房主已转让给 $nextName"
            else -> "已离开音乐群组：${group.name}"
        }
    }

    fun groupInfo(player: EntityPlayerMP, ref: String? = null): String {
        val all = MusicGroups.groupsOf(player.uniqueID)
        if (all.isEmpty()) return "你不在音乐群组中"
        val active = states[player.uniqueID]?.prefs?.activeGroup.orEmpty()
        val selected = if (ref.isNullOrBlank()) all else all.filter {
            it.id == ref || it.name.equals(ref.trim(), ignoreCase = true)
        }
        if (selected.isEmpty()) return "找不到群组：$ref"
        return selected.joinToString("；") { group ->
            val host = groupName(group, group.host)
            val speaker = groupSpeaker(group)?.let { groupName(group, it.uuid) } ?: "无"
            val members = group.members.joinToString(", ") { groupName(group, it) }
            val mark = if (group.id == active) " [当前]" else ""
            "群组：${group.name}$mark · 房主：$host · 正在共享：$speaker · 成员：$members"
        }
    }

    private fun setActiveGroup(player: EntityPlayerMP, groupId: String) {
        val state = states[player.uniqueID] ?: return
        state.prefs = state.prefs.copy(activeGroup = groupId)
        lastSent.remove(player.uniqueID)
    }

    private fun activeGroup(player: UUID, prefs: Prefs): MusicGroup? =
        MusicGroups.groupById(prefs.activeGroup)?.takeIf { player in it.members }

    private fun groupName(group: MusicGroup, uuid: UUID): String =
        states[uuid]?.player?.displayName ?: group.names[uuid] ?: uuid.toString().take(8)

    fun broadcastWelcome() {
        val server = MinecraftServer.getServer() ?: return
        for (player in server.configurationManager.playerEntityList) {
            val state = states[player.uniqueID] ?: continue
            if (state.capable) sendWelcome(player)
        }
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
        val group = activeGroup(listener.uniqueID, listenerState.prefs)
        val visible = visibleHosts(listener, listenerState, group)
        val groupView = groupView(group)
        val hosts = visible.mapNotNull { (state, inGroup) -> remoteHost(state, inGroup) }
        val update = NearbyUpdate(hosts, groupView, groupUi(listener, groupView))
        val json = NetworkBootstrap.gson.toJson(update)
        if (lastSent[listener.uniqueID] != json) {
            lastSent[listener.uniqueID] = json
            NetworkBootstrap.sendJsonTo(listener, NearbyProtocol.S2C.NEARBY, json)
        }

        if (ServerConfig.allowLyrics && listenerState.prefs.seeLyrics && hosts.isNotEmpty()) {
            for ((state, _) in visible) {
                if (!state.prefs.shareLyrics || state.lyrics == null || state.lyricsTrack.isEmpty()) continue
                val version = state.lyricsVersion
                if (listenerState.sentLyrics[state.uuid] == version) continue
                val data = fitLyrics(state.lyricsTrack, state.lyrics!!, state.lyricsStyle)
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
        if (group != null && listenerState.prefs.hearGroup) {
            // Any member can be the group speaker, not only the owner. The most
            // recently started track wins; if nobody is sharing, fall through to
            // the normal nearby list.
            val speaker = groupSpeaker(group)
            if (speaker != null && speaker.uuid != listener.uniqueID) {
                return listOf(speaker to true)
            }
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

    private fun groupSpeaker(group: MusicGroup): PlayerState? =
        pickGroupSpeaker(group)?.also { speaker ->
            // A follower chosen because nobody else shares is now the group's real
            // speaker: later joiners must follow it instead of out-ranking it.
            if (speaker.followGroupKey != null) {
                speaker.followGroupKey = null
                speaker.followPendingUntil = 0L
            }
        }

    private fun pickGroupSpeaker(group: MusicGroup): PlayerState? =
        group.members.asSequence()
            .mapNotNull { states[it] }
            .filter { it.capable && it.prefs.shareGroup && it.prefs.activeGroup == group.id && it.now.track != null }
            // A playing member outranks a paused one; among playing members the
            // most recently started track wins.
            // A member that joined while playing ranks last until it starts a new track.
            .maxWithOrNull(
                compareBy<PlayerState> { it.followGroupKey == null || it.followGroupKey != it.now.track?.key }
                    .thenBy { it.now.playing }
                    .thenBy { it.playbackStartedAt }
            )

    private fun remoteHost(state: PlayerState, inGroup: Boolean): RemoteHost? {
        val player = state.player ?: return null
        val track = state.now.track ?: return null
        val elapsed = if (state.now.playing) (System.currentTimeMillis() - state.nowAt).coerceAtLeast(0L) else 0L
        var position = state.now.positionMs + elapsed
        if (track.durationMs > 0L) position = position.coerceAtMost(track.durationMs)
        // The host sends its wiggle frame once per NowPlaying packet. Advance it
        // to the current server time so every 0.5 s broadcast carries a fresh,
        // coherent frame instead of rewinding the listener's animation.
        val wiggleFps = state.lyricsStyle?.wiggleFps?.coerceIn(1, 24) ?: 8
        val anchor = state.now.anchor?.let {
            if (it.wiggleFrame < 0L) it
            else it.copy(wiggleFrame = it.wiggleFrame + (System.currentTimeMillis() - state.nowAt) * wiggleFps / 1000L)
        }
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
            anchor = anchor,
        )
    }

    private fun groupUi(listener: EntityPlayerMP, view: GroupView?): GroupUi {
        val players = states.values.asSequence()
            .filter { it.capable && it.uuid != listener.uniqueID }
            .mapNotNull { state -> state.player?.let { GroupPlayer(it.displayName, state.uuid.toString()) } }
            .sortedBy { it.name.lowercase() }
            .take(32)
            .toList()
        val joined = MusicGroups.groupsOf(listener.uniqueID)
            .sortedByDescending { it.id == view?.id }
            .take(4)
            .mapNotNull { groupView(it) }
        val groups = MusicGroups.all().asSequence()
            .filter { listener.uniqueID !in it.members }
            .take(8)
            .map { group -> GroupSummary(group.id, group.name, groupName(group, group.host), group.members.size, ServerConfig.maxGroup) }
            .toList()
        val inbox = synchronized(invites) { invites[listener.uniqueID]?.takeLast(5).orEmpty() }
        return GroupUi(view, players, inbox, groups, joined)
    }

    private fun handleGroupAction(player: EntityPlayerMP, action: GroupAction) {
        if (!isCapable(player)) {
            feedback(player, "请先安装并启用 DangoMusic 客户端")
            return
        }
        when (action.action.lowercase()) {
            "create" -> feedback(player, groupCreate(player, action.name) ?: "已创建音乐群组：${action.name.trim()}")
            "join" -> feedback(player, groupJoin(player, action.name) ?: "已加入音乐群组：${action.name.trim()}")
            "leave" -> feedback(player, groupLeave(player, action.name) ?: "你不在音乐群组中")
            "invite" -> invite(player, action.target)
            "accept" -> acceptInvite(player, action.name)
            "decline" -> declineInvite(player, action.name)
            else -> feedback(player, "未知的群组操作")
        }
    }

    private fun invite(player: EntityPlayerMP, targetName: String) {
        val state = states[player.uniqueID] ?: run {
            feedback(player, "请先安装并启用 DangoMusic 客户端")
            return
        }
        val group = activeGroup(player.uniqueID, state.prefs) ?: run {
            feedback(player, "请先在群组页面选择一个群组")
            return
        }
        val target = states.values.firstOrNull {
            it.capable && it.player?.displayName.equals(targetName.trim(), ignoreCase = true)
        }?.player ?: run {
            feedback(player, "找不到已安装模组的在线玩家：$targetName")
            return
        }
        if (target.uniqueID in group.members) {
            feedback(player, "对方已经在该音乐群组中")
            return
        }
        synchronized(invites) {
            val list = invites.computeIfAbsent(target.uniqueID) { mutableListOf() }
            list.removeIf { it.groupId == group.id }
            list += GroupInvite(group.id, group.name, player.displayName)
            while (list.size > 5) list.removeAt(0)
        }
        feedback(player, "已邀请 ${target.displayName} 加入 ${group.name}")
        invalidateAll()
    }

    private fun acceptInvite(player: EntityPlayerMP, groupRef: String) {
        val invite = synchronized(invites) {
            invites[player.uniqueID]?.firstOrNull {
                it.groupId == groupRef || it.groupName.equals(groupRef.trim(), ignoreCase = true)
            }
        } ?: run {
            feedback(player, "没有找到这个群组邀请")
            return
        }
        val error = MusicGroups.join(player.uniqueID, player.displayName, invite.groupName, ServerConfig.maxGroup)
        if (error != null) {
            feedback(player, error)
            return
        }
        synchronized(invites) { invites[player.uniqueID]?.remove(invite) }
        setActiveGroup(player, invite.groupId)
        feedback(player, "已加入音乐群组：${invite.groupName}")
        invalidateAll()
    }

    private fun declineInvite(player: EntityPlayerMP, groupRef: String) {
        synchronized(invites) {
            invites[player.uniqueID]?.removeIf {
                it.groupId == groupRef || it.groupName.equals(groupRef.trim(), ignoreCase = true)
            }
        }
        invalidateAll()
    }

    private fun feedback(player: EntityPlayerMP, text: String) {
        try {
            player.addChatMessage(ChatComponentText(text))
        } catch (e: Exception) {
            // A queued group action can outlive the player's connection.
            DangoLogger.warn("DangoMusic chat feedback failed: {}", e.javaClass.simpleName)
        }
    }

    private fun groupView(group: MusicGroup?): GroupView? {
        group ?: return null
        val speaker = groupSpeaker(group)
        return MusicGroups.view(group, { uuid -> groupName(group, uuid) }, speaker == null, speaker?.uuid)
    }

    private fun fitLyrics(trackKey: String, lines: List<SharedLyricLine>, style: LyricsStyle?): LyricsData {
        var take = lines
        while (take.size > 1) {
            val data = LyricsData(trackKey, take, style)
            if (NetworkBootstrap.gson.toJson(data).toByteArray(Charsets.UTF_8).size <= 24 * 1024) return data
            take = take.subList(0, take.size / 2)
        }
        return LyricsData(trackKey, take, style)
    }

    private fun sanitize(now: NowPlayingState): NowPlayingState {
        val track = now.track?.let {
            it.copy(
                source = it.source.take(16),
                nativeId = it.nativeId.take(128),
                name = it.name.take(NearbyProtocol.MAX_TRACK_NAME),
                artists = it.artists.take(NearbyProtocol.MAX_ARTISTS).map { artist -> artist.take(120) },
                durationMs = it.durationMs.coerceIn(0L, 24L * 60L * 60L * 1000L),
                coverUrl = it.coverUrl.take(NearbyProtocol.MAX_URL)
                    .takeIf { url -> NearbyMedia.isAllowedRelayHost(url, false) }.orEmpty(),
                albumId = it.albumId.take(128),
                mediaId = it.mediaId.take(128),
                albumAudioId = it.albumAudioId.take(128),
            )
        }
        val url = now.url?.takeIf {
            it.isNotBlank() && it.length <= NearbyProtocol.MAX_URL &&
                NearbyMedia.isAllowedRelayHost(it, ServerConfig.relayAllowLocal)
        }
        val duration = track?.durationMs ?: 0L
        var position = now.positionMs.coerceIn(0L, 24L * 60L * 60L * 1000L)
        if (duration > 0L) position = position.coerceAtMost(duration)
        val anchor = now.anchor?.sanitized()
        return NowPlayingState(
            track = track,
            positionMs = position,
            playing = now.playing,
            url = url,
            anchor = anchor,
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
        private val fetching = java.util.concurrent.atomic.AtomicInteger(0)
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
            val group = activeGroup(listener.uniqueID, listenerState.prefs)
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
            if (fetching.incrementAndGet() > ServerConfig.relayMaxListeners) {
                fetching.decrementAndGet()
                error(listener, request.transferId, "服务器中转人数已满")
                return
            }
            Thread({
                try {
                    val bytes = NearbyMedia.download(
                        url, NearbyMedia.referer(source), maxBytes,
                        allowed = { candidate -> NearbyMedia.isAllowedRelayHost(candidate.toString(), ServerConfig.relayAllowLocal) },
                    )
                    if (bytes == null || bytes.isEmpty()) {
                        error(listener, request.transferId, "服务器无法获取该音频地址")
                        return@Thread
                    }
                    synchronized(cache) {
                        cache[request.trackKey] = bytes
                        while (cache.size > 2) cache.remove(cache.keys.first())
                    }
                    accept(listener, request.transferId, request.trackKey, bytes)
                } finally {
                    fetching.decrementAndGet()
                }
            }, "Dango-RelayFetch").apply { isDaemon = true }.start()
        }

        private fun accept(listener: EntityPlayerMP, id: Int, trackKey: String, bytes: ByteArray) {
            transfers[listener.uniqueID to id] = Transfer(id, listener, trackKey, bytes)
            DangoLogger.info("DangoMusic relay accepted: bytes={} listeners={}", bytes.size, transfers.size)
        }

        fun tick(players: List<EntityPlayerMP>) {
            if (transfers.isEmpty()) return
            val online = players.mapTo(HashSet()) { it.uniqueID }
            val finished = ArrayList<Pair<UUID, Int>>()
            for ((key, transfer) in transfers) {
                if (transfer.listener.uniqueID !in online) {
                    finished += key
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
                if (transfer.next >= transfer.bytes.size) finished += key
            }
            finished.forEach { transfers.remove(it) }
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
