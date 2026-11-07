package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.i18n.I18n

import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.music.NeteasePlaylist
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme

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
             sectionFor: ((Long) -> String)? = null) {
        ui.headerAnim {
            ui.text(title, page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(if (loading) I18n.text("Loading...") else sub, page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            if (loading) ui.progressBar(LayoutBounds(page.x, page.y + 48f, 200f, 3f), null)
        }
        area = LayoutBounds(page.x, page.y + 54f, page.width, page.bottom - (page.y + 54f))
        drawGrid(area, items, sectionFor)
    }

    /** 只画封面墙（歌手页的专辑用，标题自己画） */
    fun drawItems(area: LayoutBounds, items: List<NeteasePlaylist>) {
        this.area = area
        drawGrid(area, items)
    }

    private fun drawGrid(area: LayoutBounds, items: List<NeteasePlaylist>, sectionFor: ((Long) -> String)? = null) {
        val gap = 22f
        val cols = max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = cw + 46f
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
            val y = area.y + offset - pos
            offset += height
            if (y + height < area.y || y > area.bottom) continue
            if (heading != null) {
                ui.text(heading,area.x,y+5f,15f,Theme.TEXT_PRIMARY,Theme.FONT_SEMIBOLD)
                continue
            }
            for ((c,p) in cards.withIndex()) {
                val x = area.x + c * (cw + gap)
                ui.itemAnim(visible++) {
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
                    ui.hits += cell.intersect(area) to { screen.openPlaylist(p, from) }
                }
            }
        }
        ui.endClip()
    }
}
