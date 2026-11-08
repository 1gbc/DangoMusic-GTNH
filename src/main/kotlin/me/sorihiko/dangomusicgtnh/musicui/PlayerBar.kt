package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.client.ClientMusic
import me.sorihiko.dangomusicgtnh.i18n.I18n

import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule
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

    private var lastSubtitle = ""
    private var subtitleAt = 0L
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
    private var seeking = false
    private var seekFraction = 0f

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
    private var modeSlider = Spring(300f, 30f)
    private var modeMenu = LayoutBounds()
    private var modeButton = LayoutBounds()
    private val playA = Anim()
    private val nextA = Anim()
    private val queueA = Anim()
    private val likeA = Anim()
    private val volumeA = Anim()
    private val modeMotion = PopupMotion()
    private val volumeMotion = PopupMotion()
    private var hitStart = 0
    /** 播放 ↔ 暂停图标的过渡 */
    private val playMorph = Spring(320f, 26f)
    /** 进度条上的圆钮：鼠标移上去时渐显 */
    private val progKnob = Spring(300f, 26f)

    fun draw(bar: LayoutBounds) {
        val ctx = ui.ctx
        hitStart = ui.hits.size
        if (UiStyle.continuous) {
            modeMotion.step(modeOpen,ui.frameDt/1000f)
            volumeMotion.step(volumeOpen,ui.frameDt/1000f)
        } else {
            modeMotion.snap(modeOpen); volumeMotion.snap(volumeOpen)
        }
        drawPanel(bar)
        val snap = PlaybackView.snapshot()
        val t = snap.track
        // 上面一排放封面、按钮和音量（中线在 [ry]），下面是横贯整条栏的进度条
        val ry = bar.y + 34f

        // 左：封面 + 歌名 / 歌手
        val cs = 48f
        // Center the complete control group, including mode and stop buttons.
        val modeOffset = MODE_BUTTON_OFFSET
        val cx = bar.x + bar.width / 2f - (154f - modeOffset) / 2f
        val canLike = t != null && t.source == MusicSource.NETEASE && NeteaseApi.loggedIn
        val leftLimit = cx - modeOffset - 4f
        // 窄窗口里先把封面往左收，给标题、收藏和心动模式保留各自的点击范围。
        val infoReserve = if (canLike) 84f else 48f
        val coverInset = (leftLimit - bar.x - cs - 12f - infoReserve).coerceIn(12f, PROGRESS_INSET)
        val coverBox = LayoutBounds(bar.x + coverInset, ry - cs / 2f, cs, cs)
        ui.drawCover(coverBox, t?.let { CoverCache.trackRounded(it, 0.27f) }, 13f)
        ctx.drawShape(coverBox, ShapeStyle(Color.TRANSPARENT, 13f, 1f, Color(1f, 1f, 1f, 0.10f)))
        if (screen.canOpenLyrics && !snap.remote) {
            ui.hits += coverBox to screen::toggleLyrics
            if (ui.hover(coverBox)) {
                ui.card(coverBox, Color(0f, 0f, 0f, 0.47f), 13f)
                ui.icon("♫", coverBox.x + cs / 2f, coverBox.y + cs / 2f, 23f, Theme.TEXT_PRIMARY)
            }
        }
        val infoX = coverBox.right + 12f
        // 窗口窄时歌名区跟着缩短，别压到中间的按钮上（红心也要留位置）
        val likeShown = canLike && leftLimit - infoX >= 44f
        val infoW = (leftLimit - infoX - (if (likeShown) 44f else 8f)).coerceIn(0f, 220f)
        val title = t?.name ?: I18n.text("Not playing")
        ui.text(ui.trim(title, infoW, 13f, Theme.FONT_SEMIBOLD), infoX, coverBox.y + 9f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = when {
            t == null -> "-"
            snap.message.isNotEmpty() -> I18n.message(snap.message)
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
        if (sub != lastSubtitle) { lastSubtitle = sub; subtitleAt = System.nanoTime() }
        val overflow = ui.width(sub, 11f) - infoW
        if (snap.message.isNotEmpty() && overflow > 0f) {
            val seconds = (System.nanoTime() - subtitleAt) / 1e9
            val travel = overflow / 28.0
            val phase = seconds % (travel * 2 + 3)
            val offset = when {
                phase < 1.5 -> 0f
                phase < 1.5 + travel -> ((phase - 1.5) * 28).toFloat()
                phase < 3 + travel -> overflow
                else -> (overflow - (phase - 3 - travel) * 28).toFloat()
            }
            ui.beginClip(LayoutBounds(infoX, coverBox.y + 23f, infoW, 20f))
            ui.text(sub, infoX - offset, coverBox.y + 27f, 11f, Theme.TEXT_SECONDARY)
            ui.endClip()
        } else if (t != null && snap.message.isEmpty() && !snap.remote && t.source != MusicSource.APPLE) {
            // 歌手名可以点，打开歌手页；远端曲目只显示来源，不提供歌手跳转
            drawArtistLinks(screen, t, infoX, coverBox.y + 27f, infoW, 11f, Theme.TEXT_SECONDARY)
        } else ui.text(ui.trim(sub, infoW, 11f), infoX, coverBox.y + 27f, 11f, Theme.TEXT_SECONDARY)

        // 网易云：红心（登录了才有）
        if (t != null && likeShown) {
            Library.ensureLiked()
            val likeB = LayoutBounds(infoX + infoW + 8f, coverBox.y + cs / 2f - 14f, 28f, 28f)
            val k = button(likeB, likeA)
            val on = likeA.on.tick(if (NeteaseApi.isLiked(t.id)) 1f else 0f).coerceIn(0f, 1f)
            ui.icon("♥", likeB.x + 14f, likeB.y + 14f, 15f * k, mixColor(Theme.TEXT_SECONDARY, RED, on))
            ui.hits += likeB to { likeA.press.v -= 7f; Library.toggleLike(t) }
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
            if (opening) modeSlider = Spring(300f, 30f)
        }
        if (!UiStyle.continuous && mode == MusicPlayer.Mode.REPEAT_ONE) repeatOneMark(modeButton.x + 16f, cy)
        val stopScale = button(stopB, stopA)
        if (UiStyle.continuous) PlayerIcons.draw(ctx,PlayerIcons.stop,stopB.x+16f,cy,18f*stopScale,Theme.TEXT_PRIMARY)
        else ui.icon("■", stopB.x + stopB.width / 2f, cy, 12f * stopScale, Theme.TEXT_PRIMARY)
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
            if (UiStyle.continuous) PlayerIcons.draw(ctx,PlayerIcons.play,cx,cy,26f*kPlay*(1f-0.35f*p),Theme.TEXT_PRIMARY)
            else ui.icon("▶", cx + 1.5f * kPlay, cy, 18f * kPlay * (1f - 0.35f * p), Theme.TEXT_PRIMARY)
            ctx.popOpacity()
        }
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条（围绕按钮中心缩放）
        val pc = prevB.x + prevB.width / 2f
        val nc = nextB.x + nextB.width / 2f
        if (UiStyle.continuous) {
            PlayerIcons.draw(ctx,PlayerIcons.previous,pc,cy,24f*kPrev,Theme.TEXT_PRIMARY)
            PlayerIcons.draw(ctx,PlayerIcons.next,nc,cy,24f*kNext,Theme.TEXT_PRIMARY)
        } else {
            ui.card(LayoutBounds(pc - 6f * kPrev, cy - 5.5f * kPrev, 2.2f * kPrev, 11f * kPrev), Theme.TEXT_PRIMARY, 1f)
            ui.icon("◀", pc + 2.5f * kPrev, cy, 12f * kPrev, Theme.TEXT_PRIMARY)
            ui.icon("▶", nc - 2.5f * kNext, cy, 12f * kNext, Theme.TEXT_PRIMARY)
            ui.card(LayoutBounds(nc + 3.8f * kNext, cy - 5.5f * kNext, 2.2f * kNext, 11f * kNext), Theme.TEXT_PRIMARY, 1f)
        }
        val q = queueA.on.tick(if (ScreenState.page == Page.QUEUE) 1f else 0f)
        if (UiStyle.continuous) PlayerIcons.draw(ctx,PlayerIcons.queue,queueB.x+16f,cy,20f*kQueue,mixColor(Theme.TEXT_PRIMARY,RED,q))
        else ui.icon("≡", queueB.x + queueB.width / 2f, cy, 14f * kQueue, mixColor(Theme.TEXT_PRIMARY, RED, q))
        ui.hits += prevB to { prevA.press.v -= 7f; if (!snap.remote) ClientMusic.control { MusicPlayer.prev() } }
        ui.hits += playB to { playA.press.v -= 7f; PlaybackView.togglePause() }
        ui.hits += nextB to { nextA.press.v -= 7f; if (!snap.remote) ClientMusic.control { MusicPlayer.next() } }
        ui.hits += queueB to {
            queueA.press.v -= 7f
            if (!snap.remote) {
                if (ScreenState.page == Page.QUEUE) screen.goBackOrHome() else screen.go(Page.QUEUE)
            }
        }

        // 进度条：在栏的底部，两端上方标时间（当前时间亮一点）；鼠标移上去变粗、出现圆钮
        val dur = snap.durationMs
        val pos = snap.positionMs
        // 两头各留一段，不贴着播放栏的圆角
        progRect = LayoutBounds(bar.x + PROGRESS_INSET, bar.bottom - 11f, bar.width - PROGRESS_INSET * 2, 3f)
        val kn = progKnob.tick(if (seeking || progRect.expand(0f, 6f).contains(ui.mx, ui.my)) 1f else 0f).coerceIn(0f, 1.2f)
        val l = fmtTime(if (seeking && dur > 0) (seekFraction * dur).toLong() else pos)
        val rgt = fmtTime(dur)
        ui.text(l, progRect.x, progRect.y - 14f, 9.5f, Theme.TEXT_PRIMARY.copy(a = 0.9f), Theme.FONT_SEMIBOLD)
        ui.text(rgt, progRect.right - ui.width(rgt, 9.5f, Theme.FONT_SEMIBOLD), progRect.y - 14f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val thick = 3f + 2f * kn.coerceAtMost(1f)
        val lane = LayoutBounds(progRect.x, progRect.y + 1.5f - thick / 2f, progRect.width, thick)
        ui.card(lane, Color(1f, 1f, 1f, 0.08f), thick / 2f)
        if (dur > 0) {
            val f = if (seeking) seekFraction else (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(lane.x, lane.y, max(thick, lane.width * f), lane.height), RED, thick / 2f)
            if (kn > 0.01f) {
                val kx = lane.x + lane.width * f
                val ks = 12f * kn
                ctx.drawShape(LayoutBounds(kx - ks / 2f, progRect.y + 1.5f - ks / 2f, ks, ks), ShapeStyle(RED, ks / 2f))
            }
        }

        drawVolume(bar, ry)
        if (me.sorihiko.dangomusicgtnh.music.MusicSources.selected == MusicSource.NETEASE) drawQuality(bar, ry) else if (qualityOpen) dismissMenu()
        if (volumeOpen || (UiStyle.continuous && volumeMotion.visible)) drawVolumeMenu(bar)
        if (UiStyle.continuous && (modeOpen || modeMotion.visible)) drawContinuousModeMenu(bar, mode)
        else if (modeOpen) {
            // 图标 + 文字，一眼能看出是哪种播放模式
            modeMenu = LayoutBounds(modeButton.x, bar.y - 96f, 112f, 92f)
            ui.ctx.drawGlass(modeMenu, BAR_GLASS.copy(radius = 11f))
            val sliderY = modeMenu.y + 4f + modeSlider.tick(mode.ordinal.toFloat()).coerceIn(0f, 2f) * 29f
            ui.card(LayoutBounds(modeMenu.x + 3f, sliderY, modeMenu.width - 6f, 26f), RED, 7f)
            for ((i, option) in MusicPlayer.Mode.values().withIndex()) {
                val row = LayoutBounds(modeMenu.x + 3f, modeMenu.y + 4f + i * 29f, modeMenu.width - 6f, 26f)
                if (option != mode && ui.hover(row)) ui.card(row, Theme.CARD_HOVER.copy(a = 0.35f), 7f)
                ui.icon(option.glyph, row.x + 15f, row.y + row.height / 2f, 15f, Theme.TEXT_PRIMARY)
                if (option == MusicPlayer.Mode.REPEAT_ONE) repeatOneMark(row.x + 15f, row.y + row.height / 2f)
                ui.text(I18n.text(option.label), row.x + 30f, row.y + row.height / 2f - 6f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                ui.hits += row to { module.selectPlaybackMode(option) }
            }
            ui.hits += modeMenu to {}
        }
    }

    /** 单曲循环：在循环箭头中间叠一个小“1” */
    private fun repeatOneMark(cx: Float, cy: Float) {
        val w = ui.width("1", 7f, Theme.FONT_SEMIBOLD)
        ui.text("1", cx - w / 2f, cy - 4f, 7f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
    }

    private fun drawVolume(bar: LayoutBounds, cy: Float) {
        if (UiStyle.continuous) {
            val inset = if (me.sorihiko.dangomusicgtnh.music.MusicSources.selected == MusicSource.NETEASE) 70f else 14f
            volumeButton = LayoutBounds(bar.right - inset - 60f, cy - 16f, 60f, 32f)
            val volume = MusicPlayer.volume.coerceIn(0f, 1f)
            val color = if (volume < 0.1f || volumeOpen) RED else Theme.TEXT_SECONDARY
            val k = button(LayoutBounds(volumeButton.x,cy-16f,32f,32f),volumeA)
            PlayerIcons.speaker(ui.ctx, volumeButton.x + 14f, cy, color, 0.75f*k,
                if (volume > 0.5f) 2 else 1, volume <= 0f)
            val percent = "${(volume * 100f).roundToInt()}%"
            ui.text(percent, volumeButton.right - 7f - ui.width(percent, 10f, Theme.FONT_MEDIUM), cy - 4.4f, 10f, color, Theme.FONT_MEDIUM)
            ui.hits += volumeButton to { val opening = !volumeOpen; dismissMenu(); volumeOpen = opening }
            return
        }
        val inset = if (screen.canOpenLyrics) 70f else 13f
        volumeButton = LayoutBounds(bar.right - inset - 32f, cy - 16f, 32f, 32f)
        if (volumeOpen || ui.hover(volumeButton)) {
            ui.card(volumeButton, if (volumeOpen) RED.copy(a = 0.3f) else Theme.CARD_HOVER, 9f)
        }
        speaker(volumeButton.x + 9.5f, cy, MusicPlayer.volume > 0f)
        // 音量百分比：音量很低时（比如 3%）一眼就能看出来，不会以为没声音
        val percent = "${(MusicPlayer.volume * 100f + 0.5f).toInt()}%"
        val low = MusicPlayer.volume < 0.1f
        ui.text(percent, volumeButton.x - 2f - ui.width(percent, 10f, Theme.FONT_MEDIUM), cy - 5f, 10f,
            if (low) RED else Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
        ui.hits += volumeButton to {
            val opening = !volumeOpen
            dismissMenu()
            volumeOpen = opening
        }
    }

    private fun drawVolumeMenu(bar: LayoutBounds) {
        if (UiStyle.continuous) {
            drawContinuousVolumeMenu(bar)
            return
        }
        volumeMenu = LayoutBounds(volumeButton.x - 4f, bar.y - 118f, 40f, 114f)
        ui.ctx.drawGlass(volumeMenu, BAR_GLASS.copy(radius = 11f))
        val cx = volumeMenu.x + volumeMenu.width / 2f
        val volume = MusicPlayer.volume.coerceIn(0f, 1f)
        val percent = "${(volume * 100).roundToInt()}%"
        val width = ui.width(percent, 11f, Theme.FONT_SEMIBOLD)
        ui.text(percent, cx - width / 2f, volumeMenu.y + 9f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        volRect = LayoutBounds(cx - 2f, volumeMenu.y + 32f, 4f, 69f)
        ui.card(volRect, Theme.TRACK, 2f)
        val fill = volRect.height * volume
        val y = volRect.bottom - fill
        if (fill > 0f) ui.card(LayoutBounds(volRect.x, y, volRect.width, fill), RED, 2f)
        ui.card(LayoutBounds(cx - 5f, y - 5f, 10f, 10f), Theme.TEXT_PRIMARY, 5f)
        ui.hits += volRect.expand(13f, 6f) to {
            volumeDragging = true
            setVolumeAt(ui.mx, ui.my)
        }
        ui.hits += volumeMenu to {}
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
            PlayerIcons.speaker(ui.ctx,content.x+20f,cy,Theme.TEXT_SECONDARY,0.75f,0)
            PlayerIcons.speaker(ui.ctx,content.right-21f,cy,Theme.TEXT_SECONDARY,0.75f,2)
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
        ui.ctx.drawGlass(b,CONTROL_GLASS.copy(radius=kotlin.math.min(CONTROL_GLASS.radius,b.height/2f)))
        ui.ctx.popOpacity()
        ui.beginClip(b)
        ui.ctx.pushOpacity(motion.contentOpacity)
        try {
            content(target.copy(y=target.y+10f*(1f-p)),open && p>0.6f,b)
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
        if (!qualityOpen) return

        val menu = LayoutBounds(bar.right - 284f, bar.y - 281f, 271f, 275f)
        qualityMenu = menu
        ui.ctx.drawShape(menu, ShapeStyle(Color.fromHex("#242830"), 14f, 1f, Color(1f, 1f, 1f, 0.22f)))
        ui.text(I18n.text("Playback quality"), menu.x + 14f, menu.y + 13f, 16f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val now = when {
            stream != null -> I18n.format("Current stream · %s", streamDescription(stream))
            MusicPlayer.state == MusicPlayer.State.LOADING && MusicPlayer.current?.source == MusicSource.NETEASE -> I18n.text("Loading current stream…")
            else -> I18n.text("No active NetEase stream")
        }
        ui.text(ui.trim(now, menu.width - 28f, 12f), menu.x + 14f, menu.y + 38f, 12f, Theme.TEXT_SECONDARY)
        val output = MusicPlayer.outputInfo
        val details = if (output != null) {
            val depth = output.sourceBits?.let { "$it → ${output.bits}" } ?: output.bits.toString()
            val backend = I18n.text(if (output.javaSound) "Java Sound" else "Game audio (OpenAL)")
            I18n.format("Output · %s-bit · %d Hz · %s", depth, output.rate, backend)
        } else I18n.text("Audio output inactive")
        ui.text(ui.trim(details, menu.width - 28f, 10.5f), menu.x + 14f, menu.y + 54f, 10.5f, Theme.TEXT_SECONDARY)
        val requested = I18n.format("Requested · %s", I18n.text(preferred.label))
        ui.text(requested, menu.x + 14f, menu.y + 68f, 10.5f, Theme.TEXT_SECONDARY)
        for ((i, tier) in NeteaseQuality.values().reversed().withIndex()) {
            val row = LayoutBounds(menu.x + 8f, menu.y + 86f + i * 29f, menu.width - 16f, 27f)
            val selected = tier == preferred
            if (selected || ui.hover(row)) ui.card(row, if (selected) RED.copy(a = 0.16f) else Theme.TRACK, 6f)
            ui.text(I18n.text(tier.label), row.x + 10f, row.y + 6f, 14f,
                if (selected) RED else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            if (selected) ui.icon("✓", row.right - 15f, row.y + row.height / 2f, 12f, RED)
            ui.hits += row to { module.selectQuality(tier) }
        }
        ui.hits += menu to {}
    }

    private fun streamDescription(stream: NeteaseStream): String {
        return listOfNotNull(
            stream.quality?.let { I18n.text(it.label) },
            stream.format.takeIf { it.isNotBlank() }?.uppercase(java.util.Locale.ROOT),
            stream.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        ).joinToString("  ").ifEmpty { I18n.text("Unknown quality") }
    }

    fun dismissMenu(): Boolean {
        if (!qualityOpen && !modeOpen && !volumeOpen && !(UiStyle.continuous && (modeMotion.visible || volumeMotion.visible))) return false
        volumeOpen = false
        volumeDragging = false
        modeOpen = false
        qualityOpen = false
        qualityMenu = LayoutBounds()
        return true
    }

    fun menuContains(x: Int, y: Int) = (qualityOpen && qualityMenu.contains(x, y)) ||
        ((modeOpen || (UiStyle.continuous && modeMotion.visible)) && modeMenu.contains(x,y)) ||
        ((volumeOpen || (UiStyle.continuous && volumeMotion.visible)) && volumeMenu.contains(x,y))

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
            ui.icon(mode.glyph, b.x + 16f, vy, 14f * k, Theme.TEXT_PRIMARY)
        }
        ui.hits += b to {
            a.press.v -= 7f
            action()
        }
    }

    /** 小喇叭图标：方块 + 喇叭口，loud = 右边再加两道声波 */
    private fun speaker(x: Float, cy: Float, loud: Boolean) {
        ui.card(LayoutBounds(x, cy - 2.5f, 3.5f, 5f), Theme.TEXT_SECONDARY, 1f)
        ui.card(LayoutBounds(x + 3f, cy - 5f, 3.5f, 10f), Theme.TEXT_SECONDARY, 1.2f)
        if (loud) {
            ui.card(LayoutBounds(x + 8.5f, cy - 2.5f, 1.4f, 5f), Theme.TEXT_SECONDARY, 0.7f)
            ui.card(LayoutBounds(x + 11.5f, cy - 4.5f, 1.4f, 9f), Theme.TEXT_SECONDARY, 0.7f)
        }
    }

    /** 点在进度条 / 音量条上就处理掉，返回 true；按在进度条上开始拖动 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (progRect.expand(0f, 6f).contains(mx, my)) {
            val snap = PlaybackView.snapshot()
            if (!snap.remote && snap.durationMs > 0) {
                seeking = true
                seekFraction = fractionAt(mx)
            }
            return true
        }
        return false
    }

    /** 按住拖动：拖进度条时跟着鼠标走（拖出条外也算），否则拖音量 */
    fun mouseDrag(mx: Int, my: Int) {
        if (seeking) {
            seekFraction = fractionAt(mx)
            return
        }
        if (volumeOpen && volumeDragging) setVolumeAt(mx, my)
    }

    /** 松开鼠标：拖进度条的话现在才跳过去 */
    fun mouseUp() {
        volumeDragging = false
        if (!seeking) return
        seeking = false
        val dur = PlaybackView.snapshot().durationMs
        if (dur > 0) {
            val position = (seekFraction * dur).toLong()
            PlaybackView.seek(position)
        }
    }

    private fun fractionAt(x: Int): Float =
        if (progRect.width <= 0f) 0f else ((x - progRect.x) / progRect.width).coerceIn(0f, 1f)

    private fun setVolumeAt(x: Int, y: Int) {
        if (volRect.height <= 0f || volRect.width <= 0f) return
        val volume = if (UiStyle.continuous) ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
            else ((volRect.bottom - y) / volRect.height).coerceIn(0f, 1f)
        module.setVolume(volume)
        PlaybackView.setVolume(volume)
    }
}

/** 播放栏的玻璃：用侧栏那一套，圆角放大、阴影比侧栏淡一点 */
private val BAR_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 28f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 14f, Vec2(0f, -3f)))
/** Same tint, blur, refraction and highlights as the bar beneath the popover. */
private val CONTROL_GLASS = BAR_GLASS.copy(radius = 24f)
/** 进度条离播放栏左右两边的距离 */
private const val PROGRESS_INSET = 72f
private const val MODE_BUTTON_OFFSET = 116f
