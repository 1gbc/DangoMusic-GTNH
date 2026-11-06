package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule
import me.sorihiko.dangomusicgtnh.module.impls.music.adaptiveLyricLineLimit
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

    private fun lyricLineLimit(size: Float): Int {
        if (!adaptiveLyricLineLimit) return module.lyrics()?.maxLyricLines?.maxRange ?: 1
        val row = size * (1.32f + (if (showTranslation) 1f else 0f) + (if (showRomanization) 1f else 0f)) + 17f
        return (lyricArea.height / row).toInt().coerceIn(1, 8)
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
        val position = if (song != null && lyricModule != null) lyricModule.alignedLyricPosition(song, MusicPlayer.positionMs) else MusicPlayer.positionMs
        val ktv = lyricModule?.ktvMode?.get() == true
        val current = lyrics?.indexAt(position) ?: -1
        val active = if (ktv) lyrics?.ktvIndexAt(position) ?: -1 else current
        val railY = area.y + (area.height - 6 * 40f - 24f) / 2f
        val popup = menu?.let {
            val height = if (it == Menu.SIZE) 82f else 248f
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
            val target = manualPosition?.coerceAtMost(rows.last().center) ?: (rows.getOrNull(if (active >= 0) active else current)?.center ?: 0f)
            scrollPosition = ui.approach(scrollPosition, target, 95f)
            val section = if (ktv && lyricModule?.ktvInstrumental?.get() == true && manualPosition == null)
                lyrics?.instrumentalAt(position.coerceAtLeast(0L), MusicPlayer.durationMs + (song?.let { lyricModule?.lyricOffset(it) } ?: 0L)) else null
            if (section != null) {
                lyricText("【${I18n.text(section.label)}】", lyricArea.y + lyricArea.height * 0.46f - size / 2f, size,
                    Color.fromRGB(lyricModule?.ktvBaseColor?.value ?: 0xD8D8D8), Theme.FONT_SEMIBOLD, popup)
            } else if (!ktv || active >= 0 || manualPosition != null) {
                drawLyrics(active, position, size, popup, lyricModule)
            }
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

    private fun drawLyrics(active: Int, position: Long, size: Float, popup: LayoutBounds?, lyricModule: LyricsModule?) {
        val a = lyricArea
        val maxLines = (lyricModule?.maxLyricLines?.value ?: 1).coerceAtMost(lyricLineLimit(size))
        val start = if (manualPosition == null && active >= 0) active else
            rows.binarySearchBy(scrollPosition) { it.center }.let { if (it < 0) -it - 1 else it }.coerceAtMost(rows.lastIndex)
        val count = if (adaptiveLyricLineLimit) {
            visibleLyricRows(maxLines, rows.size - start, a.height) { rows[start + it].height }
        } else minOf(maxLines, rows.size - start)
        val end = start + count
        val height = rows[end - 1].top + rows[end - 1].height - rows[start].top
        val slack = (a.height - height).coerceAtLeast(0f) / 2f
        val transition = if (manualPosition == null && active >= 0) {
            (rows[active].center - scrollPosition).coerceIn(-slack, slack)
        } else 0f
        val baseline = a.y + slack + transition - rows[start].top
        ui.beginClip(a)
        for (i in start until end) {
            val row = rows[i]
            var y = baseline + row.top
            val center = y + row.height / 2f
            val edge = minOf(center - a.y, a.bottom - center).div(48f).coerceIn(0.22f, 1f)
            val color = white.copy(a = (if (i == active) 1f else 0.53f) * edge)
            val ktv = lyricModule?.takeIf { it.ktvMode.get() }
            val line = lines[i]
            val next = lines.getOrNull(i + 1)?.timeMs ?: line.timeMs + 3500L
            val sung = if (ktv != null && i == active) line.sungCharacters(position, next) else 0f
            var main = sung
            for (text in row.text) {
                if (ktv == null) lyricText(text, y, size, color, Theme.FONT_SEMIBOLD, popup)
                else main = ktvText(ktv, text, y, size, Theme.FONT_SEMIBOLD, color.a, popup, main)
                y += size * 1.32f
            }
            val phoneticSize = size * 0.74f
            val annotationColor = color.copy(a = color.a * 0.85f)
            val total = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)
            var phonetic = sung / total * row.phonetic.sumOf { text -> text.count { !it.isWhitespace() } }
            for (text in row.phonetic) {
                if (ktv == null) lyricText(text, y, phoneticSize, annotationColor, Theme.FONT_REGULAR, popup)
                else phonetic = ktvText(ktv, text, y, phoneticSize, Theme.FONT_REGULAR, annotationColor.a, popup, phonetic)
                y += size
            }
            var translated = sung / total * row.translation.sumOf { text -> text.count { !it.isWhitespace() } }
            for (text in row.translation) {
                if (ktv == null) lyricText(text, y, phoneticSize, annotationColor, Theme.FONT_REGULAR, popup)
                else translated = ktvText(ktv, text, y, phoneticSize, Theme.FONT_REGULAR, annotationColor.a, popup, translated)
                y += size
            }
        }
        ui.endClip()
    }

    private fun ktvText(
        ktv: LyricsModule, text: String, y: Float, size: Float, font: String, alpha: Float,
        popup: LayoutBounds?, sung: Float,
    ): Float {
        val base = Color.fromRGB(ktv.ktvBaseColor.value).copy(a = alpha)
        val shadow = Color(0f, 0f, 0f, alpha * 0.65f)
        if (ktv.ktvBaseShadow.get()) lyricText(text, y + 1f, size, shadow, font, popup)
        lyricText(text, y, size, base, font, popup)
        if (sung <= 0f) return 0f

        var remaining = sung
        var index = 0
        while (index < text.length && (text[index].isWhitespace() || remaining >= 1f)) {
            if (!text[index].isWhitespace()) remaining -= 1f
            index++
        }
        val prefix = ui.width(text.take(index), size, font)
        val width = if (index == text.length) prefix
            else prefix + (ui.width(text.take(index + 1), size, font) - prefix) * remaining
        val top = maxOf(y - 2f, lyricArea.y)
        val bottom = minOf(y + size * 1.32f, lyricArea.bottom)
        if (width > 0f && bottom > top) {
            ui.beginClip(LayoutBounds(lyricArea.x, top, width.coerceAtMost(lyricArea.width), bottom - top))
            if (ktv.ktvSungShadow.get()) lyricText(text, y + 1f, size, shadow, font, popup)
            val mode = ktv.ktvLyricColors.value
            if (mode <= 1) {
                lyricText(text, y, size, Color.fromRGB(ktv.ktvHighlight(0f, 0f)).copy(a = alpha), font, popup)
            } else {
                val fullWidth = ui.width(text, size, font)
                val right = if (popup != null && y + size >= popup.y && y <= popup.bottom)
                    popup.x - lyricArea.x - 10f else lyricArea.width
                var x = 0f
                for ((i, c) in text.withIndex()) {
                    if (x >= minOf(width, right)) break
                    val next = ui.width(text.take(i + 1), size, font)
                    if (!c.isWhitespace() && next <= right) {
                        val color = Color.fromRGB(ktv.ktvHighlight((x + next) / 2f, fullWidth)).copy(a = alpha)
                        ui.text(c.toString(), lyricArea.x + x, y, size, color, font)
                    }
                    x = next
                }
            }
            ui.beginClip(lyricArea)
        }
        return if (index == text.length) remaining else 0f
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
            val limit = module.lyrics()?.maxLyricLines
            if (limit != null) {
                val max = lyricLineLimit(size.toFloat())
                val value = limit.value.coerceAtMost(max)
                ui.text(I18n.text("Simultaneous lyric lines") + " · " + value, b.x + 14f, b.y + 141f, 12f, white)
                button(LayoutBounds(b.right - 84f, b.y + 133f, 28f, 28f), "−", null, enabled = value > 1) {
                    limit.setValue(value - 1)
                }
                button(LayoutBounds(b.right - 46f, b.y + 133f, 28f, 28f), "+", null, enabled = value < max) {
                    limit.setValue(value + 1)
                }
            }
            val lyricModule = module.lyrics()
            val song = track
            if (lyricModule != null && song != null) {
                val offset = lyricModule.lyricOffset(song) / 1000.0
                val label = I18n.format("Lyrics offset · %+.1f s", offset)
                ui.text(label, b.x + 14f, b.y + 175f, 12f, white)
                button(LayoutBounds(b.right - 84f, b.y + 167f, 28f, 28f), "−", null, enabled = offset > -30) {
                    lyricModule.adjustLyricOffset(song, -500L)
                }
                button(LayoutBounds(b.right - 46f, b.y + 167f, 28f, 28f), "+", null, enabled = offset < 30) {
                    lyricModule.adjustLyricOffset(song, 500L)
                }
            }
            ui.pill(LayoutBounds(b.x + 14f, b.y + 206f, b.width - 28f, 28f), white.copy(a = 0.13f), I18n.text("Reset lyrics settings")) {
                if (song != null && lyricModule != null) lyricModule.adjustLyricOffset(song, -lyricModule.lyricOffset(song))
                module.lyrics()?.maxLyricLines?.setValue(1)
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

internal fun visibleLyricRows(requested: Int, available: Int, height: Float, rowHeight: (Int) -> Float): Int {
    var used = 0f
    var count = 0
    while (count < minOf(requested, available)) {
        val next = used + rowHeight(count)
        if (count > 0 && next > height) break
        used = next
        count++
    }
    return count
}
