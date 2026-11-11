package me.sorihiko.dangomusicgtnh.music

import com.google.gson.JsonObject
import me.sorihiko.dangomusicgtnh.DangoMusic
import java.util.concurrent.Executors

/** The browser plays the current song. MusicPlayer owns the in-game queue. */
object AppleWebPlayer {
    @Volatile private var server: AppleWebServer? = null
    @Volatile var message = ""
        private set
    @Volatile private var selected = false
    private var lastVolume = -1f
    @Volatile private var loadStartedAt = 0L
    private var endedOnce = false
    @Volatile private var connectionEpoch = 0L
    @Volatile private var nativeProcess: Process? = null
    @Volatile private var nativeAttempted = false
    @Volatile var nativeMode = false
        private set
    @Volatile private var startupFailure = ""
    @Volatile private var playbackFailure = ""
    @Volatile private var nativeStartedAt = 0L
    private val nativeWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-AppleNative").apply { isDaemon = true } }
    val nativeRunning get() = nativeProcess?.isAlive == true
    val connected get() = server?.connected() == true
    val authorized get() = server?.authorized() == true
    val authState get() = server?.authState() ?: "disconnected"
    fun restoreNativeIfNeeded() { if (!nativeAttempted && !nativeRunning && !connected) startNative() }
    val pairingLink get() = server?.pairingLink().orEmpty()
    fun ensureStarted() { if (server == null) connect() }
    fun startNative() {
        nativeAttempted = true
        nativeWorker.execute {
            nativeMode = true
            startupFailure = ""
            if (nativeRunning && connected) { command("show"); return@execute }
            if (!System.getProperty("os.name", "").startsWith("Windows")) { startupFailure = "The embedded Apple player currently requires Windows"; message = startupFailure; return@execute }
            val executable = try {
                BundledApplePlayer.install(DangoMusic.instance.clientDir.resolve("apple-player")).toFile()
            } catch (_: Exception) {
                startupFailure = "Unable to install the bundled Apple Music player; check the mod JAR and config directory permissions"
                message = startupFailure
                return@execute
            }
            try {
                val profile = DangoMusic.instance.accountsDir.resolve("apple-webview")
                command("stop")
                if (connected) Thread.sleep(1200)
                nativeProcess?.destroy()
                connect()
                nativeMode = true
                if (server == null) return@execute
                val endpoint = server ?: return@execute
                val expectedEpoch = connectionEpoch
                val child = ProcessBuilder(executable.absolutePath).directory(executable.parentFile).start()
                synchronized(this) {
                    if (connectionEpoch != expectedEpoch) { child.destroy(); return@execute }
                    nativeProcess = child
                    nativeStartedAt = System.nanoTime()
                }
                val bootstrap = JsonObject().apply {
                    addProperty("link", endpoint.pairingLink())
                    addProperty("profile", profile.toString())
                    addProperty("parentPid", java.lang.management.ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: 0L)
                }
                child.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(bootstrap.toString()); it.newLine() }
                message = "Complete Apple login in the embedded player, then hide its window"
                Thread({
                    try {
                        val exit = child.waitFor()
                        synchronized(this) {
                            if (nativeProcess === child) {
                                nativeProcess = null
                                if (connectionEpoch == expectedEpoch) {
                                    server?.reconnect()
                                    startupFailure = me.sorihiko.dangomusicgtnh.i18n.I18n.text("Embedded Apple player exited; restart it or check the component") + " [$exit]"
                                    message = startupFailure
                                }
                            }
                        }
                    } catch (_: InterruptedException) { }
                }, "Dango-AppleExit").apply { isDaemon = true }.start()
            } catch (_: Exception) {
                startupFailure = "Unable to launch embedded Apple player; check the component and .NET Desktop Runtime"
                message = startupFailure
            }
        }
    }
    @Synchronized fun connect() {
        try {
            ++connectionEpoch
            AppleMusicSource.clear()
            nativeMode = false
            startupFailure = ""
            if (server == null) server = AppleWebServer() else server?.reconnect()
            message = "Pair the Apple Music browser extension"
            lastVolume = -1f
        } catch (_: Exception) { message = "Unable to start Apple Music local bridge" }
    }
    fun request(op: String, value: String, offset: Int): JsonObject {
        if (!connected) throw SourceAccessException("Apple Music player is disconnected")
        if (op !in listOf("search", "charts") && !authorized) throw SourceAccessException(when (authState) {
            "restoring" -> "Restoring Apple Music session; library access is not ready"
            "unavailable" -> "Apple Music library interface is unavailable; check the player window"
            else -> "Apple Music website sign-in is not yet authorized for library access"
        })
        val endpoint = server ?: throw SourceAccessException("Apple Music player is disconnected")
        val epoch = connectionEpoch
        try {
            val result = endpoint.request(op, value, offset)
            if (epoch != connectionEpoch) throw SourceAccessException("Apple Music session changed; retry")
            return result
        } catch (e: Exception) {
            if (e.cause?.message == "Apple Music request cancelled") throw java.util.concurrent.CancellationException()
            if (e is java.util.concurrent.TimeoutException) throw SourceAccessException("Apple Music 游戏与组件请求超时 [RPC_TIMEOUT]")
            throw SourceAccessException("Apple Music 游戏与组件连接异常 [RPC_CONNECTION]")
        }
    }
    fun load(track: NeteaseTrack): Boolean {
        playbackFailure = ""
        if (!authorized) { message = "Sign in to Apple Music in the login window"; return false }
        return try {
            server?.playSong(track.mediaId.removePrefix("library:"), track.mediaId.startsWith("library:"))
            loadStartedAt = System.nanoTime()
            true
        } catch (_: Exception) { message = "Apple Music could not load this song"; false }
    }
    fun command(name: String, value: Double? = null) {
        if (name == "stop") { loadStartedAt = 0L; playbackFailure = "" }
        try { server?.command(name, value) } catch (_: Exception) { message = "Apple Music control failed" }
    }
    fun tick() {
        val apple = MusicSources.selected == MusicSource.APPLE
        if (selected && !apple) { command("stop"); MusicPlayer.clearAppleState() }
        selected = apple
        if (!apple) return
        if (connected && lastVolume != MusicPlayer.volume) {
            lastVolume = MusicPlayer.volume
            command("volume", lastVolume.toDouble())
        }
        val s = server?.snapshot()
        val controlError = server?.error().orEmpty()
        if (controlError.startsWith("Apple Music queue failed") || controlError.startsWith("Apple Music play failed")) playbackFailure = controlError
        message = when {
            startupFailure.isNotEmpty() -> startupFailure
            playbackFailure.isNotEmpty() -> playbackFailure
            nativeMode && nativeRunning && !connected && System.nanoTime() - nativeStartedAt < 15_000_000_000L -> "Starting embedded Apple player"
            nativeMode && !connected -> "Embedded player is not connected; show its window to check startup"
            server == null -> "Connect Apple Music using the account button"
            !connected -> "Apple Music browser disconnected; pair again"
            server?.error()?.isNotEmpty() == true -> server!!.error()
            connected && !authorized && authState == "restoring" -> "Restoring Apple Music session; library access is not ready"
            connected && !authorized && authState == "unavailable" -> "Apple Music library interface is unavailable; check the player window"
            connected && !authorized -> "Apple Music website sign-in is not yet authorized for library access"
            else -> ""
        }
        fun str(k: String) = s?.get(k)?.asString.orEmpty()
        val current = MusicPlayer.current
        val reportedId = str("id")
        if (s?.get("ended")?.asBoolean == true) {
            // Older companions can report an end without an item ID. A pending load
            // is a barrier in either case, including when repeating the same song.
            if (!endedOnce && loadStartedAt == 0L && current?.source == MusicSource.APPLE &&
                (reportedId.isEmpty() || current.matchesAppleId(reportedId))) {
                endedOnce = true
                MusicPlayer.finishAppleTrack()
            }
            return
        }
        endedOnce = false
        if (s == null || !s.get("available").asBoolean || s.get("title").asString.isEmpty()) {
            if (loadStartedAt != 0L && System.nanoTime() - loadStartedAt < 65_000_000_000L && server?.error().isNullOrEmpty()) return
            loadStartedAt = 0L
            MusicPlayer.clearAppleState()
            return
        }
        val id = reportedId.ifEmpty { str("title") + ":" + str("artist") }
        loadStartedAt = 0L
        val track = current?.takeIf { it.matchesAppleId(id) }
            ?: MusicPlayer.queue.firstOrNull { it.matchesAppleId(id) }
            ?: NeteaseTrack(MusicSources.stableId(MusicSource.APPLE, id), str("title"), listOf(str("artist")),
                s.get("durationMs").asLong, str("cover"), MusicSource.APPLE, id).apply { albumName = str("album") }
        val duration = s.get("durationMs").asLong
        if (duration > 0) track.durationMs = duration
        if (str("cover").isNotEmpty()) track.coverUrl = str("cover")
        MusicPlayer.acceptAppleState(track, s.get("positionMs").asLong, s.get("playing").asBoolean)
    }
    private fun NeteaseTrack.matchesAppleId(id: String) =
        source == MusicSource.APPLE && (nativeId == id || mediaId.removePrefix("library:") == id)
    fun disconnect() {
        val retired = synchronized(this) {
            ++connectionEpoch
            AppleMusicSource.clear()
            loadStartedAt = 0L
            val old = server to nativeProcess
            server = null
            nativeProcess = null
            startupFailure = ""
            nativeMode = false
            playbackFailure = ""
            old
        }
        val old = retired.first
        val child = retired.second
        old?.command("stop", null)
        if (child != null) old?.command("shutdown", null)
        MusicPlayer.clearAppleState()
        // Allow the extension to receive/ack stop before retiring its endpoint.
        if (old != null) Thread({
            try {
                if (child == null) Thread.sleep(2000)
                else if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) child.destroy()
            } catch (_: InterruptedException) { }
            old.close()
        }, "Dango-AppleDisconnect").apply { isDaemon = true }.start()
    }
}
