package me.sorihiko.dangomusicgtnh.music

/** Browser owns audio and queue in this first game integration. No audio enters Java Sound. */
object AppleWebPlayer {
    @Volatile private var server: AppleWebServer? = null
    @Volatile var message = ""
        private set
    @Volatile private var selected = false
    private var lastVolume = -1f
    val connected get() = server?.connected() == true
    val pairingLink get() = server?.pairingLink().orEmpty()
    fun ensureStarted() { if (server == null) connect() }
    @Synchronized fun connect() {
        try {
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
        val old = synchronized(this) { val previous = server; server = null; previous }
        old?.command("stop", null)
        MusicPlayer.clearAppleState()
        // Allow the extension to receive/ack stop before retiring its endpoint.
        if (old != null) Thread({
            try { Thread.sleep(2000) } catch (_: InterruptedException) { }
            old.close()
        }, "Dango-AppleDisconnect").apply { isDaemon = true }.start()
    }
}
