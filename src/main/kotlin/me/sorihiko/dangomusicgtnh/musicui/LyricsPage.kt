package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.utils.Color

/** 网易云唱片与歌词页。保留原来浏览页的状态，也不改动游戏内歌词的设置。 */
internal class LyricsPage(private val ui: Ui, private val module: NeteaseMusicModule) {
    private enum class Menu { SIZE, MORE }

    private data class Row(val top: Float, val text: List<String>, val phonetic: List<String>, val translation: List<String>, val height: Float) {
        val center get() = top + height / 2f
    }

    private val white = Color(1f, 1f, 1f, 1f)
    private var menu: Menu? = null
    private var track: NeteaseTrack? = null
    private var lines = emptyList<LyricLine>()
    private var rows = emptyList<Row>()
    private var lyricArea = LayoutBounds()
    private var layoutWidth = 0f
    private var layoutSize = 0f

    private var lyricOffsetMs = 0L
    private var offsetTrack: String? = null

    private fun lyricOffset(song: NeteaseTrack): Long = if (offsetTrack == song.key) lyricOffsetMs else 0L

    private fun adjustLyricOffset(song: NeteaseTrack, deltaMs: Long) {
        lyricOffsetMs = (lyricOffset(song) + deltaMs).coerceIn(-30_000L, 30_000L)
        offsetTrack = song.key
    }

    private var showTranslation = false
    private var showRomanization = false
    private var hasTranslation = false
    private var hasRomanization = false
    private var manualPosition: Float? = null
    private var scrollPosition = 0f

    fun dismissMenu(): Boolean {
        if (menu == null) return false
        menu = null
        return true
    }

    fun reset() {
        menu = null
        manualPosition = null
    }

    fun scroll(wheel: Int, x: Int, y: Int) {
        if (menu != null || rows.isEmpty() || !lyricArea.contains(x, y)) return
        manualPosition = (manualPosition ?: scrollPosition).plus(if (wheel > 0) -80f else 80f)
            .coerceIn(0f, rows.last().center)
    }

    fun draw(area: LayoutBounds, close: () -> Unit) {
        val song = MusicPlayer.current?.takeIf { it.source == MusicSource.NETEASE }
        if (song?.key != track?.key) {
            manualPosition = null
            scrollPosition = 0f
        }
        track = song
        val lyricModule = module.lyrics()
        val lyrics = song?.let { lyricModule?.playerLyrics(it) }
        val loading = song != null && lyricModule?.isLoadingLyrics(song) == true
        val size = module.lyricsPageSize.value.toFloat()

        ui.card(area, Color.fromHex("#382122"), 22f)
        val collapse = LayoutBounds(area.x + 18f, area.y + 16f, 36f, 36f)
        button(collapse, "⌄", I18n.text("Collapse lyrics"), action = close)

        val leftWidth = area.width * 0.46f
        val textX = area.x + leftWidth
        val railX = area.right - 66f
        val textWidth = railX - textX - 26f
        val title = song?.name ?: I18n.text("Not playing")
        val titleFont = Theme.FONT_SEMIBOLD
        ui.text(ui.trim(title, textWidth, 32f, titleFont), textX, area.y + 28f, 32f, white, titleFont)
        val info = listOfNotNull(
            song?.artists?.takeIf { it.isNotEmpty() }?.let { I18n.format("Artist: %s", it.joinToString(" / ")) },
            song?.albumName?.takeIf { it.isNotBlank() }?.let { I18n.format("Album: %s", it) },
        ).joinToString("   ·   ")
        ui.text(ui.trim(info, textWidth, 15f), textX, area.y + 74f, 15f, white.copy(a = 0.63f))

        val label = LayoutBounds(textX, area.y + 104f, 78f, 28f)
        ui.card(label, white.copy(a = 0.13f), 14f)
        ui.icon(I18n.text("Lyrics"), label.x + 39f, label.y + 14f, 14f, white)
        if (manualPosition != null) {
            val follow = LayoutBounds(label.right + 10f, label.y, 140f, label.height)
            ui.pill(follow, white.copy(a = 0.16f), I18n.text("Follow playback")) { manualPosition = null }
        }

        drawRecord(area.x + leftWidth / 2f, area.y + area.height * 0.55f, minOf(leftWidth * 0.74f, area.height * 0.65f), song)
        lyricArea = LayoutBounds(textX, label.bottom + 12f, textWidth, area.bottom - label.bottom - 29f)
        layout(lyrics?.lines.orEmpty(), textWidth, size)
        val position = MusicPlayer.positionMs + (song?.let(::lyricOffset) ?: 0L)
        val active = lyrics?.indexAt(position) ?: -1
        val railY = area.y + (area.height - 6 * 40f - 24f) / 2f
        val popup = menu?.let {
            val height = if (it == Menu.SIZE) 82f else 216f
            LayoutBounds(railX - 246f, railY + 260f - height, 232f, height)
        }
        if (rows.isEmpty()) {
            val message = when {
                song == null -> "Play a NetEase song to view lyrics"
                loading -> "Loading lyrics..."
                else -> "No lyrics available"
            }
            ui.text(I18n.text(message), textX, lyricArea.y + lyricArea.height / 2f - 8f, 17f, white.copy(a = 0.66f))
        } else {
            val target = manualPosition?.coerceAtMost(rows.last().center) ?: (rows.getOrNull(active)?.center ?: 0f)
            scrollPosition = ui.approach(scrollPosition, target, 95f)
            drawLyrics(active, size, popup)
        }
        drawShortcuts(railX, railY, song)
        if (popup != null) drawMenu(popup)
    }

    private fun layout(newLines: List<LyricLine>, width: Float, size: Float) {
        val translate = module.lyricsPageTranslation.get()
        val romanize = module.lyricsPageRomanization.get()
        if (lines === newLines && layoutWidth == width && layoutSize == size &&
            showTranslation == translate && showRomanization == romanize) return

        lines = newLines
        layoutWidth = width
        layoutSize = size
        showTranslation = translate
        showRomanization = romanize
        hasTranslation = lines.any { it.translation != null }
        hasRomanization = lines.any { it.romanization != null }
        var top = 0f
        rows = lines.map { line ->
            val original = ui.wrap2(line.text, width, size, Theme.FONT_SEMIBOLD)
            val phonetic = line.annotations(romanize, false).flatMap { ui.wrap2(it, width, size * 0.74f) }
            val translation = line.annotations(false, translate).flatMap { ui.wrap2(it, width, size * 0.74f) }
            val height = original.size * size * 1.32f + (phonetic.size + translation.size) * size + 17f
            Row(top, original, phonetic, translation, height).also { top += height }
        }
    }

    private fun drawLyrics(active: Int, size: Float, popup: LayoutBounds?) {
        val a = lyricArea
        val baseline = a.y + a.height / 2f - scrollPosition
        val first = rows.binarySearchBy(a.y - baseline) { it.top + it.height }
            .let { if (it < 0) -it - 1 else it }
        ui.beginClip(a)
        for (i in first until rows.size) {
            val row = rows[i]
            var y = baseline + row.top
            if (y >= a.bottom) break
            val center = baseline + row.center
            val edge = minOf(center - a.y, a.bottom - center).div(48f).coerceIn(0.22f, 1f)
            val color = white.copy(a = (if (i == active) 1f else 0.53f) * edge)
            for (text in row.text) {
                lyricText(text, y, size, color, Theme.FONT_SEMIBOLD, popup)
                y += size * 1.32f
            }
            val annotationColor = color.copy(a = color.a * 0.85f)
            for (text in row.phonetic + row.translation) {
                lyricText(text, y, size * 0.74f, annotationColor, Theme.FONT_REGULAR, popup)
                y += size
            }
        }
        ui.endClip()
    }

    private fun lyricText(text: String, y: Float, size: Float, color: Color, font: String, popup: LayoutBounds?) {
        // 中文走延迟绘制，弹窗背后的文字也需要在提交时截断。
        val visible = if (popup != null && y + size >= popup.y && y <= popup.bottom) {
            ui.trim(text, popup.x - lyricArea.x - 10f, size, font)
        } else text
        ui.text(visible, lyricArea.x, y, size, color, font)
    }

    private fun drawRecord(cx: Float, cy: Float, diameter: Float, song: NeteaseTrack?) {
        val disc = LayoutBounds(cx - diameter / 2f, cy - diameter / 2f, diameter, diameter)
        ui.card(disc.expand(8f, 8f), white.copy(a = 0.08f), diameter / 2f + 8f)
        ui.card(disc, Color.fromHex("#131318"), diameter / 2f)
        for (i in 0 until 11) {
            val inset = diameter * (0.026f + i * 0.014f)
            val groove = disc.expand(-inset, -inset)
            ui.ctx.drawShape(groove, ShapeStyle(Color.TRANSPARENT, groove.width / 2f, 1f, white.copy(a = 0.06f)))
        }
        val cover = disc.expand(-diameter * 0.165f, -diameter * 0.165f)
        ui.drawCover(cover, song?.let { CoverCache.trackRounded(it, 0.5f) }, cover.width / 2f)
        if (song == null) ui.icon("♫", cx, cy, diameter * 0.19f, white.copy(a = 0.55f))

        val pivot = diameter * 0.10f
        val arm = diameter * 0.26f
        val ctx = ui.ctx
        ctx.pushTranslate(cx + diameter * 0.26f, disc.y - diameter * 0.11f, 0f)
        ctx.pushRotate(if (MusicPlayer.state == MusicPlayer.State.PLAYING && song != null) 143f else 38f, 0f, 0f, 1f)
        ui.card(LayoutBounds(-pivot / 2f, -pivot / 2f, pivot, pivot), Color.fromHex("#e0c8c2"), pivot / 2f)
        ui.card(LayoutBounds(0f, -2.5f, arm, 5f), white, 2.5f)
        ui.card(LayoutBounds(arm - 4f, -5f, 18f, 10f), Color.fromHex("#f6e6e0"), 3f)
        ctx.popTransform()
        ctx.popTransform()
    }

    private fun drawShortcuts(x: Float, y: Float, song: NeteaseTrack?) {
        val seekable = song != null && MusicPlayer.durationMs > 0 &&
            (MusicPlayer.state == MusicPlayer.State.PLAYING || MusicPlayer.state == MusicPlayer.State.PAUSED)
        fun at(index: Int) = LayoutBounds(x, y + 40f * index + if (index >= 2) 24f else 0f, 34f, 34f)
        button(at(0), "↶5", I18n.text("Back 5 seconds"), enabled = seekable) { seek(song, -5000L) }
        button(at(1), "↷5", I18n.text("Forward 5 seconds"), enabled = seekable) { seek(song, 5000L) }
        button(at(2), I18n.text("Phonetic glyph"), I18n.text(if (hasRomanization) "Phonetic lyrics" else "No phonetic lyrics"),
            showRomanization && hasRomanization, hasRomanization) { module.lyricsPageRomanization.toggle() }
        button(at(3), I18n.text("Translation glyph"), I18n.text(if (hasTranslation) "Lyric translation" else "No lyric translation"),
            showTranslation && hasTranslation, hasTranslation) { module.lyricsPageTranslation.toggle() }
        button(at(4), "Tt", I18n.text("Lyrics font size"), menu == Menu.SIZE) { toggleMenu(Menu.SIZE) }
        button(at(5), "…", I18n.text("Lyrics settings"), menu == Menu.MORE) { toggleMenu(Menu.MORE) }
    }

    private fun seek(song: NeteaseTrack?, delta: Long) = ClientMusic.control {
        if (song != null && MusicPlayer.current?.key == song.key) MusicPlayer.seek(MusicPlayer.positionMs + delta)
    }

    private fun toggleMenu(next: Menu) {
        menu = if (menu == next) null else next
    }

    private fun drawMenu(b: LayoutBounds) {
        ui.ctx.drawShape(b, ShapeStyle(Color.fromHex("#513332"), 13f, 1f, white.copy(a = 0.18f)))
        val more = menu == Menu.MORE
        ui.text(I18n.text(if (more) "Lyrics settings" else "Lyrics font size"), b.x + 14f, b.y + 12f, 15f, white, Theme.FONT_SEMIBOLD)
        val size = module.lyricsPageSize.value
        val sizeY = b.y + 37f
        ui.text(I18n.format("Font size: %d", size), b.x + 14f, sizeY + 8f, 13f, white)
        button(LayoutBounds(b.right - 84f, sizeY, 28f, 28f), "−", null, enabled = size > 14) { module.lyricsPageSize.setValue(size - 2) }
        button(LayoutBounds(b.right - 46f, sizeY, 28f, 28f), "+", null, enabled = size < 30) { module.lyricsPageSize.setValue(size + 2) }
        if (more) {
            ui.text(I18n.text("Show phonetic lyrics"), b.x + 14f, b.y + 109f, 13f, white)
            ui.text(I18n.text("Lyric translation"), b.x + 14f, b.y + 78f, 13f, white)
            ui.toggleSwitch(LayoutBounds(b.right - 53f, b.y + 105f, 38f, 19f), showRomanization, b) { module.lyricsPageRomanization.toggle() }
            ui.toggleSwitch(LayoutBounds(b.right - 53f, b.y + 74f, 38f, 19f), showTranslation, b) { module.lyricsPageTranslation.toggle() }
            val song = track
            if (song != null) {
                val offset = lyricOffset(song) / 1000.0
                val label = I18n.format("Lyrics offset · %+.1f s", offset)
                ui.text(label, b.x + 14f, b.y + 143f, 12f, white)
                button(LayoutBounds(b.right - 84f, b.y + 135f, 28f, 28f), "−", null, enabled = offset > -30) {
                    adjustLyricOffset(song, -500L)
                }
                button(LayoutBounds(b.right - 46f, b.y + 135f, 28f, 28f), "+", null, enabled = offset < 30) {
                    adjustLyricOffset(song, 500L)
                }
            }
            ui.pill(LayoutBounds(b.x + 14f, b.y + 174f, b.width - 28f, 28f), white.copy(a = 0.13f), I18n.text("Reset lyrics settings")) {
                if (song != null) adjustLyricOffset(song, -lyricOffset(song))
                module.lyricsPageSize.setValue(22)
                module.lyricsPageRomanization.set(false)
                module.lyricsPageTranslation.set(true)
            }
        }
        ui.hits += b to {}
    }

    private fun button(b: LayoutBounds, glyph: String, tooltip: String?, on: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
        val hovered = ui.hover(b)
        val fill = white.copy(a = if (on) 0.21f else if (hovered && enabled) 0.13f else 0.04f)
        ui.ctx.drawShape(b, ShapeStyle(fill, b.height / 2f, 1f, white.copy(a = if (on) 0.74f else 0.20f)))
        ui.icon(glyph, b.x + b.width / 2f, b.y + b.height / 2f, 15f, white.copy(a = if (enabled) 1f else 0.33f))
        if (enabled) ui.hits += b to action
        if (tooltip != null && hovered && menu == null) {
            val width = ui.width(tooltip, 12f) + 18f
            val x = if (b.x > lyricArea.x) b.x - width - 9f else b.right + 9f
            val tip = LayoutBounds(x, b.y - 24f, width, 22f)
            val box = if (b.x > lyricArea.x) tip else LayoutBounds(x, b.y + 7f, width, 22f)
            ui.card(box, Color.fromHex("#513332"), 7f)
            ui.text(tooltip, box.x + 9f, box.y + 5f, 12f, white)
        }
    }
}
