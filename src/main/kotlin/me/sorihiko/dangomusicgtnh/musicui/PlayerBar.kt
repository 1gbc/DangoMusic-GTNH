package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n

import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
import me.sorihiko.dangomusicgtnh.music.CoverArt
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.music.MusicScreen
import me.sorihiko.dangomusicgtnh.music.MusicSource
import me.sorihiko.dangomusicgtnh.music.NeteaseApi
import me.sorihiko.dangomusicgtnh.music.NeteaseQuality
import me.sorihiko.dangomusicgtnh.music.NeteaseStream
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.ShadowStyle
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.ui.UiStyle
import me.sorihiko.dangomusicgtnh.ui.Vec2
import me.sorihiko.dangomusicgtnh.utils.Color

import kotlin.math.max
import kotlin.math.roundToInt

/** 底部播放栏：左边当前歌曲，中间控制按钮 + 进度条，右边单曲循环 / 随机 + 音量 */
internal class PlayerBar(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private var lastPlaybackNotice = ""
    private var playbackNotice = ""
    private var playbackNoticeUntil = 0L
    private var progressHit = LayoutBounds()
    private var lastSkipNotice = 0L
    private var progRect = LayoutBounds()
    private var volRect = LayoutBounds()
    private var volumeOpen = false
    private var volumeDragging = false
    private var volumeButton = LayoutBounds()
    private var volumeMenu = LayoutBounds()
    private var qualityOpen = false
    private var qualityButton = LayoutBounds()
    private var qualityMenu = LayoutBounds()
    /** 正在拖进度条：拖的时候只移动显示的位置，松开才真正跳过去（拖动过程中不反复重新解码） */
    private var seekTrack: String? = null
    private var seekFraction = 0f
    private var showTimeUntil = 0L
    private var progressCover: java.awt.image.BufferedImage? = null
    private var progressColors = emptyList<Color>()

    /** 一个按钮的动画：悬停底圈渐显、点击先缩再弹回、开关类按钮的选中颜色渐变 */
    private class Anim {
        val hover = Spring(300f, 26f)
        val press = Spring(420f, 13f)
        val on = Spring(240f, 24f)
    }
    private val prevA = Anim()
    private val modeA = Anim()
    private val stopA = Anim()
    private var modeOpen = false
    private var modeMenu = LayoutBounds()
    private var modeButton = LayoutBounds()
    private val playA = Anim()
    private val nextA = Anim()
    private val queueA = Anim()
    private val volumeA = Anim()
    private val modeMotion = PopupMotion()
    private val volumeMotion = PopupMotion()
    private val qualityMotion = PopupMotion()
    private var hitStart = 0
    /** 播放 ↔ 暂停图标的过渡 */
    private val playMorph = Spring(320f, 26f)
    /** 进度条上的圆钮：鼠标移上去时渐显 */
    private val progKnob = Spring(300f, 26f)

    fun draw(bar: LayoutBounds, lyricsOpen: Boolean) {
        val ctx = ui.ctx
        hitStart = ui.hits.size
        // 播放顺序 / 音量 / 音质菜单：两种界面风格用同一套弹出动画（外观见 PopupLook）
        modeMotion.step(modeOpen,ui.frameDt/1000f)
        volumeMotion.step(volumeOpen,ui.frameDt/1000f)
        qualityMotion.step(qualityOpen,ui.frameDt/1000f)
        if (!lyricsOpen) drawPanel(bar)
        val snap = PlaybackView.snapshot()
        val t = snap.track
        val ry = bar.y + bar.height / 2f + 3f

        // 左：封面 + 歌名 / 歌手
        val cs = 48f
        // Center the complete control group, including mode and stop buttons.
        val modeOffset = MODE_BUTTON_OFFSET
        val cx = bar.x + bar.width / 2f - (154f - modeOffset) / 2f
        val showHeart = t != null && t.source == MusicSource.NETEASE && !snap.remote
        val hasComments = showHeart && module.songCommentsEnabled.get()
        val canLike = showHeart && NeteaseApi.loggedIn
        val leftLimit = cx - modeOffset - 4f
        // 窄窗口里先把封面往左收，给标题、收藏和心动模式保留各自的点击范围。
        val buttonWidth = if (hasComments) 76f else if (showHeart) 31f else 0f
        val infoReserve = if (showHeart) buttonWidth + 34f else 48f
        val coverInset = (leftLimit - bar.x - cs - 12f - infoReserve).coerceIn(12f, PROGRESS_INSET)
        val coverBox = LayoutBounds(bar.x + coverInset, ry - cs / 2f, cs, cs)
        val showCover = !lyricsOpen && (!showHeart || leftLimit - coverBox.right >= buttonWidth + 30f + if (snap.loading) 25f else 0f)
        if (showCover) {
            ui.drawCover(coverBox, t?.let { CoverCache.trackRounded(it, 0.27f) }, 13f)
            ctx.drawShape(coverBox, ShapeStyle(Color.TRANSPARENT, 13f, 1f, Color(1f, 1f, 1f, 0.10f)))
            if (screen.canOpenLyrics && !snap.remote) {
                ui.hits += coverBox to screen::toggleLyrics
                if (ui.hover(coverBox)) {
                    ui.card(coverBox, Color(0f, 0f, 0f, 0.47f), 13f)
                    ui.icon("♫", coverBox.x + cs / 2f, coverBox.y + cs / 2f, 23f, Theme.TEXT_PRIMARY)
                }
            }
        }
        val infoX = if (showCover) coverBox.right + 12f else bar.x + 14f
        // 窗口窄时歌名区跟着缩短，别压到中间的按钮上（红心也要留位置）
        val actionWidth = buttonWidth + if (snap.loading) 25f else 0f
        val infoW = (leftLimit - infoX - actionWidth - 8f).coerceIn(0f, 220f)
        val title = t?.name ?: I18n.text("Not playing")
        val shownTitle = ui.trim(title, infoW, 13f, Theme.FONT_SEMIBOLD)
        val titleY = coverBox.y + 9f
        val titleWidth = ui.width(shownTitle, 13f, Theme.FONT_SEMIBOLD)
        val titleBounds = LayoutBounds(infoX, titleY - 2f, titleWidth.coerceAtMost(infoW), 18f)
        ui.text(shownTitle, infoX, titleY, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        if (lyricsOpen && titleBounds.width > 0f) ui.hits += titleBounds to screen::toggleLyrics
        val sub = when {
            t == null -> "-"
            snap.remote -> snap.sourceLabel + " · " + t.artists.joinToString(" / ")
            else -> t.artists.joinToString(" / ")
        }
        val skipAt = if (snap.remote) 0L else MusicPlayer.skipNotice
        if (lastSkipNotice != skipAt) {
            lastSkipNotice = skipAt
            val remaining = 5_000_000_000L - (System.nanoTime() - skipAt)
            // 重开界面不重放过期事件，也不延长仍在显示的提示。
            if (skipAt != 0L && remaining > 0L) Library.showTemporaryStatus(
                I18n.text("This song could not be played. Skipped to the next song."), remaining / 1_000_000L)
        }
        val notice = when {
            snap.loading -> ""
            !snap.remote && MusicPlayer.state == MusicPlayer.State.ERROR -> MusicPlayer.message
            else -> snap.message
        }
        if (notice != lastPlaybackNotice) {
            lastPlaybackNotice = notice
            if (notice.isNotBlank()) {
                playbackNotice = I18n.message(notice)
                playbackNoticeUntil = System.nanoTime() + 3_500_000_000L
            }
        }
        if (t != null && !snap.remote && t.source != MusicSource.APPLE) {
            drawArtistLinks(screen, t, infoX, coverBox.y + 27f, infoW, 11f, Theme.TEXT_SECONDARY)
        } else ui.text(ui.trim(sub, infoW, 11f), infoX, coverBox.y + 27f, 11f, Theme.TEXT_SECONDARY)
        val artistWidth = ui.width(sub, 11f)
        val actionX = infoX + maxOf(titleWidth, artistWidth).coerceAtMost(infoW) + 6f

        if (t != null && showHeart) {
            val y = coverBox.y + 12f
            if (canLike) Library.ensureLiked()
            val liked = canLike && NeteaseApi.isLiked(t.id)
            val heart = LayoutBounds(actionX, y, 30f, 26f)
            val hover = ui.hover(heart)
            val color = if (liked) RED else Theme.TEXT_SECONDARY
            val outline = if (hover) PlayerIcons.heartBoldOutline else PlayerIcons.heartOutline
            if (liked) PlayerIcons.draw(ctx, PlayerIcons.heart, heart.x + 13f, heart.y + 13f, 19f, color)
            if (!liked || hover) PlayerIcons.draw(ctx, outline, heart.x + 13f, heart.y + 13f, 19f, color)
            if (canLike) ui.hits += heart to { Library.toggleLike(t) }

            if (hasComments) {
                val comment = LayoutBounds(heart.right + 3f, y, 42f, 26f)
                PlayerIcons.draw(ctx, PlayerIcons.comments, comment.x + 12f, comment.y + 13f, 18f,
                    if (ui.hover(comment)) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
                screen.songComments.count(t.id)?.let { count ->
                    val label = if (count >= 10000) "${count / 10000}w+" else if (count > 999) "999+" else count.toString()
                    ui.text(label, comment.x + 20f, comment.y + 1f, 8f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                }
                ui.hits += comment to screen::openSongComments
            }
        }

        if (snap.loading) {
            val ringX = actionX + buttonWidth + 11f
            val percent = downloadPercent.find(snap.message)?.groupValues?.get(1)?.toIntOrNull()
            PlayerIcons.loadingRing(ctx, ringX, coverBox.y + 25f, percent)
        }

        // 中：上一首 / 播放 / 下一首 / 队列。点击区域固定，动画只改画出来的样子
        val cy = ry
        val playB = LayoutBounds(cx - 24f, cy - 24f, 48f, 48f)
        val prevB = LayoutBounds(cx - 62f - 16f, cy - 16f, 32f, 32f)
        val nextB = LayoutBounds(cx + 62f - 16f, cy - 16f, 32f, 32f)
        val queueB = LayoutBounds(cx + 88f, cy - 16f, 32f, 32f)
        val stopB = LayoutBounds(queueB.right + 2f, cy - 16f, 32f, 32f)
        modeButton = LayoutBounds(cx - modeOffset, cy - 16f, 32f, 32f)
        val mode = if (snap.remote) MusicPlayer.Mode.SEQUENTIAL else MusicPlayer.mode
        toggleButton(modeButton, cy, modeOpen, modeA, mode) {
            if (snap.remote) return@toggleButton
            val opening = !modeOpen
            dismissMenu()
            modeOpen = opening
        }
        val stopScale = button(stopB, stopA)
        PlayerIcons.draw(ctx, PlayerIcons.stop, stopB.x + 16f, cy, (if (UiStyle.continuous) 18f else 16f) * stopScale, Theme.TEXT_PRIMARY)
        ui.hits += stopB to { stopA.press.v -= 7f; PlaybackView.stop() }
        val kPrev = button(prevB, prevA)
        val kPlay = drawPlayDisc(playB)
        val kNext = button(nextB, nextA)
        val kQueue = button(queueB, queueA)
        // 播放 ↔ 暂停：两个图标交叉淡入淡出，暂停的两道竖条从中间长出来，三角形收小淡出
        val p = playMorph.tick(if (snap.playing) 1f else 0f).coerceIn(0f, 1f)
        if (p > 0.01f) {
            ctx.pushOpacity(p)
            val bh = (if (UiStyle.continuous) 18f else 16f) * kPlay * (0.55f + 0.45f * p)
            ui.card(LayoutBounds(cx - 6f * kPlay, cy - bh / 2f, 4f * kPlay, bh), Theme.TEXT_PRIMARY, 1.5f)
            ui.card(LayoutBounds(cx + 2f * kPlay, cy - bh / 2f, 4f * kPlay, bh), Theme.TEXT_PRIMARY, 1.5f)
            ctx.popOpacity()
        }
        if (p < 0.99f) {
            ctx.pushOpacity(1f - p)
            PlayerIcons.draw(ctx, PlayerIcons.play, cx + (if (UiStyle.continuous) 0f else 1f), cy, 26f * kPlay * (1f - 0.35f * p), Theme.TEXT_PRIMARY)
            ctx.popOpacity()
        }
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条（围绕按钮中心缩放）
        val pc = prevB.x + prevB.width / 2f
        val nc = nextB.x + nextB.width / 2f
        PlayerIcons.draw(ctx, PlayerIcons.previous, pc, cy, 24f * kPrev, Theme.TEXT_PRIMARY)
        PlayerIcons.draw(ctx, PlayerIcons.next, nc, cy, 24f * kNext, Theme.TEXT_PRIMARY)
        val q = queueA.on.tick(if (ScreenState.page == Page.QUEUE) 1f else 0f)
        PlayerIcons.draw(ctx, PlayerIcons.queue, queueB.x + 16f, cy, 20f * kQueue, mixColor(Theme.TEXT_PRIMARY, RED, q))
        ui.hits += prevB to { prevA.press.v -= 7f; if (!snap.remote) ClientMusic.control { MusicPlayer.prev() } }
        ui.hits += playB to { playA.press.v -= 7f; PlaybackView.togglePause() }
        ui.hits += nextB to { nextA.press.v -= 7f; if (!snap.remote) ClientMusic.control { MusicPlayer.next() } }
        ui.hits += queueB to {
            queueA.press.v -= 7f
            if (!snap.remote) {
                if (ScreenState.page == Page.QUEUE) screen.goBackOrHome() else screen.go(Page.QUEUE)
            }
        }

        drawProgress(bar, snap, lyricsOpen)
        if (System.nanoTime() < playbackNoticeUntil) {
            val font = Theme.FONT_SEMIBOLD
            val text = ui.trim(playbackNotice, (bar.width - 28f).coerceAtLeast(0f), 11f, font)
            val w = ui.width(text, 11f, font) + 20f
            val toast = LayoutBounds(bar.x + (bar.width - w) / 2f, bar.y - 58f, w, 24f)
            ui.card(toast, Color(0.05f, 0.06f, 0.08f, 0.95f), 12f)
            ui.text(text, toast.x + 10f, toast.y + 7f, 11f, Theme.TEXT_PRIMARY, font)
        }

        drawVolume(bar, ry)
        // 只有自己放网易云时才有音质可选；换了音源或者在听别人的歌时收起菜单
        val qualityAvailable = me.sorihiko.dangomusicgtnh.music.MusicSources.selected == MusicSource.NETEASE && !PlaybackView.snapshot().remote
        if (qualityAvailable) drawQuality(bar, ry) else {
            if (qualityOpen) dismissMenu()
            // 菜单不画了：动画和位置一起清掉，免得收起动画期间还在吃点击
            qualityMotion.snap(false)
            qualityMenu = LayoutBounds()
        }
        if (qualityAvailable && (qualityOpen || qualityMotion.visible)) drawQualityMenu(bar)
        if (volumeOpen || volumeMotion.visible) drawVolumeMenu(bar)
        if (modeOpen || modeMotion.visible) {
            if (UiStyle.continuous) drawContinuousModeMenu(bar, mode) else drawClassicModeMenu(bar, mode)
        }
    }

    private fun drawVolume(bar: LayoutBounds, cy: Float) {
        if (UiStyle.continuous) {
            val inset = if (screen.canOpenLyrics) 70f else 13f
            val speakerBox=LayoutBounds(bar.right-inset-32f,cy-16f,32f,32f)
            val volume = MusicPlayer.volume.coerceIn(0f, 1f)
            val color = if (volume < 0.1f || volumeOpen) RED else Theme.TEXT_SECONDARY
            val percent = "${(volume * 100f).roundToInt()}%"
            val textX=speakerBox.x-2f-ui.width(percent,10f,Theme.FONT_MEDIUM)
            volumeButton=LayoutBounds(textX-6f,cy-16f,speakerBox.right-textX+6f,32f)
            val k=button(volumeButton,volumeA)
            ui.text(percent,textX,cy-5f,10f,color,Theme.FONT_MEDIUM)
            PlayerIcons.speaker(ui.ctx,speakerBox.x+16f,cy,color,0.75f*k,
                if(volume>0.5f) 2 else 1,volume<=0f)
            ui.hits += volumeButton to { val opening = !volumeOpen; dismissMenu(); volumeOpen = opening }
            return
        }
        val inset = if (screen.canOpenLyrics) 70f else 13f
        volumeButton = LayoutBounds(bar.right - inset - 32f, cy - 16f, 32f, 32f)
        val volume = MusicPlayer.volume.coerceIn(0f, 1f)
        val k = button(volumeButton, volumeA)
        if (volumeOpen) ui.card(volumeButton.scaled(k), RED.copy(a = 0.30f), volumeButton.height / 2f)
        PlayerIcons.speaker(ui.ctx, volumeButton.x + 16f, cy, Theme.TEXT_SECONDARY, 0.78f * k,
            if (volume > 0.5f) 2 else 1, volume <= 0f)
        // 音量百分比：音量很低时（比如 3%）一眼就能看出来，不会以为没声音
        val percent = "${(volume * 100f).roundToInt()}%"
        val low = volume < 0.1f
        ui.text(percent, volumeButton.x - 2f - ui.width(percent, 10f, Theme.FONT_MEDIUM), cy - 5f, 10f,
            if (low) RED else Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
        ui.hits += volumeButton to {
            val opening = !volumeOpen
            dismissMenu()
            volumeOpen = opening
        }
    }

    private fun drawVolumeMenu(bar: LayoutBounds) {
        if (UiStyle.continuous) drawContinuousVolumeMenu(bar) else drawClassicVolumeMenu(bar)
    }

    /**
     * 经典风格的音量菜单：喇叭正上方的一条竖向浅色玻璃，上面是百分比，中间一道竖滑条（强调色填充、白色圆钮）。
     * 从喇叭按钮里展开（和其他菜单同一种动画）
     */
    private fun drawClassicVolumeMenu(bar: LayoutBounds) {
        val width = 52f
        val height = 178f
        val target = LayoutBounds((volumeButton.x + volumeButton.width / 2f - width / 2f).coerceAtMost(bar.right - width - 8f).coerceAtLeast(bar.x + 8f),
            max(8f, bar.y - height - 8f), width, height)
        volumeMenu = drawPopup(volumeButton, target, volumeMotion, volumeOpen) { content, interactive, clip ->
            val volume = MusicPlayer.volume.coerceIn(0f, 1f)
            val cx = content.x + content.width / 2f
            val percent = "${(volume * 100f).roundToInt()}%"
            ui.text(percent, cx - ui.width(percent, 11f, Theme.FONT_SEMIBOLD) / 2f, content.y + 12f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            volRect = LayoutBounds(cx - 2.5f, content.y + 52f, 5f, content.height - 98f)
            ui.card(volRect, Color(1f, 1f, 1f, 0.22f), 2.5f)
            val fill = volRect.height * volume
            val y = volRect.bottom - fill
            if (fill > 0f) ui.card(LayoutBounds(volRect.x, y, volRect.width, max(5f, fill)), RED, 2.5f)
            ui.ctx.drawShape(LayoutBounds(cx - 7f, y - 7f, 14f, 14f), ShapeStyle(Theme.TEXT_PRIMARY, 7f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.25f), 4f, Vec2(0f, -1f))))
            drawVolumeEndpoint(cx, content.bottom - 17f, 0f, interactive, clip)
            drawVolumeEndpoint(cx, content.y + 30f, 1f, interactive, clip)
            if (interactive) ui.hits += volRect.expand(16f, 8f).intersect(clip) to {
                volumeDragging = true
                setVolumeAt(ui.mx, ui.my)
            }
        }
    }

    /**
     * 经典风格的播放顺序菜单：播放顺序按钮正上方，三行（图标 + 文字），当前的那一行浅色底、强调色图标。
     * 从按钮里展开（和其他菜单同一种动画）
     */
    private fun drawClassicModeMenu(bar: LayoutBounds, mode: MusicPlayer.Mode) {
        val width = 156f
        val rowH = 32f
        val height = 6f + MusicPlayer.Mode.values().size * rowH + 6f
        val target = LayoutBounds((modeButton.x + modeButton.width / 2f - width / 2f).coerceAtMost(bar.right - width - 8f).coerceAtLeast(bar.x + 8f),
            max(8f, bar.y - height - 8f), width, height)
        modeMenu = drawPopup(modeButton, target, modeMotion, modeOpen) { content, interactive, clip ->
            for ((i, option) in MusicPlayer.Mode.values().withIndex()) {
                val row = LayoutBounds(content.x + 6f, content.y + 6f + i * rowH, content.width - 12f, rowH - 2f)
                val selected = option == mode
                if (selected) ui.card(row, PopupLook.selected, PopupLook.rowRadius(row.height))
                else if (interactive && ui.hover(row)) ui.card(row, PopupLook.hover, PopupLook.rowRadius(row.height))
                val cy = row.y + row.height / 2f
                PlayerIcons.mode(ui.ctx, option, row.x + 18f, cy, 18f, if (selected) RED else Theme.TEXT_PRIMARY)
                ui.text(I18n.text(option.label), row.x + 36f, cy - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY,
                    if (selected) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
                if (interactive) ui.hits += row.intersect(clip) to { module.selectPlaybackMode(option) }
            }
        }
    }

    private fun drawContinuousModeMenu(bar: LayoutBounds, mode: MusicPlayer.Mode) {
        val width = 212f
        val target = LayoutBounds((modeButton.x - 10f).coerceIn(bar.x + 8f, bar.right - width - 8f), max(8f, bar.y - 104f), width, 96f)
        modeMenu = drawPopup(modeButton,target,modeMotion,modeOpen) { content, interactive, clip ->
            ui.text(I18n.text("Playback mode"),content.x+14f,content.y+12f,11f,Theme.TEXT_SECONDARY,Theme.FONT_MEDIUM)
            for ((i,option) in MusicPlayer.Mode.values().withIndex()) {
                val pill=LayoutBounds(content.x+12f+i*66f,content.y+32f,56f,32f)
                val selected=option==mode
                ui.card(pill,if(selected) Color(1f,1f,1f,0.16f) else if(interactive && ui.hover(pill)) Theme.CARD_HOVER else Theme.CARD,16f)
                PlayerIcons.mode(ui.ctx,option,pill.x+28f,pill.y+16f,20f,if(selected) RED else Theme.TEXT_PRIMARY)
                val text=ui.trim(I18n.text(option.label),60f,9.5f)
                ui.text(text,pill.x+(pill.width-ui.width(text,9.5f))/2f,pill.bottom+8f,9.5f,if(selected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
                if(interactive) ui.hits += LayoutBounds(pill.x-3f,pill.y,62f,53f).intersect(clip) to { module.selectPlaybackMode(option) }
            }
        }
    }

    private fun drawContinuousVolumeMenu(bar: LayoutBounds) {
        val width = 228f
        val target=LayoutBounds((volumeButton.right-width).coerceIn(bar.x+8f,bar.right-width-8f),max(8f,bar.y-100f),width,92f)
        volumeMenu=drawPopup(volumeButton,target,volumeMotion,volumeOpen) { content,interactive,clip ->
            val volume=MusicPlayer.volume.coerceIn(0f,1f)
            val percent="${(volume*100f).roundToInt()}%"
            ui.text(I18n.text("Volume"),content.x+14f,content.y+13f,12f,Theme.TEXT_PRIMARY,Theme.FONT_MEDIUM)
            ui.text(percent,content.right-14f-ui.width(percent,11f),content.y+14f,11f,Theme.TEXT_SECONDARY)
            val cy=content.y+57f
            drawVolumeEndpoint(content.x + 20f, cy, 0f, interactive, clip)
            drawVolumeEndpoint(content.right - 21f, cy, 1f, interactive, clip)
            volRect=LayoutBounds(content.x+42f,cy-2f,content.width-84f,4f)
            ui.card(volRect,Color(1f,1f,1f,0.18f),2f)
            val fill=volRect.width*volume
            if(fill>0f) ui.card(LayoutBounds(volRect.x,volRect.y,max(4f,fill),volRect.height),Theme.TEXT_PRIMARY.copy(a=0.85f),2f)
            ui.card(LayoutBounds(volRect.x+fill-5f,cy-5f,10f,10f),Theme.TEXT_PRIMARY,5f)
            if(interactive) ui.hits += volRect.expand(8f,12f).intersect(clip) to { volumeDragging=true; setVolumeAt(ui.mx,ui.my) }
        }
    }

    private fun drawPopup(anchor: LayoutBounds,target: LayoutBounds,motion: PopupMotion,open: Boolean,
        content: (LayoutBounds,Boolean,LayoutBounds)->Unit): LayoutBounds {
        val b=motion.bounds(anchor,target)
        val p=motion.progress.coerceIn(0f,1f)
        if(p<=0.002f) return b
        val before=ui.hits.size
        val trigger=ui.hits.subList(hitStart.coerceAtMost(before),before).firstOrNull { it.first === anchor }
        ui.ctx.pushOpacity(p)
        PopupLook.panel(ui.ctx, b)
        ui.ctx.popOpacity()
        ui.beginClip(b)
        ui.ctx.pushOpacity(motion.contentOpacity)
        try {
            ui.popup(b) { content(target.copy(y=target.y+10f*(1f-p)),open && p>0.6f,b) }
        } finally { ui.ctx.popOpacity(); ui.endClip() }
        ui.hits += b to {}
        // Animated glass can overlap transport controls. Keep popup hits above them,
        // while its own trigger remains clickable for a quick close/reopen.
        val popupHits=ui.hits.subList(before,ui.hits.size).toList()
        repeat(popupHits.size) { ui.hits.removeAt(before) }
        ui.hits.addAll(hitStart.coerceAtMost(ui.hits.size),if(trigger!=null) listOf(trigger)+popupHits else popupHits)
        return b
    }

    private fun drawQuality(bar: LayoutBounds, y: Float) {
        if (PlaybackView.snapshot().remote) return
        val preferred = module.preferredQuality
        val stream = MusicPlayer.neteaseStream
        val badge = LayoutBounds(bar.right - 62f, y - 14f, 49f, 28f)
        qualityButton = badge
        val label = stream?.quality?.let { I18n.text(it.label) }
            ?: stream?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000}k" }
            ?: I18n.text("Quality")
        val color = if (qualityOpen || ui.hover(badge)) RED else Theme.TEXT_PRIMARY
        ui.ctx.drawShape(badge, ShapeStyle(Theme.TRACK, 7f, 1f, color.copy(a = 0.42f)))
        val size = 10f
        val badgeText = ui.trim(label, badge.width - 6f, size, Theme.FONT_SEMIBOLD)
        ui.text(badgeText, badge.x + (badge.width - ui.width(badgeText, size, Theme.FONT_SEMIBOLD)) / 2f,
            badge.y + 8f, size, color, Theme.FONT_SEMIBOLD)
        ui.hits += badge to {
            val opening = !qualityOpen
            dismissMenu()
            qualityOpen = opening
        }
    }

    /**
     * 音质菜单（两种风格共用，外观见 PopupLook）：和播放顺序、音量菜单同一种弹出动画（从音质徽章里展开，内容淡入、上浮），
     * 当前流 / 输出 / 请求的音质三行说明，下面是可选的音质，选中的一项高亮并打勾
     */
    private fun drawQualityMenu(bar: LayoutBounds) {
        val preferred = module.preferredQuality
        val stream = MusicPlayer.neteaseStream
        val tiers = NeteaseQuality.values().reversed()
        val width = 260f
        val rowH = 30f
        val height = 92f + tiers.size * rowH + 8f
        val target = LayoutBounds((qualityButton.right - width).coerceAtMost(bar.right - width - 8f).coerceAtLeast(bar.x + 8f), max(8f, bar.y - height - 8f), width, height)
        qualityMenu = drawPopup(qualityButton, target, qualityMotion, qualityOpen) { content, interactive, clip ->
            ui.text(I18n.text("Playback quality"), content.x + 14f, content.y + 12f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            val now = when {
                stream != null -> I18n.format("Current stream · %s", streamDescription(stream))
                MusicPlayer.state == MusicPlayer.State.LOADING && MusicPlayer.current?.source == MusicSource.NETEASE -> I18n.text("Loading current stream…")
                else -> I18n.text("No active NetEase stream")
            }
            ui.text(ui.trim(now, content.width - 28f, 11.5f), content.x + 14f, content.y + 32f, 11.5f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            val output = MusicPlayer.outputInfo
            val details = if (output != null) {
                val depth = output.sourceBits?.let { "$it → ${output.bits}" } ?: output.bits.toString()
                val backend = I18n.text(if (output.javaSound) "Java Sound" else "Game audio (OpenAL)")
                I18n.format("Output · %s-bit · %d Hz · %s", depth, output.rate, backend)
            } else I18n.text("Audio output inactive")
            ui.text(ui.trim(details, content.width - 28f, 9.5f), content.x + 14f, content.y + 50f, 9.5f, Theme.TEXT_SECONDARY)
            ui.text(I18n.format("Requested · %s", I18n.text(preferred.label)), content.x + 14f, content.y + 64f, 9.5f, Theme.TEXT_SECONDARY)
            ui.ctx.drawShape(LayoutBounds(content.x + 14f, content.y + 84f, content.width - 28f, 1f), ShapeStyle(PopupLook.divider, 0.5f))
            for ((i, tier) in tiers.withIndex()) {
                val row = LayoutBounds(content.x + 8f, content.y + 92f + i * rowH, content.width - 16f, rowH - 4f)
                val selected = tier == preferred
                if (selected) ui.card(row, PopupLook.selected, PopupLook.rowRadius(row.height))
                else if (interactive && ui.hover(row)) ui.card(row, PopupLook.hover, PopupLook.rowRadius(row.height))
                ui.text(I18n.text(tier.label), row.x + 14f, row.y + row.height / 2f - 12f * 0.39f - 0.5f, 12f,
                    if (selected) RED else Theme.TEXT_PRIMARY, if (selected) Theme.FONT_SEMIBOLD else Theme.FONT_MEDIUM)
                if (selected) PlayerIcons.draw(ui.ctx, PlayerIcons.check, row.right - 16f, row.y + row.height / 2f, 14f, RED)
                if (interactive) ui.hits += row.intersect(clip) to { module.selectQuality(tier) }
            }
        }
    }

    private fun streamDescription(stream: NeteaseStream): String {
        return listOfNotNull(
            stream.quality?.let { I18n.text(it.label) },
            stream.format.takeIf { it.isNotBlank() }?.uppercase(java.util.Locale.ROOT),
            stream.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        ).joinToString("  ").ifEmpty { I18n.text("Unknown quality") }
    }

    fun dismissMenu(): Boolean {
        if (!qualityOpen && !modeOpen && !volumeOpen && !modeMotion.visible && !volumeMotion.visible && !qualityMotion.visible) return false
        volumeOpen = false
        volumeDragging = false
        modeOpen = false
        qualityOpen = false
        return true
    }

    fun menuContains(x: Int, y: Int) = ((qualityOpen || qualityMotion.visible) && qualityMenu.contains(x, y)) ||
        ((modeOpen || modeMotion.visible) && modeMenu.contains(x,y)) ||
        ((volumeOpen || volumeMotion.visible) && volumeMenu.contains(x,y))

    fun closeMenuOutside(x: Int, y: Int) {
        if (!menuContains(x, y) && !(qualityOpen && qualityButton.contains(x, y)) &&
            !(modeOpen && modeButton.contains(x, y)) && !(volumeOpen && volumeButton.contains(x, y))) dismissMenu()
    }

    /**
     * 播放栏的底：和侧栏同一种液态玻璃，圆角更大；下面压着一团强调色的光晕
     */
    private fun drawPanel(bar: LayoutBounds) {
        val glowW = bar.width * 0.6f
        ui.ctx.drawShape(LayoutBounds(bar.x + (bar.width - glowW) / 2f, bar.bottom - 40f, glowW, 36f),
            ShapeStyle(Color.TRANSPARENT, 18f, shadow = ShadowStyle(RED.copy(a = 0.12f), 30f, Vec2(0f, -6f))))
        ui.ctx.drawGlass(bar, BAR_GLASS)
    }

    /**
     * 播放键：实心的强调色圆；正在播放时外面一圈一圈地往外扩散。
     * 返回当前的缩放（悬停放大、点击回弹），里面的图标按它缩放
     */
    private fun drawPlayDisc(b: LayoutBounds): Float {
        val ctx = ui.ctx
        val hover = playA.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + playA.press.tick(0f) * 0.14f + hover * 0.06f
        if (PlaybackView.snapshot().playing) {
            val phase = (System.nanoTime() / 1e9 % 2.8 / 2.8).toFloat()
            if (phase < 0.7f) {
                val ring = b.scaled(k * (1f + 0.4f * phase / 0.7f))
                ctx.drawShape(ring, ShapeStyle(Color.TRANSPARENT, ring.height / 2f, 1.5f, RED.copy(a = 0.7f * (1f - phase / 0.7f))))
            }
        }
        val disc = b.scaled(k)
        ctx.drawShape(disc, ShapeStyle(RED, disc.height / 2f))
        return k
    }

    /** 圆形按钮的底：悬停时渐显、跟着点击回弹；返回当前的缩放，图标按它缩放 */
    private fun button(b: LayoutBounds, a: Anim): Float {
        val h = a.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + a.press.tick(0f) * 0.14f + h * 0.04f
        if (h > 0.01f) {
            val d = b.scaled(k)
            val hoverRadius = if (me.sorihiko.dangomusicgtnh.ui.UiStyle.continuous) 9f * k else d.height / 2f
            ui.ctx.drawShape(d, ShapeStyle(Theme.CARD_HOVER.copy(a = Theme.CARD_HOVER.a * h), hoverRadius))
        }
        return k
    }

    /** 随机 / 单曲循环：选中的红底渐入渐出，点击先缩再弹回 */
    private fun toggleButton(b: LayoutBounds, vy: Float, on: Boolean, a: Anim, mode: MusicPlayer.Mode, action: () -> Unit) {
        val o = a.on.tick(if (on) 1f else 0f).coerceIn(0f, 1f)
        val h = a.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + a.press.tick(0f) * 0.14f
        val base = mixColor(Color(1f, 1f, 1f, 0.07f), Theme.CARD_HOVER, h)
        if (UiStyle.continuous) {
            if(h>0.01f) ui.card(b.scaled(k),Theme.CARD_HOVER.copy(a=Theme.CARD_HOVER.a*h),9f*k)
            PlayerIcons.mode(ui.ctx, mode, b.x + b.width / 2f, vy, 18f * k, mixColor(Theme.TEXT_PRIMARY, RED, o))
        } else {
            ui.card(b.scaled(k), mixColor(base, RED.copy(a = 0.85f), o), 9f * k)
            PlayerIcons.mode(ui.ctx, mode, b.x + b.width / 2f, vy, 18f * k, Theme.TEXT_PRIMARY)
        }
        ui.hits += b to {
            a.press.v -= 7f
            action()
        }
    }


    private fun drawProgress(bar: LayoutBounds, snap: PlaybackView.Snapshot, lyricsOpen: Boolean) {
        val duration = snap.durationMs
        if (seekTrack != null && seekTrack != snap.track?.key) seekTrack = null
        val seeking = seekTrack != null
        val y = bar.y + 6f
        val panel = bar.takeUnless { lyricsOpen }
        val inset = if (panel != null) progressInset(bar) else 0f
        progRect = LayoutBounds(bar.x + inset, y - 3f, bar.width - inset * 2f, 6f)
        progressHit = LayoutBounds(bar.x, y - 9f, bar.width, 18f)
        val hover = progressHit.contains(ui.mx, ui.my) && !menuContains(ui.mx, ui.my)
        val show = duration > 0L && (seeking || hover || System.nanoTime() < showTimeUntil)
        val knob = progKnob.tick(if (show) 1f else 0f).coerceIn(0f, 1f)
        val height = 4f + 2f * knob
        val lane = LayoutBounds(bar.x, y - height / 2f, bar.width, height)
        ui.ctx.drawShape(lane, ShapeStyle(Color(1f, 1f, 1f, 0.34f), clipBounds = panel, clipRadius = BAR_GLASS.radius))
        if (duration <= 0L) return

        val position = if (seeking) (seekFraction.toDouble() * duration).toLong() else snap.positionMs.coerceIn(0L, duration)
        val fraction = if (seeking) seekFraction else (position.toDouble() / duration).toFloat()
        val progressKnobX = progRect.x + progRect.width * fraction
        val fill = if (fraction >= 1f) lane.width else progressKnobX - lane.x
        val image = if (lyricsOpen) snap.track?.let(CoverCache::trackImage) else null
        if (image !== progressCover) {
            progressCover = image
            progressColors = image?.let(CoverArt::paletteOf).orEmpty().map(Color::fromARGB)
        }
        if (fraction > 0f && fill > 0f) {
            val style = ShapeStyle(progressColors.firstOrNull() ?: RED, fillEndColor = progressColors.getOrNull(1),
                clipBounds = panel, clipRadius = BAR_GLASS.radius)
            ui.ctx.drawShape(LayoutBounds(lane.x, lane.y, fill, height), style)
        }
        if (knob <= 0.01f) return
        val radius = 6f * knob
        ui.ctx.drawShape(LayoutBounds(progressKnobX - radius, y - radius, radius * 2f, radius * 2f),
            ShapeStyle(Theme.TEXT_PRIMARY, radius, clipBounds = panel ?: bar, clipRadius = if (panel != null) BAR_GLASS.radius else 0f))
        val label = "${fmtTime(position)} / ${fmtTime(duration)}"
        val font = Theme.FONT_SEMIBOLD
        val labelWidth = ui.width(label, 10f, font)
        val tooltipWidth = labelWidth + 18f
        val tooltipX = (progressKnobX - tooltipWidth / 2f).coerceIn(bar.x, (bar.right - tooltipWidth).coerceAtLeast(bar.x))
        val tooltip = LayoutBounds(tooltipX, y - 31f, tooltipWidth, 22f)
        ui.ctx.pushOpacity(knob)
        try {
            ui.card(tooltip, Color(0.05f, 0.06f, 0.08f, 0.93f), 11f)
            ui.text(label, tooltip.x + 9f, tooltip.y + 6f, 10f, Theme.TEXT_PRIMARY, font)
        } finally {
            ui.ctx.popOpacity()
        }
    }

    private fun progressInset(bar: LayoutBounds): Float {
        val radius = minOf(BAR_GLASS.radius, bar.width / 2f, bar.height / 2f)
        val y = bar.height / 2f - 6f
        if (!UiStyle.continuous) {
            val dy = (radius - 6f).coerceAtLeast(0f)
            return radius - kotlin.math.sqrt(radius * radius - dy * dy)
        }
        var left = 0f
        var right = minOf(bar.width / 2f, radius * 1.5f)
        repeat(12) {
            val mid = (left + right) / 2f
            if (me.sorihiko.dangomusicgtnh.music.ContinuousCorners.distance(
                    (bar.width / 2f - mid).toDouble(), y.toDouble(),
                    (bar.width / 2f).toDouble(), (bar.height / 2f).toDouble(), radius.toDouble()) > 0) left = mid
            else right = mid
        }
        return right
    }

    /** 从按下的位置开始预览，拖动时更新，松手后只跳转一次。 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (!progressHit.contains(mx, my)) return false
        val snap = PlaybackView.snapshot()
        if (!snap.remote && snap.durationMs > 0L && snap.track != null) {
            seekTrack = snap.track.key
            seekFraction = fractionAt(mx)
            progKnob.x = 1f
            progKnob.v = 0f
        }
        return true
    }

    fun mouseDrag(mx: Int, my: Int) {
        if (seekTrack != null) {
            seekFraction = fractionAt(mx)
            return
        }
        if (volumeOpen && volumeDragging) setVolumeAt(mx, my)
    }

    fun mouseUp() {
        volumeDragging = false
        val key = seekTrack ?: return
        seekTrack = null
        val snap = PlaybackView.snapshot()
        if (!snap.remote && snap.track?.key == key && snap.durationMs > 0L) {
            showTimeUntil = System.nanoTime() + 900_000_000L
            PlaybackView.seek((seekFraction.toDouble() * snap.durationMs).toLong())
        }
    }

    private fun fractionAt(x: Int): Float =
        if (progRect.width <= 0f) 0f else ((x - progRect.x) / progRect.width).coerceIn(0f, 1f)

    private fun drawVolumeEndpoint(x: Float, y: Float, volume: Float, interactive: Boolean, clip: LayoutBounds) {
        val bounds = LayoutBounds(x - 12f, y - 11f, 24f, 22f)
        val hovered = interactive && ui.hover(bounds)
        if (hovered) ui.card(bounds, PopupLook.hover, 7f)
        PlayerIcons.speaker(ui.ctx, x, y, if (hovered) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY,
            0.68f, if (volume == 0f) 0 else 2, volume == 0f)
        if (interactive) ui.hits += bounds.intersect(clip) to { applyVolume(volume) }
    }

    private fun setVolumeAt(x: Int, y: Int) {
        if (volRect.height <= 0f || volRect.width <= 0f) return
        val volume = if (UiStyle.continuous) ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
            else ((volRect.bottom - y) / volRect.height).coerceIn(0f, 1f)
        applyVolume(volume)
    }

    private fun applyVolume(volume: Float) {
        module.setVolume(volume)
        PlaybackView.setVolume(volume)
    }
}

/** 播放栏的玻璃：用侧栏那一套，圆角放大、阴影比侧栏淡一点 */
private val BAR_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 28f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 14f, Vec2(0f, -3f)))
/** 进度条离播放栏左右两边的距离 */
private const val PROGRESS_INSET = 72f
private const val MODE_BUTTON_OFFSET = 116f

private val downloadPercent = Regex("([0-9]{1,3})%")
