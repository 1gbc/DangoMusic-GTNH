package me.sorihiko.dangomusicgtnh.network

import net.minecraftforge.common.config.Configuration
import java.io.File

/** Optional server-side policy; the server component is usable without a client install. */
object ServerConfig {
    var enabled = true
    var radius = 32
    var maxHosts = 4
    var maxGroup = 8
    var allowLyrics = true

    /** L3 is deliberately off by default: it uses server bandwidth and is a redistribution path. */
    var relayEnabled = false
    var relayMaxListeners = 2
    var relayMaxMb = 16
    var relayChunksPerTick = 2
    var relayAllowLocal = false

    private var config: Configuration? = null

    fun load(file: File) {
        file.parentFile?.mkdirs()
        val c = Configuration(file)
        config = c
        c.load()
        read(c)
        if (c.hasChanged()) c.save()
    }

    fun reload() {
        val c = config ?: return
        c.load()
        read(c)
        if (c.hasChanged()) c.save()
    }

    private fun read(c: Configuration) {
        val g = "general"
        enabled = c.getBoolean("enabled", g, true, "Enable the optional server-side nearby/group component.")
        radius = c.getInt("radiusBlocks", g, 32, 4, 128, "Maximum distance (blocks) for nearby sharing.")
        maxHosts = c.getInt("maxHostsPerListener", g, 4, 1, 8, "Maximum simultaneous nearby hosts per listener.")
        maxGroup = c.getInt("maxGroupSize", g, 8, 2, 16, "Maximum members in one music group.")
        allowLyrics = c.getBoolean("allowLyrics", g, true, "Allow hosts to share line-level lyrics.")
        relayEnabled = c.getBoolean("relayEnabled", g, false, "Allow the L3 server relay fallback. Off by default.")
        relayMaxListeners = c.getInt("relayMaxListeners", g, 2, 1, 8, "Maximum concurrent L3 relay listeners.")
        relayMaxMb = c.getInt("relayMaxMb", g, 16, 1, 64, "Maximum size of one relayed track (MB).")
        relayChunksPerTick = c.getInt("relayChunksPerTick", g, 2, 1, 16, "4 KiB chunks sent per server tick per relay.")
        relayAllowLocal = c.getBoolean("relayAllowLocal", g, false, "Test only: allow 127.0.0.1 relay URLs.")
    }
}
