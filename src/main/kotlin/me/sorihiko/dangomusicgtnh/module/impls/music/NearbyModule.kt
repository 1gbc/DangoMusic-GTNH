package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoMusic
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
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsStyle
import me.sorihiko.dangomusicgtnh.multiplayer.sanitized
import me.sorihiko.dangomusicgtnh.music.LyricFonts
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NearbyAudioPlayer
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.network.NearbyClient
import me.sorihiko.dangomusicgtnh.utils.Color
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

    @ModuleValue("Share quality", "Quality requested for the shared URL")
    internal val shareQuality = IntValue(0, 2, 1)

    @ModuleValue("Share my lyrics", "Let nearby players and group members see your current line")
    internal val shareLyrics = BooleanValue(false)

    @ModuleValue("See nearby lyrics", "Show lyrics for nearby and group playback")
    internal val seeLyrics = BooleanValue(true)

    @ModuleValue("Hear group", "Follow the group speaker regardless of distance")
    internal val hearGroup = BooleanValue(true)

    @ModuleValue("Share to group", "Let group members hear your music")
    internal val shareGroup = BooleanValue(true)

    @ModuleValue("Group takes over", "Joining or listening to a group pauses your own music and plays the group's song")
    internal val groupTakeover = BooleanValue(true)

    @ModuleValue("Resume after group", "Your own music continues when you leave the group or stop listening to it")
    internal val resumeAfterGroup = BooleanValue(true)

    override fun onEnable() {
        NearbyAudioPlayer.volume = MusicPlayer.volume
    }

    override fun onDisable() {
        NearbyAudioPlayer.stop()
        NearbyClient.stopAll()
    }

    @EventHandler
    fun onTick(event: TickEvent) {
        NearbyAudioPlayer.volume = MusicPlayer.volume
    }

    /** Same stable per-glyph random as LyricsModule.withWiggle. */
    private fun hashRand(k: Int, frame: Long, salt: Int, seed: Int): Float {
        var h = k * 73856093 xor (frame * 19349663L).toInt() xor (salt * 83492791) xor seed
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    @EventHandler
    fun render(event: WorldRenderEndEvent) {
        if (!enabled || !seeLyrics.get()) return
        // 歌词样式设成“不显示”时，别人投影过来的歌词也不画
        if (DangoMusic.instance.moduleManager.get(LyricsModule::class.java).lyricsHidden()) return
        if (PlaybackView.localPresent()) return
        val host = NearbyClient.activeHost() ?: return
        if (!host.shareLyrics) return
        if (NearbyClient.shouldUseLocalLyrics()) return
        val track = host.track ?: return
        if (host.dimension != DangoClient.platform.dimension) return
        val payload = NearbyClient.lyricsData(track.key) ?: return
        val lines = payload.lines
        if (lines.isEmpty()) return
        val style = (payload.style ?: LyricsStyle()).sanitized()
        val position = host.positionMs + if (host.playing) {
            (System.currentTimeMillis() - host.receivedAtMs).coerceAtLeast(0L)
        } else 0L
        val index = lines.indexOfLast { it.timeMs <= position }
        if (index < 0) return

        val local = DangoClient.platform.player ?: return
        val anchor = host.anchor?.sanitized()
        val ax = anchor?.x ?: host.x
        val ay = anchor?.y ?: (host.y + 1.65)
        val az = anchor?.z ?: host.z
        val dx = ax - local.eyeX
        val dy = ay - local.eyeY
        val dz = az - local.eyeZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!host.inGroup && distance > NearbyClient.radius() + 8.0) return

        val world = DangoClient.gfx.world
        val rx = ax - world.cameraX
        val ry = ay - world.cameraY
        val rz = az - world.cameraZ
        val horizontal = sqrt(rx * rx + rz * rz)
        val yaw = Math.toDegrees(atan2(-rx, rz)).toFloat()
        val pitch = Math.toDegrees(atan2(-ry, horizontal)).toFloat()
        val scale = if (anchor != null) {
            // Same formula as LyricsModule: avoids trusting an old anchor.scale default
            // while still matching the host's world-space text size.
            (0.03f * style.scale.coerceIn(0.3f, 3f) * (style.distance.coerceIn(3f, 40f) / 6.5f)).toFloat()
        } else {
            (0.03f * (distance / 8.0).coerceIn(0.6, 2.0)).toFloat()
        }
        val count = style.maxLines.coerceIn(1, 8)
        val wiggleFps = style.wiggleFps.coerceIn(1, 24)
        // The server advances the host's frame to broadcast time; add the network
        // transit since receipt. Without a host frame, fall back to local time.
        val wiggleFrame = anchor?.wiggleFrame?.takeIf { it >= 0L }?.let { hostFrame ->
            hostFrame + (System.currentTimeMillis() - host.receivedAtMs).coerceAtLeast(0L) * wiggleFps / 1000L
        } ?: (System.nanoTime() / 1_000_000L * wiggleFps / 1000L)

        fun color(rgb: Int, alpha: Float): Color = Color(
            ((rgb shr 16) and 255) / 255f,
            ((rgb shr 8) and 255) / 255f,
            (rgb and 255) / 255f,
            alpha,
        )

        fun drawLine(
            pen: me.sorihiko.dangomusicgtnh.gfx.TextCanvas,
            text: String,
            y: Float,
            size: Float,
            rgb: Int,
            alpha: Float,
            shadow: Boolean,
            frame: Long,
            wiggle: Boolean = false,
        ) {
            if (text.isBlank()) return
            val c = color(rgb, alpha)
            val argb = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
            if (!wiggle) {
                if (style.smooth) {
                    val width = pen.smoothWidth(text, size, CjkStyle.LYRIC)
                    if (shadow) {
                        pen.color(0f, 0f, 0f, alpha * 0.45f)
                        pen.smoothText(text, -width / 2f + 0.6f, y + 0.6f, size, CjkStyle.LYRIC)
                    }
                    pen.color(c.r, c.g, c.b, c.a)
                    pen.smoothText(text, -width / 2f, y, size, CjkStyle.LYRIC)
                } else {
                    val width = pen.pixelWidth(text)
                    pen.pixelText(text, -width / 2f, y, argb, shadow)
                }
                return
            }
            val widths = text.map { ch ->
                if (style.smooth) pen.smoothWidth(ch.toString(), size, CjkStyle.LYRIC)
                else pen.pixelWidth(ch.toString())
            }
            var x = -widths.sum() / 2f
            // Match LyricsModule's per-glyph wobble: same hash, rotation, offset
            // and scale, driven by the host's frame so both clients move together.
            // Settled/KTV lines use a 1-based non-whitespace glyph index.
            val seed = 31 + text.hashCode()
            val amount = style.wiggleAmount.coerceIn(0f, 2f)
            var glyphIndex = 0
            text.forEachIndexed { i, ch ->
                if (!ch.isWhitespace()) {
                    val k = ++glyphIndex
                    val rot = hashRand(k, frame, 1, seed) * 9f * amount
                    val dx = hashRand(k, frame, 2, seed) * 0.8f * amount
                    val dy = hashRand(k, frame, 3, seed) * 1.6f * amount
                    val sc = 1f + hashRand(k, frame, 4, seed) * 0.12f * amount
                    val cx = x + widths[i] / 2f
                    val cy = y + 3.5f
                    pen.push()
                    try {
                        pen.translate(cx + dx, cy + dy)
                        pen.rotate(rot)
                        pen.scale(sc, sc)
                        pen.translate(-cx, -cy)
                        if (style.smooth) {
                            if (shadow) {
                                pen.color(0f, 0f, 0f, alpha * 0.45f)
                                pen.smoothText(ch.toString(), x + 0.6f, y + 0.6f, size, CjkStyle.LYRIC)
                            }
                            pen.color(c.r, c.g, c.b, c.a)
                            pen.smoothText(ch.toString(), x, y, size, CjkStyle.LYRIC)
                        } else {
                            pen.pixelText(ch.toString(), x, y, argb, shadow)
                        }
                    } finally {
                        pen.pop()
                    }
                }
                x += widths[i]
            }
        }

        fun draw(glowPass: Boolean) {
            val pen = DangoClient.gfx.text
            val (font, key) = if (style.smooth) LyricFonts.load(style.font) else (null to null)
            pen.font(CjkStyle.LYRIC, font, key)
            if (style.smooth) pen.beginSmooth()
            val stride = when {
                style.romanization -> 24f
                style.translation -> 22f
                else -> 14f
            }
            try {
                for (i in 0 until count) {
                    val line = lines.getOrNull(index + i) ?: break
                    val alpha = if (i == 0) 0.96f else 0.72f
                    val y = i * stride
                    drawLine(pen, line.text, y, 10f,
                        if (style.ktv) style.ktvBaseColor else style.textColor, alpha, !glowPass, wiggleFrame, style.wiggle)
                    if (style.translation) {
                        line.translation?.takeIf { it.isNotBlank() }?.let {
                            drawLine(pen, it, y + 12f, 8f, style.translationColor, alpha * 0.75f, !glowPass, wiggleFrame)
                        }
                    }
                    if (style.romanization) {
                        line.romanization?.takeIf { it.isNotBlank() }?.let {
                            drawLine(pen, it, y + 20f, 8f, style.translationColor, alpha * 0.62f, !glowPass, wiggleFrame)
                        }
                    }
                }
            } finally {
                if (style.smooth) pen.endSmooth()
            }
        }

        DangoClient.gfx.guarded {
            world.begin()
            try {
                world.push(rx, ry, rz, yaw, pitch, scale)
                try {
                    if (style.glow && DangoClient.gfx.glow.available) {
                        try {
                            if (DangoClient.gfx.glow.begin()) {
                                draw(true)
                                DangoClient.gfx.glow.end(
                                    1f, 1f, 1f,
                                    style.glowStrength.coerceIn(0f, 3f) * 4f,
                                    style.glowRadius.coerceIn(0f, 1f),
                                )
                            }
                        } catch (t: Throwable) {
                            DangoClient.gfx.glow.disable(t)
                        }
                        world.resync()
                    }
                    draw(false)
                } finally {
                    world.pop()
                }
            } finally {
                world.resync()
            }
        }
    }
}
