package me.sorihiko.dangomusicgtnh.network

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.network.FMLNetworkEvent
import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.Tags
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.musicui.Library
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule
import me.sorihiko.dangomusicgtnh.module.impls.music.NearbyModule
import me.sorihiko.dangomusicgtnh.multiplayer.GroupAction
import me.sorihiko.dangomusicgtnh.multiplayer.GroupUi
import me.sorihiko.dangomusicgtnh.multiplayer.GroupView
import me.sorihiko.dangomusicgtnh.multiplayer.Hello
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
import me.sorihiko.dangomusicgtnh.multiplayer.SharedTrack
import me.sorihiko.dangomusicgtnh.multiplayer.Welcome
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.music.MusicSources
import me.sorihiko.dangomusicgtnh.music.NearbyAudioPlayer
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Client half of the optional server component.
 *
 * It only becomes active after the server answers Hello. A server without the mod never
 * sees a packet it understands; a client without the mod never receives one because the
 * server only sends to players that completed Hello.
 */
object NearbyClient {

    private class RelayResult(val bytes: ByteArray?)

    private class PendingRelay(
        val queue: ArrayBlockingQueue<RelayResult>,
        val out: ByteArrayOutputStream,
        var received: Int = 0,
    )

    @Volatile private var connected = false
    @Volatile private var welcome: Welcome? = null
    @Volatile private var available = false
    @Volatile private var hosts: List<RemoteHost> = emptyList()
    @Volatile private var group: GroupView? = null
    @Volatile private var groupUi: GroupUi? = null
    @Volatile private var shareUrl: String? = null
    @Volatile private var shareUrlKey: String? = null
    @Volatile private var shareUrlAtMs = 0L
    /** Group id selected for listening/sharing; empty means no active group. */
    @Volatile private var activeGroupId = ""
    private var pendingGroupSelect: String? = null

    /** 进群 / 选中群组 / 打开收听群组之后，等群组里有人在放歌就让本地让位（等到这个时间为止） */
    private var takeoverUntil = 0L
    /** 本地音乐因为群组接管而暂停、正在让位给群组；PlaybackView 据此把远端当成当前来源 */
    @Volatile
    var localYielded = false
        private set
    private var yieldedAt = 0L
    /** 让位前本地是在播放（而不是本来就暂停着）：离开群组时才需要接着放 */
    private var resumeLocal = false
    private var lastHearGroup = false
    private var pendingGroupSelectAt = 0L

    private val lyricsCache = ConcurrentHashMap<String, LyricsData>()
    private val inbox = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val pendingRelays = ConcurrentHashMap<Int, PendingRelay>()
    private val relayIds = AtomicInteger(1)

    private fun cancelPendingRelays() {
        pendingRelays.values.forEach { it.queue.offer(RelayResult(null)) }
        pendingRelays.clear()
    }
    private var ticks = 0
    private var helloAttempts = 0
    private var prefsSent: Prefs? = null
    private var lastNowKey: String? = null
    private var lastLyricsKey: String? = null
    private var resolvingUrlKey: String? = null

    fun init() {
        NearbyAudioPlayer.relay = object : NearbyAudioPlayer.Relay {
            override fun request(trackKey: String, url: String?, timeoutMs: Long): ByteArray? =
                requestRelay(trackKey, url, timeoutMs)
        }
    }

    @SubscribeEvent
    fun onConnected(event: FMLNetworkEvent.ClientConnectedToServerEvent) {
        connected = true
        welcome = null
        available = false
        hosts = emptyList()
        group = null
        groupUi = null
        lyricsCache.clear()
        inbox.clear()
        cancelPendingRelays()
        ticks = 0
        helloAttempts = 0
        prefsSent = null
        lastNowKey = null
        lastLyricsKey = null
        shareUrl = null
        shareUrlKey = null
        shareUrlAtMs = 0L
        resolvingUrlKey = null
        pendingGroupSelect = null
        // Keep activeGroupId across a reconnect: the server persists groups and
        // Prefs will re-select it if membership still exists.
        NearbyAudioPlayer.stop()
    }

    @SubscribeEvent
    fun onDisconnected(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        connected = false
        releaseLocal(resume = false)
        welcome = null
        available = false
        hosts = emptyList()
        group = null
        groupUi = null
        lyricsCache.clear()
        inbox.clear()
        cancelPendingRelays()
        shareUrl = null
        shareUrlKey = null
        shareUrlAtMs = 0L
        pendingGroupSelect = null
        NearbyAudioPlayer.stop()
    }

    fun handle(type: Int, json: String) {
        inbox.add(type to json)
    }

    /** Called from the client tick (main thread). */
    fun tick() {
        if (!connected) return
        while (true) {
            val (type, json) = inbox.poll() ?: break
            handleNow(type, json)
        }
        ticks++
        if (welcome == null) {
            if (helloAttempts < 5 && ticks % 40 == 0) {
                helloAttempts++
                NetworkBootstrap.sendToServer(
                    NearbyProtocol.C2S.HELLO,
                    Hello(NearbyProtocol.VERSION, Tags.VERSION)
                )
            }
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        if (!available) {
            // The server turned the component off (or speaks another protocol):
            // drop stale hosts/groups so the UI does not keep showing them.
            hosts = emptyList()
            group = null
            groupUi = null
            releaseLocal(resume = true)
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        val module = module() ?: run {
            releaseLocal(resume = true)
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        syncPrefs(module)
        if (!module.enabled) {
            releaseLocal(resume = true)
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        sendNowPlaying(module)
        resolveShareUrl(module)
        sendLyrics(module)
        // The player-bar stop button turns both listening toggles off. Stop here
        // immediately instead of waiting for the server to drop the host, otherwise
        // the next tick could restart it for one network round trip.
        val hearGroupNow = module.hearGroup.get()
        if (hearGroupNow != lastHearGroup) {
            lastHearGroup = hearGroupNow
            if (hearGroupNow) {
                if (activeGroupId.isNotEmpty()) requestTakeover()
            } else {
                releaseLocal(resume = true)
            }
        }
        if (!module.hearNearby.get() && !module.hearGroup.get()) {
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        val target = activeHost()
        applyTakeover(module, target)
        NearbyAudioPlayer.volume = MusicPlayer.volume
        NearbyAudioPlayer.update(target, gainFor(target), PlaybackView.localPresent())
    }

    fun activeHost(): RemoteHost? {
        val g = group
        return if (g != null && !g.idle) {
            hosts.firstOrNull { it.uuid == g.speakerUuid }
                ?: hosts.firstOrNull { it.inGroup }
                ?: hosts.firstOrNull()
        } else {
            hosts.firstOrNull()
        }
    }

    fun lyricsFor(trackKey: String): List<SharedLyricLine>? = lyricsCache[trackKey]?.lines

    fun lyricsData(trackKey: String): LyricsData? = lyricsCache[trackKey]

    fun groupUi(): GroupUi? = groupUi

    fun groupAction(action: String, name: String = "", target: String = "") {
        if (!available) return
        if (action == "create" || action == "join" || action == "accept") {
            pendingGroupSelect = name
            pendingGroupSelectAt = System.currentTimeMillis()
        }
        if (action == "leave" && (name.isBlank() || name == activeGroupId)) {
            activeGroupId = ""
            pendingGroupSelect = null
            releaseLocal(resume = true)
        }
        NetworkBootstrap.sendToServer(NearbyProtocol.C2S.GROUP_ACTION, GroupAction(action, name, target))
    }

    /** Select the group heard/shared to; empty deselects. */
    fun selectGroup(groupId: String) {
        val changed = groupId != activeGroupId
        activeGroupId = groupId
        pendingGroupSelect = null
        if (groupId.isNotEmpty()) {
            module()?.hearGroup?.set(true)
            if (changed) requestTakeover()
        } else {
            releaseLocal(resume = true)
        }
        prefsSent = null
    }

    private fun requestTakeover() {
        takeoverUntil = System.currentTimeMillis() + 6_000L
    }

    /**
     * 群组里已经有别人在共享：暂停本地音乐，让 NearbyAudioPlayer 接上群组当前的歌和进度。
     * 本地本来就暂停着也一样让位（否则暂停的本地会一直压着群组）
     */
    private fun applyTakeover(module: NearbyModule, target: RemoteHost?) {
        val now = System.currentTimeMillis()
        if (takeoverUntil != 0L) {
            if (!module.groupTakeover.get() || !module.hearGroup.get()) {
                takeoverUntil = 0L
            } else if (target != null && target.inGroup && target.track != null && isInGroup()) {
                when (MusicPlayer.state) {
                    MusicPlayer.State.PLAYING -> {
                        takeoverUntil = 0L
                        MusicPlayer.togglePause()
                        localYielded = true
                        resumeLocal = true
                        yieldedAt = now
                    }
                    MusicPlayer.State.PAUSED -> {
                        takeoverUntil = 0L
                        if (MusicPlayer.current != null && !localYielded) {
                            localYielded = true
                            resumeLocal = false
                            yieldedAt = now
                        }
                    }
                    // 本地正在加载 / 切歌：等它开始播放再暂停，窗口到期为止
                    MusicPlayer.State.LOADING -> if (now > takeoverUntil) takeoverUntil = 0L
                    else -> takeoverUntil = 0L
                }
            } else if (now > takeoverUntil) {
                // 群组里没人在放（idle）：没有可切换的歌，本地照常
                takeoverUntil = 0L
            }
        }
        if (localYielded) {
            val state = MusicPlayer.state
            // 用户自己又点了播放 / 换了歌（暂停淡出期间状态还是 PLAYING，留 2 秒余量），或者本地已经停了
            if ((state == MusicPlayer.State.PLAYING && now - yieldedAt > 2_000L) ||
                state == MusicPlayer.State.STOPPED || MusicPlayer.current == null
            ) {
                localYielded = false
                resumeLocal = false
            }
        }
    }

    /** 离开群组 / 取消选中 / 关掉收听群组：结束让位，按设置决定要不要接着放本地音乐 */
    private fun releaseLocal(resume: Boolean) {
        takeoverUntil = 0L
        if (!localYielded) return
        val wasPlaying = resumeLocal
        localYielded = false
        resumeLocal = false
        if (resume && wasPlaying && module()?.resumeAfterGroup?.get() == true &&
            MusicPlayer.state == MusicPlayer.State.PAUSED && MusicPlayer.current != null
        ) {
            NearbyAudioPlayer.stop()
            MusicPlayer.togglePause()
        }
    }

    fun selectedGroupId(): String = activeGroupId

    fun radius(): Int = welcome?.radius ?: 32

    fun isAvailable(): Boolean = available

    /**
     * True when the listener did not opt into the host's lyrics (or the host does
     * not share them). In that case the client fetches and renders lyrics itself
     * with the player's normal single-player lyric settings.
     */
    fun shouldUseLocalLyrics(): Boolean {
        val host = activeHost() ?: return false
        val module = module() ?: return false
        if (!module.enabled) return false
        val welcome = welcome
        if (welcome != null && !welcome.allowLyrics) return true
        if (!module.seeLyrics.get() || !host.shareLyrics) return true
        // 主机头顶的投影在这里看不到（别的维度、离得太远、一直没收到主机的歌词）：
        // 按同步过来的曲目自己拉歌词，用同步进度对齐，画在自己面前
        return !hostProjectionVisible(host)
    }

    private var projectionFar = false
    private var lyricsWaitKey: String? = null
    private var lyricsWaitSince = 0L

    /**
     * 主机的歌词投影能不能在本机看清：同一维度、离投影不太远（24 格外改用本地，20 格内切回，
     * 中间留一段免得来回切）、已经收到这首歌的歌词（切歌后最多等 3 秒）
     */
    private fun hostProjectionVisible(host: RemoteHost): Boolean {
        val track = host.track ?: return false
        if (host.dimension != DangoClient.platform.dimension) return false
        val now = System.currentTimeMillis()
        val waitKey = host.uuid + "|" + track.key
        if (lyricsWaitKey != waitKey) {
            if (lyricsWaitKey?.substringBefore('|') != host.uuid) projectionFar = false
            lyricsWaitKey = waitKey
            lyricsWaitSince = now
        }
        if (lyricsCache[track.key]?.lines.isNullOrEmpty() && now - lyricsWaitSince > 3_000L) return false
        val local = DangoClient.platform.player ?: return true
        val anchor = host.anchor
        val dx = (anchor?.x ?: host.x) - local.eyeX
        val dy = (anchor?.y ?: (host.y + 1.65)) - local.eyeY
        val dz = (anchor?.z ?: host.z) - local.eyeZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        projectionFar = if (projectionFar) distance > 20.0 else distance > 24.0
        return !projectionFar
    }

    /**
     * The remote stop button is a listening preference, not a one-shot mute:
     * turn both remote sources off so the server cannot immediately re-add the
     * same host. The player can re-enable either toggle in Multiplayer settings.
     */
    fun stopForUser() {
        val module = module()
        val wasYielded = localYielded
        releaseLocal(resume = false)
        if (wasYielded) MusicPlayer.stop()
        lastHearGroup = false
        if (module != null) {
            module.hearNearby.set(false)
            module.hearGroup.set(false)
        }
        // Stop is also a group deselection: the next explicit group selection
        // re-enables listening. Keep activeGroupId empty so no queued update
        // can make the old group current again.
        activeGroupId = ""
        pendingGroupSelect = null
        prefsSent = null
        NearbyAudioPlayer.stop()
    }

    fun stopAll() {
        releaseLocal(resume = false)
        if (connected) {
            NetworkBootstrap.sendToServer(NearbyProtocol.C2S.PREFS, Prefs())
            NetworkBootstrap.sendToServer(NearbyProtocol.C2S.NOW_PLAYING, NowPlayingState())
        }
        prefsSent = null
        lastNowKey = null
        lastLyricsKey = null
        shareUrl = null
        shareUrlKey = null
        shareUrlAtMs = 0L
        activeGroupId = ""
        pendingGroupSelect = null
        hosts = emptyList()
        group = null
        groupUi = null
        lyricsCache.clear()
        cancelPendingRelays()
        NearbyAudioPlayer.stop()
    }

    private fun handleNow(type: Int, json: String) {
        try {
            when (type) {
                NearbyProtocol.S2C.WELCOME -> {
                    val data = NetworkBootstrap.gson.fromJson(json, Welcome::class.java) ?: return
                    welcome = data
                    available = data.enabled && data.protocol == NearbyProtocol.VERSION
                    if (data.protocol != NearbyProtocol.VERSION) {
                        DangoLogger.warn("DangoMusic server protocol {} does not match {}", data.protocol, NearbyProtocol.VERSION)
                    }
                    if (!available) {
                        hosts = emptyList()
                        group = null
                        groupUi = null
                        lyricsCache.clear()
                        cancelPendingRelays()
                    }
                    prefsSent = null
                    lastNowKey = null
                    lastLyricsKey = null
                }
                NearbyProtocol.S2C.NEARBY -> {
                    val data = NetworkBootstrap.gson.fromJson(json, NearbyUpdate::class.java) ?: return
                    val now = System.currentTimeMillis()
                    val previousInvites = groupUi?.invites?.map { it.groupId + "|" + it.from }.orEmpty()
                    hosts = data.hosts.map { it.copy(receivedAtMs = now) }
                    group = data.group
                    groupUi = data.groupUi
                    val joined = data.groupUi?.groupsJoined.orEmpty()
                    if (activeGroupId.isNotEmpty() && joined.none { it.id == activeGroupId }) {
                        activeGroupId = ""
                        releaseLocal(resume = true)
                    }
                    val pending = pendingGroupSelect
                    if (pending != null) {
                        val match = joined.firstOrNull {
                            it.id == pending || it.name.equals(pending.trim(), ignoreCase = true)
                        }
                        if (match != null) {
                            activeGroupId = match.id
                            pendingGroupSelect = null
                            prefsSent = null
                            requestTakeover()
                        } else if (now - pendingGroupSelectAt > 5_000L) {
                            pendingGroupSelect = null
                        }
                    }
                    data.groupUi?.invites?.firstOrNull { it.groupId + "|" + it.from !in previousInvites }?.let {
                        Library.showTemporaryStatus(I18n.format("Group invite from %s", it.from), 5_000)
                    }
                }
                NearbyProtocol.S2C.LYRICS -> {
                    val data = NetworkBootstrap.gson.fromJson(json, LyricsData::class.java) ?: return
                    if (data.trackKey.isNotEmpty() && data.lines.isNotEmpty()) {
                        lyricsCache[data.trackKey] = data
                        // A long session must not accumulate lyrics forever; the UI only
                        // needs the current/near-current tracks.
                        while (lyricsCache.size > 12) {
                            val oldest = lyricsCache.keys.firstOrNull { it != data.trackKey } ?: break
                            lyricsCache.remove(oldest)
                        }
                    }
                }
                NearbyProtocol.S2C.RELAY_DATA -> {
                    val chunk = NetworkBootstrap.gson.fromJson(json, RelayChunk::class.java) ?: return
                    val pending = pendingRelays[chunk.transferId] ?: return
                    val bytes = try {
                        Base64.getDecoder().decode(chunk.data)
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: return
                    synchronized(pending) {
                        if (pending.received + bytes.size > NearbyProtocol.MAX_RELAY_BYTES) {
                            pendingRelays.remove(chunk.transferId)
                            pending.queue.offer(RelayResult(null))
                            return
                        }
                        pending.received += bytes.size
                        pending.out.write(bytes)
                        if (chunk.total > 0 && pending.received >= chunk.total) {
                            pendingRelays.remove(chunk.transferId)
                            pending.queue.offer(RelayResult(pending.out.toByteArray()))
                        }
                    }
                }
                NearbyProtocol.S2C.RELAY_ERROR -> {
                    val error = NetworkBootstrap.gson.fromJson(json, RelayError::class.java) ?: return
                    pendingRelays.remove(error.transferId)?.queue?.offer(RelayResult(null))
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("DangoMusic client packet {} rejected: {}", type, e.javaClass.simpleName)
        }
    }

    private fun module(): NearbyModule? = try {
        DangoMusic.instance.moduleManager.get(NearbyModule::class.java)
    } catch (_: Exception) {
        null
    }

    private fun syncPrefs(module: NearbyModule) {
        // Disabling the module must silence both directions, not just sharing.
        val on = module.enabled
        val prefs = Prefs(
            shareMusic = on && module.shareMusic.get(),
            hearNearby = on && module.hearNearby.get(),
            shareLyrics = on && module.shareLyrics.get(),
            seeLyrics = on && module.seeLyrics.get(),
            hearGroup = on && module.hearGroup.get(),
            shareGroup = on && module.shareGroup.get(),
            activeGroup = if (on) activeGroupId else "",
        )
        if (prefs != prefsSent) {
            NetworkBootstrap.sendToServer(NearbyProtocol.C2S.PREFS, prefs)
            prefsSent = prefs
            if (!prefs.shareMusic) {
                NetworkBootstrap.sendToServer(NearbyProtocol.C2S.NOW_PLAYING, NowPlayingState())
                lastNowKey = null
            }
            if (!prefs.shareLyrics) {
                lastLyricsKey = null
            }
        }
    }

    private fun sendNowPlaying(module: NearbyModule) {
        val track = MusicPlayer.current?.takeUnless { it.source == me.sorihiko.dangomusicgtnh.music.MusicSource.APPLE }
        val sharing = module.enabled && track != null &&
            (module.shareMusic.get() || (module.shareGroup.get() && isInGroup()))
        val key = if (sharing) track!!.key else ""
        if (key == lastNowKey && ticks % 20 != 0) return
        val payload = if (sharing) {
            val anchor = try {
                DangoMusic.instance.moduleManager.get(LyricsModule::class.java).shareAnchor()
            } catch (_: Exception) {
                null
            }
            NowPlayingState(
                track = SharedTrack.from(track!!),
                positionMs = MusicPlayer.positionMs.coerceAtLeast(0L),
                playing = MusicPlayer.state == MusicPlayer.State.PLAYING,
                url = shareUrl?.takeIf { shareUrlKey == track.key },
                anchor = anchor,
            )
        } else {
            NowPlayingState()
        }
        NetworkBootstrap.sendToServer(NearbyProtocol.C2S.NOW_PLAYING, payload)
        lastNowKey = key
    }

    private fun resolveShareUrl(module: NearbyModule) {
        if (!module.enabled || (!module.shareMusic.get() && !(module.shareGroup.get() && isInGroup()))) {
            shareUrl = null
            shareUrlKey = null
            shareUrlAtMs = 0L
            return
        }
        val track = MusicPlayer.current?.takeUnless { it.source == me.sorihiko.dangomusicgtnh.music.MusicSource.APPLE } ?: return
        val now = System.currentTimeMillis()
        // Sharing URLs are short-lived; refresh them periodically instead of reusing
        // the URL forever when the same track is replayed.
        val fresh = shareUrlKey == track.key && now - shareUrlAtMs < 5 * 60_000L
        if (fresh || resolvingUrlKey == track.key) return
        resolvingUrlKey = track.key
        Thread({
            try {
                val result = MusicSources.api(track.source).sharingUrl(track, module.shareQuality.value)
                if (MusicPlayer.current?.key != track.key) return@Thread
                if (result != null && !result.trialOnly && result.url.isNotBlank()) {
                    shareUrl = result.url
                    shareUrlKey = track.key
                    shareUrlAtMs = System.currentTimeMillis()
                    DangoLogger.info("DangoMusic share URL ready: source={}", track.source.key)
                } else {
                    shareUrl = null
                    shareUrlKey = track.key
                    shareUrlAtMs = System.currentTimeMillis()
                    DangoLogger.info("DangoMusic share URL unavailable: source={}", track.source.key)
                }
            } catch (e: Throwable) {
                if (MusicPlayer.current?.key == track.key) {
                    shareUrl = null
                    shareUrlKey = track.key
                    shareUrlAtMs = System.currentTimeMillis()
                    DangoLogger.warn("DangoMusic share URL failed: {}", e.javaClass.simpleName)
                }
            } finally {
                if (resolvingUrlKey == track.key) resolvingUrlKey = null
            }
        }, "Dango-ShareUrl").apply { isDaemon = true }.start()
    }

    private fun sendLyrics(module: NearbyModule) {
        val track = MusicPlayer.current?.takeUnless { it.source == me.sorihiko.dangomusicgtnh.music.MusicSource.APPLE }
        if (!module.shareLyrics.get() || !module.enabled || track == null) {
            if (lastLyricsKey != null) {
                NetworkBootstrap.sendToServer(NearbyProtocol.C2S.LYRICS, LyricsData())
                lastLyricsKey = null
            }
            return
        }
        if (lastLyricsKey == track.key) return
        val payload = try {
            val lyricsModule = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
            lyricsModule.playerLyrics(track)
            var lines = lyricsModule.shareLines(track) ?: return
            val style = lyricsModule.shareStyle()
            // The full 300-line payload can exceed one MC custom payload; keep the
            // most useful leading lines and halve until it fits.
            while (lines.size > 1 &&
                NetworkBootstrap.gson.toJson(LyricsData(track.key, lines, style)).toByteArray(Charsets.UTF_8).size > NearbyProtocol.MAX_PACKET_BYTES
            ) {
                lines = lines.subList(0, lines.size / 2)
            }
            LyricsData(track.key, lines, style)
        } catch (_: Exception) {
            null
        } ?: return
        NetworkBootstrap.sendToServer(NearbyProtocol.C2S.LYRICS, payload)
        lastLyricsKey = track.key
    }

    private fun isInGroup(): Boolean = activeGroupId.isNotEmpty() && group != null

    private fun gainFor(host: RemoteHost?): Float {
        host ?: return 0f
        // Nearby playback follows the player volume but is capped well below 100%,
        // even when standing next to the host. Group playback uses the same cap.
        val cap = 0.5f
        if (host.inGroup) return cap
        val player = DangoClient.platform.player ?: return cap
        val dx = host.x - player.eyeX
        val dy = host.y - player.eyeY
        val dz = host.z - player.eyeZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val radius = radius().toDouble().coerceAtLeast(1.0)
        return ((1.0 - distance / radius).coerceIn(0.05, 1.0) * cap).toFloat()
    }

    private fun requestRelay(trackKey: String, url: String?, timeoutMs: Long): ByteArray? {
        if (!available || welcome?.relay != true) return null
        val id = relayIds.getAndIncrement()
        val queue = ArrayBlockingQueue<RelayResult>(1)
        pendingRelays[id] = PendingRelay(queue, ByteArrayOutputStream())
        NetworkBootstrap.sendToServer(
            NearbyProtocol.C2S.RELAY_REQUEST,
            RelayRequest(id, trackKey, url)
        )
        return try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)?.bytes
        } catch (_: InterruptedException) {
            null
        } finally {
            pendingRelays.remove(id)
            if (connected) {
                NetworkBootstrap.sendToServer(
                    NearbyProtocol.C2S.RELAY_CANCEL,
                    RelayRequest(id, trackKey, null)
                )
            }
        }
    }
}
