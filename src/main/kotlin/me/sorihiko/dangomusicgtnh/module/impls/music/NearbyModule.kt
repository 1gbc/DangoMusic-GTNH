package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.event.EventHandler
import me.sorihiko.dangomusicgtnh.events.TickEvent
import me.sorihiko.dangomusicgtnh.events.WorldRenderEndEvent
import me.sorihiko.dangomusicgtnh.gfx.CjkStyle
import me.sorihiko.dangomusicgtnh.module.Category
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleMeta
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.NearbyAudioPlayer
import me.sorihiko.dangomusicgtnh.network.NearbyClient
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Nearby sharing and music-group settings, plus the remote lyric projection.
 *
 * The audio itself is played by [NearbyAudioPlayer]; this module only owns the values
 * and draws the line-level projection above the host.
 */
@ModuleMeta(Category.MUSIC, "Nearby")
class NearbyModule : Module() {

    @ModuleValue("Share my music", "Let nearby players and group members hear your music")
    internal val shareMusic = BooleanValue(false)

    @ModuleValue("Hear nearby music", "Play the music of players within the server radius")
    internal val hearNearby = BooleanValue(true)

    @ModuleValue("Nearby volume", "Volume for nearby and group music")
    internal val nearbyVolume = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Share quality", "Quality requested for the shared URL")
    internal val shareQuality = IntValue(0, 2, 1)

    @ModuleValue("Share my lyrics", "Let nearby players and group members see your current line")
    internal val shareLyrics = BooleanValue(false)

    @ModuleValue("See nearby lyrics", "Show the current line above nearby hosts")
    internal val seeLyrics = BooleanValue(true)

    @ModuleValue("Hear group", "Follow the group host regardless of distance")
    internal val hearGroup = BooleanValue(true)

    @ModuleValue("Share to group", "Let group members hear your music")
    internal val shareGroup = BooleanValue(true)

    override fun onEnable() {
        NearbyAudioPlayer.volume = nearbyVolume.value
    }

    override fun onDisable() {
        NearbyAudioPlayer.stop()
        NearbyClient.stopAll()
    }

    @EventHandler
    fun onTick(event: TickEvent) {
        NearbyAudioPlayer.volume = nearbyVolume.value
    }

    @EventHandler
    fun render(event: WorldRenderEndEvent) {
        if (!enabled || !seeLyrics.get()) return
        val host = NearbyClient.activeHost() ?: return
        if (!host.shareLyrics) return
        val track = host.track ?: return
        if (host.dimension != DangoClient.platform.dimension) return
        val lines = NearbyClient.lyricsFor(track.key) ?: return
        val position = host.positionMs + if (host.playing) {
            (System.currentTimeMillis() - host.receivedAtMs).coerceAtLeast(0L)
        } else 0L
        val index = lines.indexOfLast { it.timeMs <= position }
        if (index < 0) return
        val line = lines[index]

        val local = DangoClient.platform.player ?: return
        val dx = host.x - local.eyeX
        val dy = host.y - local.eyeY
        val dz = host.z - local.eyeZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!host.inGroup && distance > NearbyClient.radius() + 4.0) return

        val world = DangoClient.gfx.world
        val rx = host.x - world.cameraX
        val ry = host.y + 1.65 - world.cameraY
        val rz = host.z - world.cameraZ
        val horizontal = sqrt(rx * rx + rz * rz)
        val yaw = Math.toDegrees(atan2(-rx, rz)).toFloat()
        val pitch = Math.toDegrees(atan2(-ry, horizontal)).toFloat()
        val scale = (0.03f * (distance / 8.0).coerceIn(0.6, 2.0)).toFloat()

        DangoClient.gfx.guarded {
            world.begin()
            try {
                world.push(rx, ry, rz, yaw, pitch, scale)
                try {
                    val pen = DangoClient.gfx.text
                    pen.beginSmooth()
                    try {
                        pen.color(1f, 1f, 1f, 0.95f)
                        val width = pen.smoothWidth(line.text, 10f, CjkStyle.PLAIN)
                        pen.smoothText(line.text, -width / 2f, 0f, 10f, CjkStyle.PLAIN)
                        line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                            pen.color(1f, 1f, 1f, 0.68f)
                            val translationWidth = pen.smoothWidth(translation, 8f, CjkStyle.PLAIN)
                            pen.smoothText(translation, -translationWidth / 2f, 12f, 8f, CjkStyle.PLAIN)
                        }
                    } finally {
                        pen.endSmooth()
                    }
                } finally {
                    world.pop()
                }
            } finally {
                world.resync()
            }
        }
    }
}
