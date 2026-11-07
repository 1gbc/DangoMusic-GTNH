package me.sorihiko.dangomusicgtnh.network

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.network.FMLNetworkEvent
import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.Tags
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule
import me.sorihiko.dangomusicgtnh.module.impls.music.NearbyModule
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
    @Volatile private var shareUrl: String? = null
    @Volatile private var shareUrlKey: String? = null

    private val lyricsCache = ConcurrentHashMap<String, List<SharedLyricLine>>()
    private val inbox = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val pendingRelays = ConcurrentHashMap<Int, PendingRelay>()
    private val relayIds = AtomicInteger(1)
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
        lyricsCache.clear()
        inbox.clear()
        pendingRelays.clear()
        ticks = 0
        helloAttempts = 0
        prefsSent = null
        lastNowKey = null
        lastLyricsKey = null
        shareUrl = null
        shareUrlKey = null
        resolvingUrlKey = null
        NearbyAudioPlayer.stop()
    }

    @SubscribeEvent
    fun onDisconnected(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        connected = false
        welcome = null
        available = false
        hosts = emptyList()
        group = null
        lyricsCache.clear()
        inbox.clear()
        pendingRelays.clear()
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
            NearbyAudioPlayer.update(null, 1f)
            return
        }
        val module = module() ?: return
        syncPrefs(module)
        sendNowPlaying(module)
        resolveShareUrl(module)
        sendLyrics(module)
        val target = activeHost()
        NearbyAudioPlayer.update(target, gainFor(target))
    }

    fun activeHost(): RemoteHost? {
        val g = group
        return if (g != null && !g.idle) {
            hosts.firstOrNull { it.uuid == g.hostUuid } ?: hosts.firstOrNull { it.inGroup }
        } else {
            hosts.firstOrNull()
        }
    }

    fun lyricsFor(trackKey: String): List<SharedLyricLine>? = lyricsCache[trackKey]

    fun radius(): Int = welcome?.radius ?: 32

    fun isAvailable(): Boolean = available

    fun stopAll() {
        if (connected) {
            NetworkBootstrap.sendToServer(NearbyProtocol.C2S.PREFS, Prefs())
            NetworkBootstrap.sendToServer(NearbyProtocol.C2S.NOW_PLAYING, NowPlayingState())
        }
        prefsSent = null
        lastNowKey = null
        lastLyricsKey = null
        shareUrl = null
        shareUrlKey = null
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
                    prefsSent = null
                    lastNowKey = null
                    lastLyricsKey = null
                }
                NearbyProtocol.S2C.NEARBY -> {
                    val data = NetworkBootstrap.gson.fromJson(json, NearbyUpdate::class.java) ?: return
                    val now = System.currentTimeMillis()
                    hosts = data.hosts.map { it.copy(receivedAtMs = now) }
                    group = data.group
                }
                NearbyProtocol.S2C.LYRICS -> {
                    val data = NetworkBootstrap.gson.fromJson(json, LyricsData::class.java) ?: return
                    if (data.trackKey.isNotEmpty() && data.lines.isNotEmpty()) {
                        lyricsCache[data.trackKey] = data.lines
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
        val prefs = Prefs(
            shareMusic = module.shareMusic.get() && module.enabled,
            hearNearby = module.hearNearby.get(),
            shareLyrics = module.shareLyrics.get() && module.enabled,
            seeLyrics = module.seeLyrics.get(),
            hearGroup = module.hearGroup.get(),
            shareGroup = module.shareGroup.get() && module.enabled,
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
        val track = MusicPlayer.current
        val sharing = module.enabled && track != null &&
            (module.shareMusic.get() || (module.shareGroup.get() && isGroupHost()))
        val key = if (sharing) track!!.key else ""
        if (key == lastNowKey && ticks % 20 != 0) return
        val payload = if (sharing) {
            NowPlayingState(
                track = SharedTrack.from(track!!),
                positionMs = MusicPlayer.positionMs.coerceAtLeast(0L),
                playing = MusicPlayer.state == MusicPlayer.State.PLAYING,
                url = shareUrl?.takeIf { shareUrlKey == track.key },
            )
        } else {
            NowPlayingState()
        }
        NetworkBootstrap.sendToServer(NearbyProtocol.C2S.NOW_PLAYING, payload)
        lastNowKey = key
    }

    private fun resolveShareUrl(module: NearbyModule) {
        if (!module.enabled || (!module.shareMusic.get() && !(module.shareGroup.get() && isGroupHost()))) {
            shareUrl = null
            shareUrlKey = null
            return
        }
        val track = MusicPlayer.current ?: return
        if (shareUrlKey == track.key || resolvingUrlKey == track.key) return
        resolvingUrlKey = track.key
        Thread({
            try {
                val result = MusicSources.api(track.source).sharingUrl(track, module.shareQuality.value)
                if (MusicPlayer.current?.key != track.key) return@Thread
                if (result != null && !result.trialOnly && result.url.isNotBlank()) {
                    shareUrl = result.url
                    shareUrlKey = track.key
                    DangoLogger.info("DangoMusic share URL ready: source={}", track.source.key)
                } else {
                    shareUrl = null
                    shareUrlKey = track.key
                    DangoLogger.info("DangoMusic share URL unavailable: source={}", track.source.key)
                }
            } catch (e: Throwable) {
                if (MusicPlayer.current?.key == track.key) {
                    shareUrl = null
                    shareUrlKey = track.key
                    DangoLogger.warn("DangoMusic share URL failed: {}", e.javaClass.simpleName)
                }
            } finally {
                if (resolvingUrlKey == track.key) resolvingUrlKey = null
            }
        }, "Dango-ShareUrl").apply { isDaemon = true }.start()
    }

    private fun sendLyrics(module: NearbyModule) {
        val track = MusicPlayer.current
        if (!module.shareLyrics.get() || !module.enabled || track == null) {
            if (lastLyricsKey != null) {
                NetworkBootstrap.sendToServer(NearbyProtocol.C2S.LYRICS, LyricsData())
                lastLyricsKey = null
            }
            return
        }
        if (lastLyricsKey == track.key) return
        val lines = try {
            val lyricsModule = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
            lyricsModule.playerLyrics(track)
            lyricsModule.shareLines(track)
        } catch (_: Exception) {
            null
        } ?: return
        NetworkBootstrap.sendToServer(NearbyProtocol.C2S.LYRICS, LyricsData(track.key, lines))
        lastLyricsKey = track.key
    }

    private fun isGroupHost(): Boolean =
        group?.host == DangoClient.platform.playerName

    private fun gainFor(host: RemoteHost?): Float {
        host ?: return 0f
        if (host.inGroup) return 1f
        val player = DangoClient.platform.player ?: return 0.5f
        val dx = host.x - player.eyeX
        val dy = host.y - player.eyeY
        val dz = host.z - player.eyeZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val radius = radius().toDouble().coerceAtLeast(1.0)
        return (1.0 - distance / radius).coerceIn(0.15, 1.0).toFloat()
    }

    private fun requestRelay(trackKey: String, url: String?, timeoutMs: Long): ByteArray? {
        if (!available || welcome?.relay != true) return null
        val id = relayIds.getAndIncrement()
        val queue = ArrayBlockingQueue<RelayResult>(1)
        pendingRelays[id] = PendingRelay(queue, ByteArrayOutputStream())
        NetworkBootstrap.sendToServer(
            NearbyProtocol.C2S.RELAY_REQUEST,
            RelayRequest(id, trackKey, url, 1)
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
                    RelayRequest(id, trackKey, null, 1)
                )
            }
        }
    }
}
