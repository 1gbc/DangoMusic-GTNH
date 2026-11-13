package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.i18n.I18n

import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.music.NeteasePlaylist
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.utils.Color

import kotlin.math.max
import kotlin.math.min

/** 歌单墙（主页推荐、排行榜）：正方形封面 + 下面两行名字，平滑滚动 */
internal class GridPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 本帧画了歌单墙的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素）
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f
    val atTop get() = target == 0f && pos < 1f

    fun tick() {
        pos = ui.approach(pos, target, 70f)
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -120f else 120f)).coerceIn(0f, maxScroll)
    }

    fun draw(page: LayoutBounds, title: String, sub: String, items: List<NeteasePlaylist>, loading: Boolean,
             sectionFor: ((Long) -> String)? = null, pullOffset: Float = 0f, categoryTiles: Boolean = false,
             wideCards: Boolean = false) {
        ui.headerAnim {
            ui.text(title, page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            if (loading || sub.isNotEmpty())
                ui.text(if (loading) I18n.text("Loading...") else sub, page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            if (loading) ui.progressBar(LayoutBounds(page.x, page.y + 48f, 200f, 3f), null)
        }
        val top = page.y + if (!loading && sub.isEmpty()) 38f else 54f
        area = LayoutBounds(page.x, top, page.width, (page.bottom - top).coerceAtLeast(0f))
        drawGrid(area, items, sectionFor, pullOffset, categoryTiles, wideCards)
    }

    /** 只画封面墙（歌手页的专辑用，标题自己画） */
    fun drawItems(area: LayoutBounds, items: List<NeteasePlaylist>) {
        this.area = area
        drawGrid(area, items)
    }

    private fun drawGrid(area: LayoutBounds, items: List<NeteasePlaylist>, sectionFor: ((Long) -> String)? = null,
                         pullOffset: Float = 0f, categoryTiles: Boolean = false, wideCards: Boolean = false) {
        val gap = if (categoryTiles) 12f else 22f
        val cols = if (wideCards) (if (area.width < 440f) 1 else 2)
            else max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = when {
            categoryTiles -> cw * 0.56f + gap
            wideCards -> cw * 0.57f + 32f
            else -> cw + 46f
        }
        val rows = mutableListOf<Pair<String?, List<NeteasePlaylist>>>()
        if (sectionFor == null) items.chunked(cols).forEach { rows += null to it }
        else items.groupBy { sectionFor(it.id) }.forEach { (title, cards) ->
            rows += title to emptyList()
            cards.chunked(cols).forEach { rows += null to it }
        }
        val totalHeight = rows.sumOf { if (it.first != null) 32.0 else rowH.toDouble() }.toFloat()
        maxScroll = max(0f, totalHeight + ui.bottomInset - area.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)

        // 上下多留一点空间给悬停时放大的边框
        val clip = LayoutBounds(area.x - 6f, area.y - 6f, area.width + 12f, area.height + 6f)
        ui.beginClip(clip)
        var offset = 0f
        var visible = 0
        for ((heading, cards) in rows) {
            val height = if (heading != null) 32f else rowH
            val y = area.y + offset - pos + pullOffset
            offset += height
            if (y + height < area.y || y > area.bottom) continue
            if (heading != null) {
                ui.text(heading,area.x,y+5f,15f,Theme.TEXT_PRIMARY,Theme.FONT_SEMIBOLD)
                continue
            }
            for ((c,p) in cards.withIndex()) {
                val x = area.x + c * (cw + gap)
                ui.itemAnim(visible++) {
                    if (categoryTiles) {
                        val tile = LayoutBounds(x, y, cw, rowH - gap)
                        val color = categoryColors[Math.floorMod(p.id, categoryColors.size.toLong()).toInt()]
                        ui.card(tile, if (ui.hover(tile)) color.copy(a = 0.82f) else color, 13f)
                        ui.text(ui.trim(p.name, cw - 24f, 13f), x + 12f, tile.bottom - 24f, 13f,
                            Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                        ui.hits += tile.intersect(area) to { screen.openGridCard(p) }
                        return@itemAnim
                    }
                    if (wideCards) {
                        val tile = LayoutBounds(x, y, cw, rowH - 12f)
                        ui.card(tile, if (ui.hover(tile)) Theme.CARD_HOVER else Theme.CARD, 15f)
                        val coverSize = tile.height - 16f
                        val cover = LayoutBounds(x + 8f, y + 8f, coverSize, coverSize)
                        ui.drawCover(cover, CoverCache.playlistRounded(p, 0.10f), 12f)
                        val titleX = cover.right + 13f
                        for ((line, label) in ui.wrap2(p.name, tile.right - titleX - 12f, 15f).withIndex()) {
                            ui.text(label, titleX, y + tile.height / 2f - 10f + line * 18f, 15f,
                                Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                        }
                        ui.hits += tile.intersect(area) to { screen.openGridCard(p) }
                        return@itemAnim
                    }
                    val cover = LayoutBounds(x, y, cw, cw)
                    val cell = LayoutBounds(x, y, cw, rowH - 6f)
                    val hov = ui.hover(cell) && clip.contains(ui.mx, ui.my)
                    if (hov) ui.ctx.drawShape(LayoutBounds(x - 5f, y - 5f, cw + 10f, rowH + 2f), ShapeStyle(Theme.CARD_HOVER, 14f))
                    ui.drawCover(cover, CoverCache.playlistRounded(p, 0.08f), 12f)
                    if (hov) {
                        // 悬停：右下角一个播放按钮
                        val pb = LayoutBounds(cover.right - 40f, cover.bottom - 40f, 30f, 30f)
                        ui.ctx.drawShape(pb, ShapeStyle(RED, 15f))
                        ui.icon("▶", pb.x + 16f, pb.y + 15f, 12f, Theme.TEXT_PRIMARY)
                    }
                    val lines = ui.wrap2(p.name, cw - 4f, 12f, Theme.FONT_MEDIUM)
                    lines.forEachIndexed { li, line ->
                        ui.text(line, x + 2f, cover.bottom + 8f + li * 15f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    }
                    val from = ScreenState.page
                    ui.hits += cell.intersect(area) to {
                        if (screen.appleNavigation) screen.openGridCard(p) else screen.openPlaylist(p, from)
                    }
                }
            }
        }
        ui.endClip()
    }

    private val categoryColors = listOf("#B64569", "#227B67", "#485AA3", "#704199", "#A14541", "#356D85").map(Color::fromHex)
}
