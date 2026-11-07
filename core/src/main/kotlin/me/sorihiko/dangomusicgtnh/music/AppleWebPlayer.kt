package me.sorihiko.dangomusicgtnh.music

import com.google.gson.JsonObject
import me.sorihiko.dangomusicgtnh.DangoMusic
import java.util.concurrent.Executors

/** Browser owns audio and queue in this first game integration. No audio enters Java Sound. */
object AppleWebPlayer {
    @Volatile private var server: AppleWebServer? = null
    @Volatile var message = ""
        private set
    @Volatile private var selected = false
    private var lastVolume = -1f
    @Volatile private var connectionEpoch = 0L
    @Volatile private var nativeProcess: Process? = null
    private val nativeWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-AppleNative").apply { isDaemon = true } }
    val nativeRunning get() = nativeProcess?.isAlive == true
    val connected get() = server?.connected() == true
    val pairingLink get() = server?.pairingLink().orEmpty()
    fun ensureStarted() { if (server == null) connect() }
    fun startNative() {
        nativeWorker.execute {
            if (nativeRunning && connected) { command("show"); return@execute }
            val directory = DangoMusic.instance.clientDir.resolve("apple-player").toFile()
            val executable = directory.resolve("DangoApplePlayer.exe")
            if (!executable.isFile) { message = "Install the Apple Music playback component in config/dangomusicgtnh/apple-player"; return@execute }
            if (!System.getProperty("os.name", "").startsWith("Windows")) { message = "The embedded Apple player currently requires Windows"; return@execute }
            try {
                command("stop")
                if (connected) Thread.sleep(1200)
                nativeProcess?.destroy()
                connect()
                if (server == null) return@execute
                val endpoint = server ?: return@execute
                val expectedEpoch = connectionEpoch
                val child = ProcessBuilder(executable.absolutePath).directory(directory).start()
                synchronized(this) {
                    if (connectionEpoch != expectedEpoch) { child.destroy(); return@execute }
                    nativeProcess = child
                }
                val bootstrap = JsonObject().apply {
                    addProperty("link", endpoint.pairingLink())
                    addProperty("profile", DangoMusic.instance.accountsDir.resolve("apple-webview").toAbsolutePath().toString())
                    addProperty("parentPid", java.lang.management.ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toLongOrNull() ?: 0L)
                }
                child.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(bootstrap.toString()); it.newLine() }
                message = "Complete Apple login in the embedded player, then hide its window"
            } catch (_: Exception) { message = "Unable to launch embedded Apple player; check the component and .NET Desktop Runtime" }
        }
    }
    @Synchronized fun connect() {
        try {
            ++connectionEpoch
            if (server == null) server = AppleWebServer() else server?.reconnect()
            message = "Pair the Apple Music browser extension"
            lastVolume = -1f
        } catch (_: Exception) { message = "Unable to start Apple Music local bridge" }
    }
    fun command(name: String, value: Double? = null) {
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
        message = when {
            server == null -> "Connect Apple Music using the account button"
            !connected -> "Apple Music browser disconnected; pair again"
            server?.error()?.isNotEmpty() == true -> server!!.error()
            else -> ""
        }
        if (s == null || !s.get("available").asBoolean || s.get("title").asString.isEmpty()) {
            MusicPlayer.clearAppleState()
            return
        }
        fun str(k: String) = s.get(k)?.asString.orEmpty()
        val id = str("id").ifEmpty { str("title") + ":" + str("artist") }
        val track = NeteaseTrack(MusicSources.stableId(MusicSource.APPLE, id), str("title"), listOf(str("artist")),
            s.get("durationMs").asLong, str("cover"), MusicSource.APPLE, id).apply { albumName = str("album") }
        MusicPlayer.acceptAppleState(track, s.get("positionMs").asLong, s.get("playing").asBoolean)
    }
    fun disconnect() {
        val retired = synchronized(this) {
            ++connectionEpoch
            val old = server to nativeProcess
            server = null
            nativeProcess = null
            old
        }
        val old = retired.first
        val child = retired.second
        old?.command("stop", null)
        MusicPlayer.clearAppleState()
        // Allow the extension to receive/ack stop before retiring its endpoint.
        if (old != null) Thread({
            try { Thread.sleep(2000) } catch (_: InterruptedException) { }
            old.close()
            child?.destroy()
        }, "Dango-AppleDisconnect").apply { isDaemon = true }.start()
    }
}
