package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.NeteaseApi
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

internal class SongComments(private val ui: Ui) {
    companion object {
        private val worker = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "Dango-Comments").apply { isDaemon = true }
        }
    }

    private class Feed(val songId: Long) {
        @Volatile var page: NeteaseApi.CommentPage? = null
        @Volatile var loading = false
        @Volatile var failed = false
    }

    @Volatile private var feed: Feed? = null
    private var scroll = 0f
    private var target = 0f
    private var maxScroll = 0f
    private var layoutPage: NeteaseApi.CommentPage? = null
    private var layoutWidth = 0f
    private var lines = emptyList<List<String>>()
    private val date = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)

    fun count(songId: Long): Long? {
        val current = feed
        if (current?.songId == songId) return current.page?.total
        val next = Feed(songId)
        feed = next
        request(next)
        scroll = 0f
        target = 0f
        maxScroll = 0f
        return null
    }

    private fun request(feed: Feed) {
        if (feed.loading) return
        val old = feed.page
        feed.failed = false
        feed.loading = true
        worker.execute {
            try {
                if (this.feed !== feed) return@execute
                val next = NeteaseApi.songComments(feed.songId, old?.nextOffset ?: 0)
                feed.page = next.copy(comments = (old?.comments.orEmpty() + next.comments).distinctBy { it.id })
            } catch (_: Exception) {
                feed.failed = true
            } finally {
                feed.loading = false
            }
        }
    }

    fun scroll(wheel: Int, close: () -> Unit) {
        if (wheel > 0 && target <= 0f && scroll < 3f) {
            close()
            return
        }
        target = (target + if (wheel > 0) -90f else 90f).coerceIn(0f, maxScroll)
        val current = feed ?: return
        if (wheel < 0 && target >= maxScroll - 100f && !current.failed && current.page?.more == true) request(current)
    }

    fun draw(area: LayoutBounds, song: NeteaseTrack, close: () -> Unit) {
        count(song.id)
        val current = feed ?: return
        val page = current.page
        val inset = minOf(70f, area.width * 0.08f)
        val x = area.x + inset
        val width = area.width - inset * 2f
        val header = LayoutBounds(area.x + area.width * 0.30f, area.y + 14f, area.width * 0.4f, 48f)
        ui.card(header, Theme.CARD_HOVER, 24f)
        ui.drawCover(LayoutBounds(header.x + 7f, header.y + 6f, 36f, 36f), CoverCache.trackRounded(song, 0.5f), 18f)
        val caption = listOf(song.name, song.artists.joinToString(" / ")).filter { it.isNotBlank() }.joinToString(" · ")
        ui.text(ui.trim(caption, header.width - 76f, 15f), header.x + 51f, header.y + 17f, 15f, Theme.TEXT_PRIMARY)
        PlayerIcons.draw(ui.ctx, PlayerIcons.chevronUp, header.right - 17f, header.y + 24f, 14f, Theme.TEXT_SECONDARY)
        ui.hits += header to close
        val title = I18n.text("All comments") + (page?.total?.let { "  $it" } ?: "")
        ui.text(title, x, area.y + 78f, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

        val list = LayoutBounds(x, area.y + 109f, width, (area.height - 114f).coerceAtLeast(0f))
        val textWidth = (list.width - 58f).coerceAtLeast(20f)
        if (layoutPage !== page || layoutWidth != textWidth) {
            layoutPage = page
            layoutWidth = textWidth
            lines = page?.comments.orEmpty().map { wrap(it.content, textWidth) }
        }
        val height = lines.sumOf { (62 + it.size * 19).toDouble() }.toFloat()
        maxScroll = (height + 45f - list.height).coerceAtLeast(0f)
        target = target.coerceIn(0f, maxScroll)
        scroll = ui.approach(scroll, target, 85f)
        var y = list.y - scroll
        ui.beginClip(list)
        page?.comments.orEmpty().forEachIndexed { index, comment ->
            val body = lines[index]
            val rowHeight = 62f + body.size * 19f
            if (y + rowHeight >= list.y && y < list.bottom) {
                ui.drawCover(LayoutBounds(x, y + 7f, 36f, 36f),
                    CoverCache.rounded("comment:${comment.avatar}", comment.avatar, 0.5f), 18f)
                val textX = x + 48f
                ui.text(ui.trim(comment.nickname, textWidth, 14f), textX, y + 8f, 14f, Theme.TEXT_SECONDARY)
                body.forEachIndexed { n, line -> ui.text(line, textX, y + 30f + n * 19f, 14f, Theme.TEXT_PRIMARY) }
                if (comment.time > 0) ui.text(date.format(Date(comment.time)), textX, y + rowHeight - 23f, 11f, Theme.TEXT_TERTIARY)
                ui.card(LayoutBounds(x, y + rowHeight - 1f, width, 1f), Theme.TRACK, 0f)
            }
            y += rowHeight
        }
        val status = when {
            current.loading -> "Loading comments…"
            current.failed -> "Could not load comments · Retry"
            page?.comments?.isEmpty() == true -> "No comments yet"
            else -> null
        }
        if (status != null && y + 8f < list.bottom) {
            val text = I18n.text(status)
            val b = LayoutBounds(x, y + 5f, width, 34f)
            ui.text(text, x, y + 14f, 13f, Theme.TEXT_SECONDARY)
            if (current.failed) ui.hits += b.intersect(list) to { request(current) }
        }
        ui.endClip()
    }

    private fun wrap(text: String, width: Float): List<String> = text.replace("\r", "").split('\n').flatMap { paragraph ->
        if (paragraph.isEmpty()) return@flatMap listOf("")
        buildList {
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val fit = (1..remaining.length).binarySearchLast { ui.width(remaining.substring(0, it), 14f) <= width }
                var end = fit.coerceAtLeast(1)
                if (end < remaining.length) {
                    val space = remaining.lastIndexOf(' ', end - 1)
                    if (space > end / 2) end = space + 1
                    if (Character.isHighSurrogate(remaining[end - 1])) end = if (end > 1) end - 1 else 2
                }
                add(remaining.substring(0, end).trimEnd())
                remaining = remaining.substring(end)
            }
        }
    }

    private inline fun IntRange.binarySearchLast(test: (Int) -> Boolean): Int {
        var low = first
        var high = last
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (test(middle)) low = middle + 1 else high = middle - 1
        }
        return high
    }
}
