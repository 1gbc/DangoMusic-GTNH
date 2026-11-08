package me.sorihiko.dangomusicgtnh.music

import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.multiplayer.RemoteHost
import me.sorihiko.dangomusicgtnh.multiplayer.SharedTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs

/**
 * Plays another player's music locally.
 *
 * This is deliberately separate from [MusicPlayer]: local playback and nearby playback
 * use two Java Sound lines, so the listener can keep their own music at full volume while
 * hearing a nearby host (or mute one of them).
 *
 * Route order: L1 own account -> L2 host URL -> L3 optional server relay.
 */
object NearbyAudioPlayer {

    enum class State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    /** Implemented by the client network layer; blocking, called from the loader thread. */
    interface Relay {
        fun request(trackKey: String, url: String?, timeoutMs: Long): ByteArray?
    }

    @Volatile var relay: Relay? = null

    @Volatile var state = State.IDLE
        private set
    @Volatile var message = ""
        private set
    @Volatile var hostName = ""
        private set
    @Volatile var trackKey = ""
        private set
    @Volatile var route = ""
        private set

    @Volatile var volume: Float = 0.2f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    @Volatile private var distanceGain = 1f
    @Volatile private var currentPlaying = true
    @Volatile private var suppressed = false
    @Volatile private var userPaused = false
    @Volatile private var userStoppedKey: String? = null
    @Volatile private var baseMs = 0L
    @Volatile private var clockStartNanos = 0L
    @Volatile private var pausedAtMs = 0L
    @Volatile private var generation = 0
    @Volatile private var failedAtNanos = 0L
    @Volatile private var active: RemoteHost? = null

    private var bytes: ByteArray? = null
    private var output: AudioOutput? = null

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dango-NearbyLoad").apply { isDaemon = true }
    }
    private val decoderWorker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { r -> Thread(r, "Dango-NearbyDecode").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> baseMs + (System.nanoTime() - clockStartNanos) / 1_000_000L
            State.PAUSED -> pausedAtMs
            else -> baseMs
        }

    /**
     * Called from the client tick. [host] is null when nothing should play.
     * [gain] is the distance attenuation (capped by the caller).
     * [suppressed] is true while the local player owns playback (personal -> group -> nearby).
     */
    @Synchronized
    fun update(hostOrNull: RemoteHost?, gain: Float, suppressedNow: Boolean = false) {
        val host = hostOrNull ?: run {
            if (state != State.IDLE) stop()
            return
        }
        val track = host.track ?: run {
            if (state != State.IDLE) stop()
            return
        }
        distanceGain = gain.coerceIn(0f, 1f)
        suppressed = suppressedNow
        val stoppedKey = userStoppedKey
        if (stoppedKey != null) {
            if (stoppedKey == track.key) {
                active = host
                return
            }
            userStoppedKey = null
        }
        if (suppressedNow && state == State.IDLE) {
            active = host
            return
        }
        if (track.key != trackKey || host.player != hostName) {
            start(host, track)
            return
        }
        active = host
        applyVolume()
        if (suppressedNow) {
            if (state == State.PLAYING) setPaused(true)
            currentPlaying = false
            return
        }
        if (!userPaused && host.playing != currentPlaying) setPaused(!host.playing)
        if (!userPaused && host.playing && state == State.PLAYING) {
            val expected = hostPosition(host)
            if (expected > 0 && abs(expected - positionMs) > 1500L) seekInternal(expected)
        }
        if (state == State.ERROR && System.nanoTime() - failedAtNanos > 30_000_000_000L) {
            start(host, track)
        }
    }

    /** Stop only the remote stream until the host changes track; used by the player stop button. */
    @Synchronized
    fun stopForUser() {
        val key = trackKey
        stop()
        if (key.isNotEmpty()) userStoppedKey = key
    }

    /** Player-bar pause/resume for the remote stream; host sync will not override it. */
    @Synchronized
    fun togglePause() {
        if (suppressed) return
        when (state) {
            State.PLAYING -> {
                userPaused = true
                setPaused(true)
            }
            State.PAUSED -> {
                userPaused = false
                setPaused(false)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun stop() {
        ++generation
        closeOutput()
        bytes = null
        active = null
        state = State.IDLE
        message = ""
        hostName = ""
        trackKey = ""
        route = ""
        baseMs = 0L
        pausedAtMs = 0L
        suppressed = false
        userPaused = false
        userStoppedKey = null
    }

    @Synchronized
    private fun start(host: RemoteHost, track: SharedTrack) {
        ++generation
        closeOutput()
        bytes = null
        active = host
        hostName = host.player
        trackKey = track.key
        currentPlaying = host.playing && !suppressed
        baseMs = hostPosition(host).coerceAtLeast(0L)
        pausedAtMs = baseMs
        route = ""
        userPaused = false
        state = if (host.playing && !suppressed) State.LOADING else State.PAUSED
        message = "正在连接音源…"
        val gen = generation
        loader.execute { load(gen, host, track) }
    }

    private fun load(gen: Int, host: RemoteHost, shared: SharedTrack) {
        if (gen != generation) return
        val track = shared.toTrack()
        val referer = NearbyMedia.referer(track.source)

        // L1: the listener's own account. This keeps the server and the host out of the path.
        try {
            setMessage(gen, "正在下载（本机账号）")
            val url = MusicSources.api(track.source).sharingUrl(track, 1)
            if (url != null && !url.trialOnly) {
                val data = NearbyMedia.download(url.url, referer, 64 * 1024 * 1024)
                if (gen != generation) return
                if (NearbyMedia.isPlayable(data)) {
                    play(gen, host, data!!, "L1")
                    return
                }
                DangoLogger.warn("Nearby L1 audio rejected: {}", shared.key)
            }
        } catch (e: Throwable) {
            if (gen != generation) return
            DangoLogger.warn("Nearby L1 failed: {}", e.javaClass.simpleName)
        }

        // L2: the short-lived URL the host resolved under its own account. The URL is
        // remote-controlled, so keep it on the platform CDN allowlist even across redirects.
        val hostUrl = host.url
        if (!hostUrl.isNullOrBlank() && NearbyMedia.isAllowedRelayHost(hostUrl, false)) {
            setMessage(gen, "正在下载（共享地址）")
            val data = NearbyMedia.download(
                hostUrl, referer, 64 * 1024 * 1024,
                allowed = { candidate -> NearbyMedia.isAllowedRelayHost(candidate.toString(), false) },
            )
            if (gen != generation) return
            if (NearbyMedia.isPlayable(data)) {
                play(gen, host, data!!, "L2")
                return
            }
            DangoLogger.warn("Nearby L2 audio rejected: {}", shared.key)
        }

        // L3: optional server relay (server fetches the host URL, or the server's own fallback).
        val relayClient = relay
        if (relayClient != null) {
            setMessage(gen, "正在通过服务器中转…")
            val data = try {
                relayClient.request(shared.key, hostUrl, 180_000L)
            } catch (_: Throwable) {
                null
            }
            if (gen != generation) return
            if (NearbyMedia.isPlayable(data)) {
                play(gen, host, data!!, "L3")
                return
            }
        }

        fail(
            gen,
            if (hostUrl.isNullOrBlank()) "无法播放：本机没有权限，主机也没有共享地址"
            else "无法播放：本机和共享地址都不可用"
        )
    }

    private fun play(gen: Int, host: RemoteHost, data: ByteArray, routeLabel: String) {
        synchronized(this) {
            if (gen != generation) return
            bytes = data
            route = routeLabel
            state = if (currentPlaying && !suppressed) State.LOADING else State.PAUSED
            message = if (routeLabel == "L3") "服务器中转" else "缓冲中…"
            val start = hostPosition(host).coerceAtLeast(0L)
            baseMs = start
            pausedAtMs = start
            decoderWorker.execute { if (gen == generation) decodeLoop(gen, data, start) }
            DangoLogger.info(
                "Nearby audio route={} source={} bytes={} format={}",
                routeLabel, host.track?.source ?: "", data.size, NearbyMedia.kind(data)
            )
        }
    }

    private fun decodeLoop(gen: Int, data: ByteArray, startMs: Long) {
        var out: AudioOutput? = null
        var decoder: AudioDecoder? = null
        try {
            val started = AudioDecoder.start(data, startMs) { gen == generation }
            decoder = started.decoder
            var pending = started.first
            var appliedVolume = -1f

            while (gen == generation) {
                val frame = pending ?: decoder.read()
                pending = null
                if (frame == null) break

                if (out == null) {
                    val format = AudioFormat(frame.rate.toFloat(), 16, frame.channels, true, false)
                    val opened = JavaSoundOutput(openLine(format, MusicPlayer.lineBufferBytes(frame.rate, frame.channels)))  // 跟本地播放用同一个音频缓冲设置
                    synchronized(this) {
                        if (gen != generation) {
                            opened.close()
                            return
                        }
                        out = opened
                        output = opened
                        baseMs = startMs
                        pausedAtMs = startMs
                        clockStartNanos = System.nanoTime()
                        appliedVolume = (volume * distanceGain).coerceIn(0f, 1f)
                        opened.setVolume(appliedVolume)
                        if (state == State.PAUSED || suppressed) opened.stop() else {
                            opened.start()
                            state = State.PLAYING
                        }
                    }
                }

                if (state == State.PAUSED || suppressed) {
                    out?.let { if (it.isRunning) it.stop() }
                    while (gen == generation && (state == State.PAUSED || suppressed)) Thread.sleep(15)
                    if (gen != generation) break
                    out?.let {
                        baseMs = pausedAtMs
                        clockStartNanos = System.nanoTime()
                        if (state != State.PAUSED) state = State.PLAYING
                        if (!it.isRunning) it.start()
                    }
                }

                val wantedVolume = (volume * distanceGain).coerceIn(0f, 1f)
                if (wantedVolume != appliedVolume) {
                    out?.setVolume(wantedVolume)
                    appliedVolume = wantedVolume
                }

                val pcm = frame.samples
                val length = frame.length
                val pcmBytes = ByteArray(length * 2)
                for (i in 0 until length) {
                    val value = pcm[i].toInt()
                    pcmBytes[i * 2] = (value and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                try {
                    out?.write(pcmBytes, 0, pcmBytes.size)
                } catch (e: Throwable) {
                    if (gen == generation) throw e
                }
            }
        } catch (e: Throwable) {
            DangoLogger.warn("Nearby decode failed: {}", e.toString())
            if (gen == generation) fail(gen, "播放失败：" + e.javaClass.simpleName)
        } finally {
            try { decoder?.close() } catch (_: Exception) {}
            synchronized(this) {
                if (output === out) output = null
            }
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    private fun setPaused(paused: Boolean) {
        if (paused) {
            if (state == State.PLAYING) {
                pausedAtMs = positionMs
                state = State.PAUSED
            }
            currentPlaying = false
        } else {
            if (state == State.PAUSED) {
                baseMs = pausedAtMs
                clockStartNanos = System.nanoTime()
                state = State.PLAYING
            }
            currentPlaying = true
        }
    }

    @Synchronized
    private fun seekInternal(ms: Long) {
        val data = bytes ?: return
        val gen = ++generation
        closeOutput()
        baseMs = ms
        pausedAtMs = ms
        state = if (currentPlaying) State.LOADING else State.PAUSED
        decoderWorker.execute { if (gen == generation) decodeLoop(gen, data, ms) }
    }

    @Synchronized
    private fun fail(gen: Int, text: String) {
        if (gen != generation) return
        closeOutput()
        bytes = null
        state = State.ERROR
        message = text
        failedAtNanos = System.nanoTime()
    }

    private fun setMessage(gen: Int, text: String) {
        synchronized(this) {
            if (gen == generation) message = text
        }
    }

    private fun applyVolume() {
        val out = output ?: return
        try {
            out.setVolume((volume * distanceGain).coerceIn(0f, 1f))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun closeOutput() {
        val out = output
        output = null
        try {
            out?.stop()
            out?.flush()
            out?.close()
        } catch (_: Exception) {
        }
    }

    private fun hostPosition(host: RemoteHost): Long {
        val elapsed = if (host.receivedAtMs > 0L) (System.currentTimeMillis() - host.receivedAtMs).coerceAtLeast(0L) else 0L
        val raw = host.positionMs + if (host.playing) elapsed else 0L
        val duration = host.track?.durationMs ?: 0L
        return if (duration > 0L) {
            raw.coerceIn(0L, duration)
        } else {
            raw.coerceIn(0L, 24L * 60L * 60L * 1000L)
        }
    }

    /** Same device fallback as MusicPlayer; the two players use independent lines. */
    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        try {
            val line = AudioSystem.getSourceDataLine(format)
            try {
                line.open(format, bufferBytes)
            } catch (_: Exception) {
                line.open(format)
            }
            return line
        } catch (e: Throwable) {
            last = e
            DangoLogger.warn("Nearby default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mixerInfo in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (!mixer.isLineSupported(info)) continue
                val line = mixer.getLine(info) as SourceDataLine
                line.open(format)
                DangoLogger.info("Nearby using audio device: {}", mixerInfo.name)
                return line
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }
}
