package me.sorihiko.dangomusicgtnh.musicui

import java.time.LocalDate
import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.music.AppleMusicSource
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.music.NeteasePlaylist
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.utils.Color

internal class AppleDiscoverPage(private val screen: MusicScreen) {
    private val ui = screen.ui
    var area = LayoutBounds()
        private set
    private var target = 0f
    private var position = 0f
    private var maxScroll = 0f

    private class Rail {
        var target = 0f
        var position = 0f
    }
    private val featured = Rail()
    private val releases = Rail()
    private val editorialRails = mutableMapOf<Int, Rail>()

    fun resetScroll() {
        target = 0f
        position = 0f
        featured.target = 0f
        featured.position = 0f
        releases.target = 0f
        releases.position = 0f
        editorialRails.clear()
    }

    fun hide() { area = LayoutBounds() }

    fun scroll(wheel: Int) {
        target = (target + if (wheel > 0) -118f else 118f).coerceIn(0f, maxScroll)
    }

    fun drawEditorial(page: LayoutBounds, title: String, sections: List<AppleMusicSource.EditorialSection>) {
        area = page
        if (page.width <= 0f || page.height <= 0f) return
        val heroColumns = when {
            page.width >= 780f -> 3
            page.width >= 500f -> 2
            else -> 1
        }
        val heroWidth = ((page.width - GAP * (heroColumns - 1) - GAP - 12f) / heroColumns).coerceAtLeast(1f)
        val albumWidth = (page.width / 5.5f).coerceIn(96f, 182f)
        val listWidth = (page.width / 4.15f).coerceIn(175f, 330f).coerceAtMost(page.width)
        var contentHeight = 56f
        ui.beginClip(page)
        try {
            ui.text(I18n.text(title), page.x, page.y - position + 2f, 30f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            for ((index, section) in sections.withIndex()) {
                val y = page.y + contentHeight - position
                val rail = editorialRails.getOrPut(index) { Rail() }
                val isHero = section.layout == "hero"
                val isTracks = section.layout == "tracks"
                val isRows = section.layout == "rows"
                val isLinks = section.layout == "links"
                val itemsPerColumn = if (isTracks) 4 else if (isRows) 2 else 1
                val width = if (isHero) heroWidth else if (isTracks || isRows || isLinks) listWidth else albumWidth
                val cellHeight = when {
                    isHero -> width * 9f / 16f + 61f
                    isTracks -> SONG_ROW
                    isRows -> 58f
                    isLinks -> 44f
                    else -> width + 38f
                }
                val columns = (section.items.size + itemsPerColumn - 1) / itemsPerColumn
                val header = if (section.title.isNotBlank()) 30f else 0f
                val height = header + cellHeight * minOf(itemsPerColumn, section.items.size)
                contentHeight += height + 28f
                if (y + height < page.y || y > page.bottom) continue

                if (header > 0f) ui.text(ui.trim(section.title, (page.width - 72f).coerceAtLeast(0f), 18f,
                    Theme.FONT_SEMIBOLD), page.x, y, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                drawRailButtons(page, if (header > 0f) y - 2f else y - 43f, rail, columns, width)
                val offset = railOffset(rail, columns, width, page.width)
                val songs = if (isTracks) section.items.mapNotNull { it.song } else emptyList()
                for ((itemIndex, item) in section.items.withIndex()) {
                    val x = page.x + itemIndex / itemsPerColumn * (width + GAP) - offset
                    val top = y + header + itemIndex % itemsPerColumn * cellHeight
                    val cell = LayoutBounds(x, top, width, cellHeight)
                    if (!visible(cell)) continue
                    val song = item.song
                    if (isTracks && song != null) {
                        drawSong(cell, song, songs, songs.indexOf(song),
                            itemIndex % itemsPerColumn < itemsPerColumn - 1 && itemIndex + 1 < section.items.size)
                        continue
                    }
                    drawEditorialItem(cell, item, isHero, isRows, isLinks)
                }
            }
        } finally {
            ui.endClip()
        }
        maxScroll = (contentHeight + ui.bottomInset - page.height).coerceAtLeast(0f)
        target = target.coerceIn(0f, maxScroll)
        position = ui.approach(position, target, 80f).coerceIn(0f, maxScroll)
    }

    private fun drawEditorialItem(cell: LayoutBounds, item: AppleMusicSource.EditorialItem, hero: Boolean, row: Boolean, link: Boolean) {
        val name = item.card.name
        when {
            hero -> {
                if (item.heading.isNotBlank())
                    ui.text(ui.trim(item.heading, cell.width, 10f), cell.x, cell.y, 10f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                val lines = ui.wrap2(name, cell.width, 17f, Theme.FONT_SEMIBOLD)
                lines.forEachIndexed { index, line ->
                    ui.text(line, cell.x, cell.y + 15f + index * 19f, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                }
                if (lines.size <= 1 && item.artist.isNotBlank())
                    ui.text(ui.trim(item.artist, cell.width, 13f), cell.x, cell.y + 36f, 13f, Theme.TEXT_SECONDARY)
                val art = LayoutBounds(cell.x, cell.y + 61f, cell.width, cell.height - 61f)
                drawArtwork(art, CoverCache.featuredPlaylist(item.card, item.featuredCover), 13f)
            }
            link -> {
                val button = cell.copy(height = 37f)
                ui.card(button, if (ui.hover(button.intersect(area))) Theme.CARD_HOVER else Theme.CARD, 9f)
                ui.text(ui.trim(name, cell.width - 34f, 12f), cell.x + 10f, cell.y + 11f, 12f, Theme.TEXT_PRIMARY)
                SidebarIcons.draw(ui.ctx, "right", cell.right - 13f, cell.y + 18f, Theme.TEXT_SECONDARY, 13f)
            }
            else -> {
                val size = if (row) 48f else cell.width
                val art = LayoutBounds(cell.x, cell.y, size, size)
                drawArtwork(art, CoverCache.playlist(item.card), if (row) 6f else 9f)
                val textX = if (row) art.right + 10f else cell.x
                val textY = if (row) cell.y + 8f else art.bottom + 5f
                val width = cell.right - textX
                ui.text(ui.trim(name, width, 12f), textX, textY, 12f, Theme.TEXT_PRIMARY)
                if (item.artist.isNotBlank())
                    ui.text(ui.trim(item.artist, width, 11f), textX, textY + 15f, 11f, Theme.TEXT_SECONDARY)
            }
        }
        hit(cell) {
            val song = item.song
            if (item.link.isNotEmpty()) screen.openAppleWebsite(item.link)
            else if (song != null) ClientMusic.control { MusicPlayer.playQueue(listOf(song), 0) }
            else screen.openGridCard(item.card)
        }
    }

    fun draw(page: LayoutBounds, items: List<NeteasePlaylist>, loading: Boolean) {
        area = page
        if (page.width <= 0f || page.height <= 0f) return
        val chart = AppleMusicSource.chartEntry()
        val albums = items.filter { it.id != chart.id }
        val songs = Library.tracks(chart.id).orEmpty()
        val loadingSongs = Library.loadingTracks(chart.id)
        val heroColumns = when {
            page.width >= 780f -> 3
            page.width >= 500f -> 2
            else -> 1
        }
        val preview = if (albums.size > heroColumns) GAP + 12f else 0f
        val heroWidth = ((page.width - GAP * (heroColumns - 1) - preview) / heroColumns).coerceAtLeast(1f)
        val heroHeight = heroWidth * 9f / 16f
        val heroBlock = if (albums.isEmpty()) 0f else heroHeight + 86f
        val songColumns = if (page.width >= 500f) 2 else 1
        val shownSongs = songs.take(songColumns * 4)
        val rowCount = (shownSongs.size + songColumns - 1) / songColumns
        val songBlock = if (shownSongs.isEmpty()) 66f else 46f + rowCount * SONG_ROW
        val albumWidth = (page.width / 4.9f).coerceIn(88f, 168f)
        val albumBlock = if (albums.isEmpty()) 0f else albumWidth + 72f
        maxScroll = (56f + heroBlock + songBlock + albumBlock + ui.bottomInset - page.height).coerceAtLeast(0f)
        target = target.coerceAtMost(maxScroll)
        position = ui.approach(position, target, 80f).coerceAtMost(maxScroll)

        ui.beginClip(page)
        try {
            var y = page.y - position
            ui.text(I18n.text("New discoveries"), page.x, y + 2f, 30f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            y += 56f

            if (albums.isNotEmpty()) {
                val cards = albums.take(10)
                val offset = railOffset(featured, cards.size, heroWidth, page.width)
                drawRailButtons(page, y - 44f, featured, cards.size, heroWidth)
                for ((i, album) in cards.withIndex()) {
                    val x = page.x + i * (heroWidth + GAP) - offset
                    val card = LayoutBounds(x, y, heroWidth, heroHeight + 61f)
                    if (!visible(card)) continue
                    val artist = AppleMusicSource.cardArtist(album.id)
                    ui.text(I18n.text("Popular albums"), x, y, 10f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                    val lines = ui.wrap2(album.name, heroWidth - 4f, 17f, Theme.FONT_SEMIBOLD)
                    lines.forEachIndexed { index, line ->
                        ui.text(line, x, y + 15f + index * 19f, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                    }
                    if (lines.size <= 1 && artist.isNotEmpty())
                        ui.text(ui.trim(artist, heroWidth - 4f, 13f), x, y + 36f, 13f, Theme.TEXT_SECONDARY)
                    val artwork = LayoutBounds(x, y + 61f, heroWidth, heroHeight)
                    drawArtwork(artwork, CoverCache.featuredPlaylist(album, AppleMusicSource.featuredCover(album.id)), 14f)
                    hit(card) { screen.openGridCard(album) }
                }
                y += heroBlock
            }

            val songTitle = if (loading && albums.isEmpty()) "Loading..." else "Popular songs"
            drawHeading(page, y, songTitle, if (songs.isEmpty()) null else { { screen.openGridCard(chart) } })
            if (shownSongs.isEmpty() && !(loading && albums.isEmpty())) {
                ui.text(I18n.text(if (loadingSongs) "Loading..." else "No items"),
                    page.x, y + 29f, 11f, Theme.TEXT_SECONDARY)
            }
            val songWidth = (page.width - GAP * (songColumns - 1)) / songColumns
            for ((index, song) in shownSongs.withIndex()) {
                val row = LayoutBounds(page.x + (index / rowCount) * (songWidth + GAP),
                    y + 27f + (index % rowCount) * SONG_ROW, songWidth, SONG_ROW)
                if (visible(row)) drawSong(row, song, songs, index, (index % rowCount) + 1 < rowCount)
            }
            y += songBlock

            if (albums.isNotEmpty()) {
                val today = LocalDate.now()
                val start = today.minusDays(6).toString()
                val end = today.toString()
                val recent = albums.map { it to AppleMusicSource.releaseDate(it.id) }
                    .filter { (_, date) -> date in start..end }.sortedByDescending { it.second }.map { it.first }
                val cards = recent.ifEmpty { albums.drop(2).ifEmpty { albums } }
                val offset = railOffset(releases, cards.size, albumWidth, page.width)
                drawHeading(page, y, if (recent.isEmpty()) "Popular albums" else "Released this week", null)
                drawRailButtons(page, y - 2f, releases, cards.size, albumWidth)
                for ((i, album) in cards.withIndex()) {
                    val x = page.x + i * (albumWidth + GAP) - offset
                    val artwork = LayoutBounds(x, y + 29f, albumWidth, albumWidth)
                    val card = artwork.copy(height = albumWidth + 36f)
                    if (!visible(card)) continue
                    drawArtwork(artwork, CoverCache.playlist(album), 9f)
                    val artist = AppleMusicSource.cardArtist(album.id)
                    ui.text(ui.trim(album.name, albumWidth, 12f), x, artwork.bottom + 6f, 12f, Theme.TEXT_PRIMARY)
                    if (artist.isNotEmpty())
                        ui.text(ui.trim(artist, albumWidth, 11f), x, artwork.bottom + 21f, 11f, Theme.TEXT_SECONDARY)
                    hit(card) { screen.openGridCard(album) }
                }
            }
        } finally {
            ui.endClip()
        }
    }

    private fun drawHeading(page: LayoutBounds, y: Float, key: String, action: (() -> Unit)?) {
        val title = I18n.text(key)
        val width = ui.width(title, 18f, Theme.FONT_SEMIBOLD)
        ui.text(title, page.x, y, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (action != null) {
            SidebarIcons.draw(ui.ctx, "right", page.x + width + 12f, y + 9f, Theme.TEXT_SECONDARY, 14f)
            hit(LayoutBounds(page.x, y, width + 23f, 24f), action)
        }
    }

    private fun drawSong(row: LayoutBounds, song: NeteaseTrack, queue: List<NeteaseTrack>, index: Int, separator: Boolean) {
        if (ui.hover(row.intersect(area))) ui.card(row, Theme.CARD_HOVER, 7f)
        val artwork = LayoutBounds(row.x, row.y + 4f, 34f, 34f)
        drawArtwork(artwork, CoverCache.track(song), 5f)
        val x = artwork.right + 11f
        val width = row.right - x - 8f
        ui.text(ui.trim(song.name, width, 13f), x, row.y + 6f, 13f, Theme.TEXT_PRIMARY)
        ui.text(ui.trim(song.artists.joinToString(" / "), width, 11f), x, row.y + 22f, 11f, Theme.TEXT_SECONDARY)
        if (separator) ui.card(LayoutBounds(x, row.bottom - 0.5f, row.right - x, 0.5f), Color(1f, 1f, 1f, 0.14f), 0f)
        hit(row) { ClientMusic.control { MusicPlayer.playQueue(queue, index) } }
    }

    private fun drawArtwork(bounds: LayoutBounds, texture: me.sorihiko.dangomusicgtnh.gfx.Texture?, radius: Float) {
        if (texture == null) ui.card(bounds, Theme.CARD, radius)
        else ui.ctx.drawRoundedImage(bounds, texture, radius)
    }

    private fun railLimit(count: Int, cardWidth: Float, viewport: Float) =
        (count * (cardWidth + GAP) - GAP - viewport).coerceAtLeast(0f)

    private fun railOffset(rail: Rail, count: Int, cardWidth: Float, viewport: Float): Float {
        val limit = railLimit(count, cardWidth, viewport)
        rail.target = rail.target.coerceIn(0f, limit)
        rail.position = ui.approach(rail.position, rail.target, 90f).coerceIn(0f, limit)
        return rail.position
    }

    private fun drawRailButtons(page: LayoutBounds, y: Float, rail: Rail, count: Int, cardWidth: Float) {
        val limit = railLimit(count, cardWidth, page.width)
        if (limit < 1f) return
        for (direction in listOf(-1, 1)) {
            val x = page.right - if (direction < 0) 62f else 28f
            val button = LayoutBounds(x, y, 28f, 28f)
            if (!visible(button)) continue
            val enabled = if (direction < 0) rail.target > 0.5f else rail.target < limit - 0.5f
            ui.card(button, if (enabled && ui.hover(button)) Theme.CARD_HOVER else Theme.CARD, 14f)
            SidebarIcons.draw(ui.ctx, if (direction < 0) "left" else "right", x + 14f, y + 14f,
                if (enabled) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, 14f)
            if (enabled) hit(button) { rail.target = (rail.target + direction * (cardWidth + GAP)).coerceIn(0f, limit) }
        }
    }

    private fun visible(bounds: LayoutBounds) =
        bounds.right > area.x && bounds.x < area.right && bounds.bottom > area.y && bounds.y < area.bottom

    private fun hit(bounds: LayoutBounds, action: () -> Unit) {
        if (visible(bounds)) ui.hits += bounds.intersect(area) to action
    }

    private companion object {
        const val GAP = 16f
        const val SONG_ROW = 42f
    }
}
