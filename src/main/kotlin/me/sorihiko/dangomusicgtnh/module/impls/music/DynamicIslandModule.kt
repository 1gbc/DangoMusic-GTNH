package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.event.EventHandler
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.font.MixedText
import me.sorihiko.dangomusicgtnh.gfx.GuiCanvas
import me.sorihiko.dangomusicgtnh.gfx.Texture
import me.sorihiko.dangomusicgtnh.module.Category
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleMeta
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.musicui.fmtTime
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color

import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp

/** 播放 HUD：共用播放进度、封面和尺寸动画，空闲时显示游戏信息。 */
@ModuleMeta(Category.MUSIC, "DynamicIsland")
class DynamicIslandModule : Module() {

    @ModuleValue("Liquid glass", "Liquid glass background; off is a solid black pill")
    private val glass = BooleanValue(true)

    @ModuleValue("Island layout", "Choose how much song information the island shows")
    private val islandLayout = IntValue(0, 1, 0)

    @ModuleValue("Pos X", "Island horizontal position")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Pos Y", "Island vertical position")
    private val posY = FloatValue(0f, 1f, 0.05f, 3)

    @ModuleValue("Scale", "Island size")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    private enum class Kind(val height: Float, val coverSize: Float, val titleSize: Float, val minTextWidth: Float) {
        IDLE(32f, 0f, 12f, 0f),
        FULL(68f, 48f, 14f, 128f),
        COMPACT(42f, 26f, 12f, 68f),
    }

    private var context: GuiCanvas? = null

    private var shown = Kind.IDLE
    private var contentAlpha = 0f

    // 弹簧动画（单位：逻辑像素）
    private var w = 0f
    private var h = 0f
    private var vw = 0f
    private var vh = 0f
    private var lastNanos = 0L
    private var cornerRadius = -1f

    private var dragOffset: Vec2? = null
    private var mouseWasDown = false

    private val clock = SimpleDateFormat("HH:mm")

    private val mt = MixedText()

    override fun onEnable() {
        w = 0f; h = 0f; vw = 0f; vh = 0f
        shown = Kind.IDLE
        contentAlpha = 0f
        lastNanos = 0L
        cornerRadius = -1f
        dragOffset = null
        mouseWasDown = false
    }

    private fun widthOf(kind: Kind, title: String, available: Float): Float {
        val preferred = if (kind == Kind.IDLE) 44f + idleWidth() else {
            val titleWidth = width(title, kind.titleSize, Theme.FONT_SEMIBOLD)
            kind.coverSize + 34f + max(titleWidth, kind.minTextWidth)
        }
        return min(preferred, available)
    }

    private fun idleParts(): List<Pair<String, Color>> {
        val parts = mutableListOf<Pair<String, Color>>()
        val platform = DangoClient.platform
        parts += platform.playerName to Theme.TEXT_PRIMARY
        parts += "${platform.fps} fps" to Theme.TEXT_SECONDARY
        val ping = platform.pingMs
        if (ping > 0) parts += "$ping ms" to Theme.TEXT_SECONDARY
        parts += clock.format(Date()) to Theme.TEXT_SECONDARY
        return parts
    }

    private fun idleWidth(): Float {
        val parts = idleParts()
        var x = 0f
        parts.forEachIndexed { i, (s, _) ->
            if (i > 0) x += width("  ·  ", 12f)
            x += width(s, 12f, if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
        }
        return x
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: HudRenderEvent) = renderIsland(e)

    internal fun renderIsland(e: HudRenderEvent? = null) {
        if (!enabled) return
        val snap = PlaybackView.snapshot()
        if (e == null && snap.track == null) return
        val platform = DangoClient.platform
        if (platform.windowWidth <= 0 || platform.windowHeight <= 0) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        val want = when {
            snap.track == null -> Kind.IDLE
            islandLayout.value == 1 -> Kind.COMPACT
            else -> Kind.FULL
        }
        val title = snap.track?.name.orEmpty()
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val requestedScale = uiScale * scale.value
        // 小窗口先缩放 HUD，长歌名则在可用宽度内截断。
        val baseScale = min(requestedScale, platform.windowWidth / 190f)
        val available = platform.windowWidth / baseScale - 16f

        // 内容切换：先淡出旧内容，再换成新内容淡入
        if (want == Kind.IDLE && shown != Kind.IDLE) {
            shown = Kind.IDLE
            contentAlpha = 1f
        } else if (shown != want) {
            contentAlpha -= dt / 0.09f
            if (contentAlpha <= 0f) {
                contentAlpha = 0f
                shown = want
            }
        } else {
            contentAlpha = min(1f, contentAlpha + dt / 0.16f)
        }

        // 弹簧：略带回弹，接近 iOS 灵动岛的手感
        val tw = widthOf(want, title, available)
        val th = want.height
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        w = min(w + vw * dt, available)
        h += vh * dt
        if (w < 1f || h < 1f) return

        val k = minOf(baseScale, platform.windowWidth / w, platform.windowHeight / h)
        val travelX = (platform.windowWidth / k - w).coerceAtLeast(0f)
        val travelY = (platform.windowHeight / k - h).coerceAtLeast(0f)
        val initial = LayoutBounds(posX.value * travelX, posY.value * travelY, w, h)
        if (e?.overlapsPlayerList(initial, k) == true) {
            dragOffset = null
            return
        }
        val down = e != null && platform.chatOpen && platform.isMouseDown(0)
        val mouseX = platform.mouseX / k
        val mouseY = platform.mouseY / k
        if (!down) dragOffset = null
        else if (!mouseWasDown && mouseX in initial.x..initial.right && mouseY in initial.y..initial.bottom) {
            dragOffset = Vec2(mouseX - initial.x, mouseY - initial.y)
        }
        mouseWasDown = down
        val pill = dragOffset?.let { offset ->
            val x = (mouseX - offset.x).coerceIn(0f, travelX)
            val y = (mouseY - offset.y).coerceIn(0f, travelY)
            if (travelX > 0f) posX.setValue(x / travelX)
            if (travelY > 0f) posY.setValue(y / travelY)
            LayoutBounds(x, y, w, h)
        } ?: initial

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), 0, 0)
        try {
            val targetRadius = if (UiStyle.continuous && want != Kind.IDLE) {
                want.coverSize * CoverCache.DEFAULT_CORNER_RATIO
            } else min(h / 2f, 22f)
            cornerRadius = if (!UiStyle.continuous || cornerRadius < 0f) targetRadius
                else cornerRadius + (targetRadius - cornerRadius) * (1f - exp(-dt / 0.12f))
            val r = min(h / 2f, cornerRadius)

            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(pill, Theme.GLASS_WINDOW.copy(radius = r, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f))))
            } else {
                ctx.drawShape(
                    pill,
                    ShapeStyle(
                        Color(0.02f, 0.02f, 0.03f, 0.9f), r, 1f, Color(1f, 1f, 1f, 0.07f),
                        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                    )
                )
            }

            // 内容按目标尺寸排版，在当前胶囊里居中，超出部分裁掉
            val cw = widthOf(shown, title, available)
            val ch = shown.height
            val cb = LayoutBounds(pill.x + (w - cw) / 2f, pill.y + (h - ch) / 2f, cw, ch)
            val scX = (pill.x * k).toInt()
            val scY = (platform.windowHeight - (pill.y + h) * k).toInt()
            ctx.scissor(scX, scY, (w * k).toInt() + 1, (h * k).toInt() + 1)

            mt.clear()
            mt.clip = pill
            ctx.pushOpacity(contentAlpha)
            if (shown == Kind.IDLE) drawIdle(ctx, cb) else drawSong(ctx, cb, r, shown, snap)
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(k)
            ctx.noScissor()
        } finally {
            ctx.end()
        }
    }

    private fun drawIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val cy = b.y + b.height / 2f
        ctx.drawShape(LayoutBounds(b.x + 14f, cy - 3f, 6f, 6f), ShapeStyle(Theme.GREEN, 3f))
        var x = b.x + 28f
        val ty = cy - 12f * 0.39f
        idleParts().forEachIndexed { i, (s, c) ->
            if (i > 0) {
                text(ctx, "  ·  ", x, ty, 12f, Theme.TEXT_TERTIARY)
                x += width("  ·  ", 12f)
            }
            val font = if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            text(ctx, s, x, ty, 12f, c, font)
            x += width(s, 12f, font)
        }
    }

    private fun drawSong(ctx: GuiCanvas, b: LayoutBounds, radius: Float, kind: Kind, snap: PlaybackView.Snapshot) {
        val track = snap.track ?: return
        val full = kind == Kind.FULL
        val cover = LayoutBounds(b.x + 10f, b.y + (b.height - kind.coverSize) / 2f, kind.coverSize, kind.coverSize)
        val texture = if (UiStyle.continuous) CoverCache.track(track) else CoverCache.trackRounded(track) ?: CoverCache.track(track)
        drawCover(ctx, cover, texture, if (UiStyle.continuous) radius else cover.width * CoverCache.DEFAULT_CORNER_RATIO)
        val x = cover.right + 10f
        val textWidth = b.right - 14f - x
        val title = trim(track.name, textWidth, kind.titleSize, Theme.FONT_SEMIBOLD)
        text(ctx, title, x, b.y + (if (full) 12f else 9f), kind.titleSize, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

        val duration = snap.durationMs.coerceAtLeast(0L)
        val position = snap.positionMs.coerceIn(0L, duration)
        var barX = x
        var barWidth = textWidth
        if (full) {
            val artists = track.artists.joinToString(" / ")
            val album = track.albumName.trim()
            val details = if (artists.isBlank() || album.isBlank()) {
                trim(artists.ifBlank { album }, textWidth, 11f)
            } else {
                val separator = " · "
                val available = textWidth - width(separator, 11f)
                val albumWidth = min(width(album, 11f), available / 2f)
                val artistLabel = trim(artists, available - albumWidth, 11f)
                artistLabel + separator + trim(album, available - width(artistLabel, 11f), 11f)
            }
            text(ctx, details, x, b.y + 31f, 11f, Theme.TEXT_SECONDARY)
            val elapsed = fmtTime(snap.positionMs.coerceAtLeast(0L))
            val remaining = if (duration > 0L) "-${fmtTime(duration - position)}" else "--:--"
            val remainingWidth = width(remaining, 9f)
            text(ctx, elapsed, x, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
            text(ctx, remaining, b.right - 14f - remainingWidth, b.y + 49f, 9f, Theme.TEXT_SECONDARY)
            val inset = width(elapsed, 9f) + 7f
            barX += inset
            barWidth -= inset + remainingWidth + 7f
        }
        if (barWidth <= 0f) return
        val bar = LayoutBounds(barX, b.y + (if (full) 52f else 28f), barWidth, 3f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1.5f))
        if (position > 0L) {
            val fill = (bar.width * (position.toDouble() / duration)).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, fill, bar.height), ShapeStyle(Theme.TEXT_PRIMARY, min(1.5f, fill / 2f)))
        }
    }

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?, radius: Float) {
        CoverCache.upload()
        if (tex != null) {
            if(UiStyle.continuous) ctx.drawRoundedImage(b,tex,radius) else ctx.drawImage(b,tex)
        } else ctx.drawShape(b,ShapeStyle(Theme.TRACK,radius))
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)
}
