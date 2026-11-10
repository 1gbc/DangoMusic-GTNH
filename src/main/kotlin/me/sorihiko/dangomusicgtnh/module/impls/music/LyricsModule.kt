package me.sorihiko.dangomusicgtnh.module.impls.music

import me.sorihiko.dangomusicgtnh.DangoClient
import me.sorihiko.dangomusicgtnh.DangoLogger
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.event.EventHandler
import me.sorihiko.dangomusicgtnh.events.GameMouseEvent
import me.sorihiko.dangomusicgtnh.events.HudRenderEvent
import me.sorihiko.dangomusicgtnh.events.WorldRenderEndEvent
import me.sorihiko.dangomusicgtnh.gfx.ViewAngles
import me.sorihiko.dangomusicgtnh.gfx.CjkStyle
import me.sorihiko.dangomusicgtnh.lyrics.LyricLine
import me.sorihiko.dangomusicgtnh.lyrics.LyricSplit
import me.sorihiko.dangomusicgtnh.lyrics.Lyrics
import me.sorihiko.dangomusicgtnh.lyrics.LyricsProvider
import me.sorihiko.dangomusicgtnh.lyrics.TextFlash
import me.sorihiko.dangomusicgtnh.module.Category
import me.sorihiko.dangomusicgtnh.module.Module
import me.sorihiko.dangomusicgtnh.module.ModuleMeta
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue
import me.sorihiko.dangomusicgtnh.module.values.TextValue
import me.sorihiko.dangomusicgtnh.music.LyricFonts
import me.sorihiko.dangomusicgtnh.module.values.ColorValue
import me.sorihiko.dangomusicgtnh.module.values.FloatValue
import me.sorihiko.dangomusicgtnh.module.values.IntValue
import me.sorihiko.dangomusicgtnh.module.values.ModuleValue
import me.sorihiko.dangomusicgtnh.music.CoverArt
import me.sorihiko.dangomusicgtnh.music.Dance
import me.sorihiko.dangomusicgtnh.music.MusicPlayer
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.music.PlaybackView
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsAnchor
import me.sorihiko.dangomusicgtnh.multiplayer.LyricsStyle
import me.sorihiko.dangomusicgtnh.multiplayer.SharedLyricLine
import me.sorihiko.dangomusicgtnh.multiplayer.shareableLyrics
import me.sorihiko.dangomusicgtnh.network.NearbyClient

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** "Above ground"：歌词底边最低在眼睛下面多少格 */
private const val GROUND_MARGIN = 0.5
/** 被方块挡住时，歌词停在方块表面前面多远（格） */
private const val BLOCK_MARGIN = 0.4
/** 拉近的下限：设定距离的这个比例。小房间里允许贴墙，不为了避让把歌词缩得太小 */
private const val MIN_REACH = 0.35
private const val LYRIC_ROW_HEIGHT = 11f
private const val LYRIC_ROW_GAP = 4f
private const val ANNOTATION_GAP = 2.5f
private const val SCROLL_NANOS = 380_000_000L

/** Stay on screen 弹簧的快慢（越大跟得越紧；12 大约 0.3 秒回到位） */
private const val SPRING = 12.0

/** 天黑到这个程度（原版 sky darken，0 = 白天，11 = 半夜）就算晚上 */
private const val NIGHT_SKY_DARKEN = 4

/** 翻译的字号是原文的多少 */
private const val TRANSLATION_SCALE = 0.72f

/** 翻译按多宽换行（原文是 200；字小了，按画出来差不多宽算） */
private const val TRANSLATION_WRAP = 270

/**
 * 歌词：内置播放器放歌时，从网易云取（逐字）歌词，一句句以动画漂浮在世界里。
 */
@ModuleMeta(Category.MUSIC, "Lyrics")
class LyricsModule : Module() {

    /**
     * 旧版本的“3D 歌词”开关（快捷键也切它）。现在由 [lyricStyle] 统一管，这个值只为读出旧存档：
     * 关着的话第一次运行时换成“不显示”（或者只留驾驶舱），之后一直是开着的，设置页里也不再显示
     */
    @ModuleValue("3D lyrics", "Show lyrics floating in the world")
    internal val worldMode = BooleanValue(true)

    @ModuleValue("Lyric style", "Where lyrics appear: floating in the world, cockpit-style on the screen edge, both, or nowhere")
    internal val lyricStyle = IntValue(STYLE_WORLD, STYLE_OFF, STYLE_WORLD)

    @ModuleValue("Side position", "Where the side lyric list sits")
    internal val sidePosition = IntValue(0, 2, 0)

    @ModuleValue("Horizontal offset", "Moves the list right (positive) or left (negative) from its position")
    internal val sideOffsetX = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("Vertical offset", "Moves the list up (positive) or down (negative) from its position")
    internal val sideOffsetY = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("Side width", "Width of the side lyric list")
    internal val sideWidth = FloatValue(200f, 480f, 300f, 0)

    @ModuleValue("Side lines", "How many lines the side list shows")
    internal val sideLines = IntValue(3, 11, 7)

    @ModuleValue("Side text size", "Text size in the side list")
    internal val sideTextSize = FloatValue(0.7f, 1.6f, 1f, 2)

    @ModuleValue("Perspective tilt", "Turns the list toward the middle of the screen like a cockpit side display")
    internal val sideTilt = FloatValue(-45f, 45f, 18f, 0)

    @ModuleValue("Pitch", "Leans the list back (positive) or forward (negative)")
    internal val sidePitch = FloatValue(-30f, 30f, 0f, 0)

    @ModuleValue("Italic", "Slant of the letters")
    internal val sideItalic = FloatValue(0f, 20f, 8f, 0)

    @ModuleValue("Glass text", "The letters become see-through glass that bends the game behind them; off is solid glowing text")
    internal val sideGlass = BooleanValue(false)

    @ModuleValue("Text opacity", "Lower it so the lyrics cover less of the game")
    internal val sideOpacity = FloatValue(0.1f, 1f, 0.85f, 2)

    @ModuleValue("Refraction", "How strongly the glass bends the game behind it")
    internal val sideRefraction = FloatValue(0f, 1f, 0.2f, 2)

    @ModuleValue("Text glow", "The letters glow in their own color and brighten on the beat")
    internal val sideGlow = BooleanValue(true)

    @ModuleValue("Glow strength", "How strongly the text and halo glow")
    internal val sideGlowStrength = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Halo", "A soft light spreading around the text")
    internal val sideTextGlow = BooleanValue(true)

    @ModuleValue("Guide line", "A thin glowing line beside the list with a tick at the current line")
    internal val sideGuide = BooleanValue(true)

    @ModuleValue("Cockpit font", "The default bold font, a font from config/dangomusicgtnh/fonts, another mod's font folder, or a system font")
    internal val sideFont = TextValue("")

    /** 驾驶舱字体那一行里选“像素字体”时为 false（和 3D 歌词的 smoothFont 一样，设置页里合进字体那一行） */
    @ModuleValue("Cockpit smooth font", "")
    internal val sideSmoothFont = BooleanValue(true)

    @ModuleValue("Cockpit colors", "Cover color follows each song; or use your own color, a cover gradient, a rainbow, or a flowing aurora of the cover colors")
    internal val sideColors = IntValue(SIDE_COVER, SIDE_AURORA, SIDE_COVER)

    @ModuleValue("Cockpit color", "Used when Cockpit colors is set to Custom color")
    internal val sideColor = ColorValue(0x8EC5FF)

    @ModuleValue("Color in letters", "How much of the color goes into the letters themselves; 0 keeps white letters with a colored glow")
    internal val sideTint = FloatValue(0f, 1f, 0.2f, 2)

    @ModuleValue("Text brightness", "Makes the lyrics brighter or darker")
    internal val sideBrightness = FloatValue(0.3f, 1.6f, 1f, 2)

    @ModuleValue("Other lines", "How visible the lines before and after the current one are")
    internal val sideOtherLines = FloatValue(0.1f, 1f, 0.45f, 2)

    @ModuleValue("Auto contrast", "Text too close in color to what is behind it is lightened or darkened so it stays readable")
    internal val autoContrast = BooleanValue(false)

    /** 按住预览键滚轮翻看驾驶舱歌词：翻到了第几行（0 = 歌名，第 n 句是 n），-1 = 不在预览 */
    private var previewRow = -1
    private var previewInputAt = 0L

    private val side = SideLyrics()
    /** 快捷键关掉歌词前用的样式（再按一次恢复成它；存盘，重开游戏后也能恢复；设置页里不显示） */
    @ModuleValue("Style before hiding", "")
    internal val styleBeforeOff = IntValue(STYLE_WORLD, STYLE_BOTH, STYLE_WORLD)
    private var migrated = false

    /** 3D 世界歌词这一帧要不要画 */
    internal fun worldVisible(): Boolean {
        migrateWorldMode()
        return lyricStyle.value == STYLE_WORLD || lyricStyle.value == STYLE_BOTH
    }

    /** 驾驶舱（侧边）歌词要不要画 */
    internal fun sideVisible(): Boolean {
        migrateWorldMode()
        return lyricStyle.value == STYLE_SIDE || lyricStyle.value == STYLE_BOTH
    }

    /** 歌词样式设成了“不显示”（别人投影过来的歌词也不画） */
    internal fun lyricsHidden(): Boolean = enabled && lyricStyle.value == STYLE_OFF

    /** 自己在用侧边列表（多人时就不看别人的 3D 投影，改用自己的歌词） */
    internal fun prefersSideLyrics(): Boolean = enabled && sideVisible()

    /** 自己的歌词会以 3D 投影给别人看；只有“只用侧边列表”时不投影（其他情况保持原来的共享行为） */
    internal fun projectsWorldLyrics(): Boolean = lyricStyle.value != STYLE_SIDE

    /** 快捷键"开关歌词"：在“不显示”和关之前的样式之间切换 */
    fun toggleLyrics() {
        migrateWorldMode()
        if (lyricStyle.value != STYLE_OFF) {
            styleBeforeOff.setValue(lyricStyle.value)
            lyricStyle.setValue(STYLE_OFF)
        } else {
            lyricStyle.setValue(styleBeforeOff.value)
        }
    }

    /**
     * 旧存档里 3D 歌词是关着的：换成新的样式（只做一次，之后 worldMode 一直开着）。
     * 模块开起来之后存档才读完，所以没开之前不做
     */
    private fun migrateWorldMode() {
        if (migrated || !enabled) return
        migrated = true
        if (worldMode.get()) return
        when (lyricStyle.value) {
            STYLE_WORLD -> lyricStyle.setValue(STYLE_OFF)
            STYLE_BOTH -> lyricStyle.setValue(STYLE_SIDE)
        }
        worldMode.set(true)
    }

    @ModuleValue("Scale", "Lyrics size")
    private val scale = FloatValue(0.5f, 3f, 1f, 1)

    @ModuleValue("Distance", "How far away 3D lyrics appear (blocks)")
    private val distance = FloatValue(3f, 40f, 10f, 1)

    @ModuleValue("Glow", "Deep Glow style: white-hot core, wide soft halo")
    private val glow = BooleanValue(true)

    @ModuleValue("Night glow only", "Turn glow off in daylight and back on when it gets dark")
    private val glowNightOnly = BooleanValue(false)

    @ModuleValue("Glow strength", "Glow brightness")
    private val glowStrength = FloatValue(0f, 3f, 0.3f, 2)

    @ModuleValue("Glow radius", "Halo spread: higher is wider and softer")
    private val glowRadius = FloatValue(0f, 1f, 0.5f, 2)

    /** 平滑字体还是原版像素字体：设置页里和"歌词字体"合成一项，不单独显示 */
    @ModuleValue("Smooth font", "Smooth font instead of the Minecraft pixel font")
    private val smoothFont = BooleanValue(false)

    @ModuleValue("Lyric font", "Pixel font, smooth font, or a .ttf / .otf font from config/dangomusic/fonts")
    private val lyricFont = TextValue("")

    @ModuleValue("Wiggle", "Low-fps jitter on every letter, like hand-drawn animation")
    private val wiggle = BooleanValue(true)

    @ModuleValue("Wiggle fps", "Jitter frame rate: lower feels more hand-drawn")
    private val wiggleFps = IntValue(4, 24, 8)

    @ModuleValue("Wiggle amount", "Jitter strength")
    private val wiggleAmount = FloatValue(0f, 2f, 0.8f, 2)

    @ModuleValue("Text flash", "Words slam onto the screen one by one, synced to the beat")
    private val textFlash = BooleanValue(false)

    @ModuleValue("Word pop", "Words pop in one by one as they are sung")
    private val wordPop = BooleanValue(true)

    @ModuleValue("Simultaneous lyric lines", "Show the current lyric and upcoming lines")
    internal val maxLyricLines = IntValue(1, 8, 1)

    @ModuleValue("Show instrumental sections", "Label the intro, interlude and outro in KTV mode")
    internal val ktvInstrumental = BooleanValue(true)

    @ModuleValue("Lyric colors", "Choose the color of the KTV highlight")
    internal val ktvLyricColors = IntValue(COLORS_CUSTOM, COLORS_RAINBOW, COLORS_CUSTOM)

    private fun worldLyricBudget(): Float {
        val platform = DangoClient.platform
        val fov = platform.fov.coerceIn(30f, 110f)
        val viewHeight = (2.0 * tan(Math.toRadians(fov / 2.0)) * 0.6 * 6.5 / 0.03).toFloat()
        return viewHeight * 0.38f / scale.value * (platform.windowHeight / 480f).coerceIn(0.55f, 1f)
    }

    internal fun worldLyricLimit(): Int {
        if (!adaptiveLyricLineLimit) return maxLyricLines.maxRange
        return (worldLyricBudget() / (14f +
            (if (romanization.get()) 8f else 0f) + (if (translation.get()) 8f else 0f))).toInt().coerceIn(1, maxLyricLines.maxRange)
    }

    @ModuleValue("KTV mode", "Show the full line and highlight it as the song plays")
    internal val ktvMode = BooleanValue(false)

    @ModuleValue("KTV base color", "")
    internal val ktvBaseColor = ColorValue(0xD8D8D8)

    @ModuleValue("KTV sung color", "")
    internal val ktvSungColor = ColorValue(0xFF345E)

    @ModuleValue("KTV base shadow", "")
    internal val ktvBaseShadow = BooleanValue(true)

    @ModuleValue("KTV sung shadow", "")
    internal val ktvSungShadow = BooleanValue(true)

    @ModuleValue("Flash size", "Text flash size")
    private val flashSize = FloatValue(0.5f, 2f, 1f, 2)

    @ModuleValue("Face player", "Lyrics keep turning to face you")
    private val facePlayer = BooleanValue(true)

    @ModuleValue("Keep away", "Lyrics back off when you walk or sprint into them")
    private val keepAway = BooleanValue(true)

    @ModuleValue("Stay on screen", "Lyrics glide back into view when you turn away, so they never leave the screen")
    private val stayOnScreen = BooleanValue(false)

    @ModuleValue("Above ground", "Lyrics never appear below your eyes, so they don't sink into the ground")
    private val aboveGround = BooleanValue(false)

    @ModuleValue("Avoid blocks", "Lyrics move in front of walls, leaves and the ground instead of going through them")
    private val avoidBlocks = BooleanValue(true)

    @ModuleValue("Spread", "How far left or right each line can appear (degrees)")
    private val spread = FloatValue(0f, 60f, 30f, 0)

    @ModuleValue("Split lines", "Split two lines written as one (NetEase often does this) so they appear separately")
    private val splitLines = BooleanValue(true)

    @ModuleValue("Translation", "Show the translation under each lyric line when the song has one")
    private val translation = BooleanValue(false)

    @ModuleValue("Show phonetic lyrics", "Show phonetic lyrics under the original when available")
    private val romanization = BooleanValue(false)

    @ModuleValue("Translation animation", "The translation appears character by character like the lyrics")
    private val translationAnimation = BooleanValue(true)

    @ModuleValue("Translation wiggle", "The translation wiggles like the lyrics (uses the Wiggle speed and amount)")
    private val translationWiggle = BooleanValue(true)

    @ModuleValue("Mob dance", "Every creature in the world squashes and stretches on the beat of the song")
    private val dance = BooleanValue(false)

    @ModuleValue("Auto BPM", "Work out the song's BPM from its drum beats")
    private val danceAuto = BooleanValue(false)

    @ModuleValue("Tap BPM", "Left-click 16 times on the beat to set the BPM yourself; counting starts with your first click (recommended, most accurate)")
    private val danceTap = BooleanValue(false)

    @ModuleValue("Spin", "Creatures also spin around steadily")
    private val danceSpin = BooleanValue(false)

    @ModuleValue("Bounce amount", "How much creatures squash and stretch")
    private val danceAmount = FloatValue(0.2f, 2f, 1.3f, 1)

    @ModuleValue("Beat offset", "Move the bounce earlier or later if it doesn't land on the beat")
    private val danceOffset = IntValue(-300, 300, 0)

    @ModuleValue("Lyric colors", "Where the lyric and translation colors come from")
    internal val lyricColors = IntValue(COLORS_CUSTOM, COLORS_RAINBOW, COLORS_CUSTOM)

    @ModuleValue("Rainbow speed", "How fast the rainbow flows across the lyrics")
    private val rainbowSpeed = FloatValue(0.05f, 1f, 0.25f, 2)

    @ModuleValue("Custom lyric color", "Used when Lyric colors is set to Custom color")
    private val textColor = ColorValue(0xF7E1D7)

    @ModuleValue("Custom translation color", "Used when Lyric colors is set to Custom color")
    // 旧默认 #803D66 在游戏里几乎看不清：默认改成浅粉，仍是旧默认值的老配置第一次渲染时迁移过来
    private val translationColor = ColorValue(0xF2C6DE)
    private var translationColorMigrated = false

    // ---------------------------------------------------------------- 状态

    /** 正在播放的歌（来自内置播放器） */
    private class NowPlaying(val track: NeteaseTrack, val playing: Boolean) {
        val key: String get() = track.key
        val title: String get() = track.name
        val artist: String get() = track.artists.joinToString(" / ")
    }

    private val cover = CoverArt()
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Lyrics").apply { isDaemon = true } }

    private object NotFound
    private val cache = ConcurrentHashMap<String, Any>()     // key -> Lyrics 或 NotFound
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private var current: NowPlaying? = null
    private var currentKey: String? = null
    private var songPos = 0.0                 // 毫秒，本地平滑时钟
    private var lastSeenNanos = 0L
    private var lastIndex = -1

    private val random = Random()

    /** 一句漂浮的歌词 */
    private class Floating(
        val lines: List<String>,
        val startMs: Long,
        val revealMs: Long,
        var x: Double, var y: Double, var z: Double,
        var yaw: Float, var pitch: Float,
        /** 每个非空白字符出现的时间（相对这句开始，毫秒），按词闪出时用 */
        val charTimes: LongArray = LongArray(0),
        val lyric: LyricLine,
        val nextMs: Long,
    ) {
        var instrumental = false
        var fadeStartNanos = 0L
        /** 这一句的翻译（已经按宽度换好行；没有就是空的），画在原文下面 */
        var translation: List<String> = emptyList()
        var phonetic: List<String> = emptyList()
        var scrollIn = 0f
        var scrollStartNanos = 0L
        var scrollOut = 0f
        var lastWiggleFrame: Long? = null
        var previousStackLift = 0f
        var previousTail: LyricLine? = null
        /** Stay on screen 往回拉时的角速度（度 / 秒），用弹簧算，起步和停下都是平滑的 */
        var vYaw = 0.0
        var vPitch = 0.0
        /** 暂停后恢复播放时重新出现的这句：用一个短淡入代替逐字出现 */
        var resumedAt = 0L
        /** Avoid blocks：沿视线拉近到方块前面的比例（1 = 原位），NaN = 还没测过 */
        var reach = Double.NaN
    }

    /** 恢复播放后重新出现的那句歌词，0.3 秒淡入 */
    private fun appearOf(f: Floating): Float =
        if (f.resumedAt == 0L) 1f else ((System.nanoTime() - f.resumedAt) / 3e8f).coerceIn(0f, 1f)

    private var active: Floating? = null

    /** 最近一帧实际渲染的活动歌词世界姿态；附近共享用它复现位置/朝向/缩放 */
    @Volatile private var sharedPose: LyricsAnchor? = null
    private var upcoming = emptyList<Floating>()
    private data class StackRow(val lyric: Floating, val center: Float)

    private fun phoneticRows(f: Floating) = if (romanization.get()) f.phonetic else emptyList()
    private fun translationRows(f: Floating) = if (translation.get()) f.translation else emptyList()
    private fun halfMain(f: Floating) = f.lines.size * LYRIC_ROW_HEIGHT / 2f

    private fun rowBottom(f: Floating): Float {
        val annotations = phoneticRows(f).size + translationRows(f).size
        return halfMain(f) + if (annotations == 0) 0f else ANNOTATION_GAP + annotations * LYRIC_ROW_HEIGHT * TRANSLATION_SCALE
    }

    private fun layoutStack(first: Floating): List<StackRow> = buildList {
        add(StackRow(first, 0f))
        var bottom = rowBottom(first)
        for (next in upcoming) {
            val center = bottom + LYRIC_ROW_GAP + halfMain(next)
            val nextBottom = center + rowBottom(next)
            if (adaptiveLyricLineLimit && nextBottom + halfMain(first) > worldLyricBudget()) break
            add(StackRow(next, center))
            bottom = nextBottom
        }
    }

    private var upcomingKey: Triple<Floating?, Int, Lyrics?>? = null
    /** 暂停期间（歌词先隐藏，恢复播放时再出现） */
    private var wasPaused = false
    private val flash = TextFlash()
    /** 正在往发光画布上画（只画字本身，不画阴影） */
    private var glowPass = false
    private val fading = mutableListOf<Floating>()

    override fun onDisable() {
        cover.clear()
        current = null
        currentKey = null
        active = null
        sharedPose = null
        fading.clear()
        flash.reset()
        lastIndex = -1
    }

    // ---------------------------------------------------------------- 当前歌曲 / 时钟

    private fun nowPlaying(): NowPlaying? {
        val snap = PlaybackView.snapshot()
        val t = snap.track ?: return null
        // When the listener opts out of the host's lyrics (or the host does not
        // share them), use this client's own lyrics data and single-player style.
        if (snap.remote && !NearbyClient.shouldUseLocalLyrics()) return null
        return NowPlaying(t, snap.playing)
    }

    private fun playbackPositionMs(): Long = PlaybackView.snapshot().positionMs

    private fun playbackDurationMs(): Long = PlaybackView.snapshot().durationMs

    private fun playbackBeatPulse(positionMs: Long): Float =
        if (PlaybackView.snapshot().remote) 0f else MusicPlayer.beatPulse(positionMs)

    private val notFoundAt = ConcurrentHashMap<String, Long>()

    private fun lyricsFor(key: String): Lyrics? = cache[key] as? Lyrics

    internal fun playerLyrics(track: NeteaseTrack): Lyrics? {
        requestLyrics(track)
        return lyricsFor(track.key)
    }

    /** Line-level lyrics for the nearby/group projection; null until the payload is loaded. */
    internal fun shareLines(track: NeteaseTrack): List<SharedLyricLine>? =
        shareableLyrics(lyricsFor(track.key))

    /** Visual settings so a listener sees the same font, colors, scale and glow. */
    internal fun shareStyle(): LyricsStyle = LyricsStyle(
        font = lyricFont.value,
        smooth = smoothFont.get(),
        textColor = textColor.value,
        translationColor = translationColor.value,
        scale = scale.value,
        distance = distance.value,
        maxLines = maxLyricLines.value,
        glow = glow.get(),
        glowStrength = glowStrength.value,
        glowRadius = glowRadius.value,
        translation = translation.get(),
        romanization = romanization.get(),
        ktv = ktvMode.get(),
        ktvBaseColor = ktvBaseColor.value,
        ktvSungColor = ktvSungColor.value,
        wiggle = wiggle.get(),
        wiggleFps = wiggleFps.value,
        wiggleAmount = wiggleAmount.value,
    )

    /** World position of the host's currently rendered lyric line, if any. */
    internal fun shareAnchor(): LyricsAnchor? {
        val f = active ?: return null
        sharedPose?.let { return it }
        return LyricsAnchor(
            f.x, f.y, f.z, f.yaw, f.pitch,
            0.03f * scale.value * (distance.value / 6.5f),
            f.lastWiggleFrame ?: -1L,
        )
    }

    internal fun isLoadingLyrics(track: NeteaseTrack): Boolean = track.key in pending

    private fun requestLyrics(track: NeteaseTrack) {
        val key = track.key
        if (cache[key] === NotFound) {
            val failedAt = notFoundAt[key] ?: 0L
            if (System.nanoTime() - failedAt < 30_000_000_000L) return
            cache.remove(key, NotFound)
        }
        if (cache.containsKey(key) || !pending.add(key)) return
        fetcher.execute {
            try {
                val result = me.sorihiko.dangomusicgtnh.music.MusicSources.api(track.source).lyrics(track)
                if (result == null) notFoundAt[key] = System.nanoTime() else notFoundAt.remove(key)
                cache[key] = result ?: NotFound
            } catch (_: Exception) {
                notFoundAt[key] = System.nanoTime()
                cache[key] = NotFound
            } finally {
                pending.remove(key)
            }
        }
    }

    /** 跟着内置播放器的进度走（它的进度是精确的） */
    private fun updateClock() {
        val now = System.nanoTime()
        val s = nowPlaying()
        if (s == null) {
            // Remote playback may be active while this client deliberately renders
            // the host's projection instead of local lyrics. Clear immediately in
            // that case so the two lyric layers do not overlap for three seconds.
            val remoteOptOut = PlaybackView.snapshot().remote
            if (current != null && (remoteOptOut || (now - lastSeenNanos) / 1e9 > 3.0)) {
                current = null
                currentKey = null
                active?.let { startFade(it) }
                active = null
                sharedPose = null
                lastIndex = -1
            }
            return
        }
        lastSeenNanos = now
        requestLyrics(s.track)
        val p = playbackPositionMs().toDouble()
        if (s.key != currentKey) {
            fading.removeAll { it.scrollOut > 0f }
            currentKey = s.key
            songPos = p
            active?.let { startFade(it) }
            active = null
            sharedPose = null
            lastIndex = -1
        } else {
            // Browser telemetry arrives in batches, not every rendered frame.
            // The lyric index below handles real seeks; a clock correction within
            // the same line must not respawn its random world anchor.
            songPos = p
        }
        current = s
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        // 生物跟着节奏摇：每帧把设置同步过去，各版本画生物时读
        Dance.enabled = enabled && dance.get()
        Dance.spin = danceSpin.get()
        Dance.amount = danceAmount.value
        Dance.offsetMs = danceOffset.value
        if (!translationColorMigrated) {
            translationColorMigrated = true
            if (translationColor.value == 0x803D66) translationColor.setValue(0xF2C6DE)
        }
        // 两种测 BPM 都没开时默认自动测，否则开了 Mob dance 也没有节拍可跟
        Dance.auto = danceAuto.get() || !danceTap.get()
        Dance.tap = danceTap.get()
        val platform = DangoClient.platform
        Dance.tapTick(platform.inGame && platform.isMouseDown(0), platform.inGame)
        if (!enabled) return
        rainbowPhase = ((System.nanoTime() / 1_000_000_000.0 * rainbowSpeed.value) % 1.0).toFloat()
        // 歌词字体：选了就换（同一个文件不会重复加载）
        // 歌词字体：每帧对一下版本标记，字体文件被替换了就重新加载（热重载）
        val (lf, lk) = if (smoothFont.get()) LyricFonts.load(lyricFont.value) else (null to null)
        DangoClient.gfx.text.font(CjkStyle.LYRIC, lf, lk)
        updateClock()
        val s = current
        if (s != null && (sideVisible() || lyricColors.value == COLORS_COVER || lyricColors.value == COLORS_GRADIENT || (ktvMode.get() && ktvLyricColors.value in COLORS_COVER..COLORS_GRADIENT))) {
            s.track.coverUrl.takeIf { it.isNotEmpty() }?.let { cover.requestUrl(s.key, it) }
        }
        cover.upload()
        // 拆句开关变了：歌词行的下标都变了，重新找当前这句
        if (LyricSplit.enabled != splitLines.get()) {
            LyricSplit.enabled = splitLines.get()
            lastIndex = -1
        }
        val lyrics = currentKey?.let { lyricsFor(it) }
        val paused = s != null && !s.playing
        val frameNanos = System.nanoTime()
        if (s != null && lyrics != null) {
            val pos = songPos.toLong()
            val ktv = ktvMode.get()
            val lyricIndex = if (ktv) lyrics.ktvIndexAt(pos) else lyrics.indexAt(pos)
            val section = if (ktv && ktvInstrumental.get() && lyricIndex < 0) lyrics.instrumentalAt(pos.coerceAtLeast(0L), playbackDurationMs()) else null
            val idx = if (section != null) -2 - section.ordinal else lyricIndex
            val sectionLabel = section?.displayLabel()
            if (paused) {
                // 暂停：当前这句淡出消失，恢复播放时再出现
                active?.let { startFade(it) }
                active = null
                lastIndex = idx
                wasPaused = true
            } else if (idx != lastIndex || wasPaused || (sectionLabel != null && sectionLabel != active?.lyric?.text)) {
                val resumed = wasPaused && idx == lastIndex
                wasPaused = false
                val anchor = active?.takeIf { maxLyricLines.value > 1 && lastIndex >= 0 && idx == lastIndex + 1 }
                val previousStack = anchor?.let(::layoutStack).orEmpty()
                lastIndex = idx
                active?.let { startFade(it) }
                active = null
                if (sectionLabel != null) {
                    active = spawn(LyricLine(pos, sectionLabel), pos + 3500L).also { it.instrumental = true }
                } else if (idx >= 0) {
                    val line = lyrics.lines[idx]
                    if (line.text.isNotBlank()) {
                        val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: Long.MAX_VALUE
                        active = spawn(line, next).also { nextLine ->
                            if (resumed) nextLine.resumedAt = System.nanoTime()
                            if (anchor != null) {
                                val stride = rowBottom(anchor) + LYRIC_ROW_GAP + halfMain(nextLine)
                                anchor.scrollOut = stride
                                anchor.fadeStartNanos = frameNanos
                                nextLine.previousStackLift = previousStack.lastOrNull()?.let {
                                    it.center + rowBottom(it.lyric) - rowBottom(anchor)
                                } ?: 0f
                                nextLine.previousTail = previousStack.lastOrNull()?.lyric?.lyric
                                nextLine.vYaw = anchor.vYaw
                                nextLine.vPitch = anchor.vPitch
                                nextLine.scrollIn = stride
                                nextLine.scrollStartNanos = frameNanos
                                nextLine.x = anchor.x; nextLine.y = anchor.y; nextLine.z = anchor.z
                                nextLine.yaw = anchor.yaw; nextLine.pitch = anchor.pitch
                                // 接着上一句已经避让好的距离，滚动换行时不在墙边跳一下
                                nextLine.reach = anchor.reach
                            }
                        }
                    }
                }
            }
        }

        val count = minOf(maxLyricLines.value, worldLyricLimit())
        val nextKey = Triple(active, count, lyrics)
        if (upcomingKey != nextKey) {
            upcomingKey = nextKey
            upcoming = if (active == null || lyrics == null || lastIndex < 0 || count == 1) emptyList()
            else (lastIndex + 1 until lyrics.lines.size).asSequence()
                .filter { lyrics.lines[it].text.isNotBlank() }
                .take(count - 1)
                .map { i ->
                    val line = lyrics.lines[i]
                    spawn(line, lyrics.lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE)
                }.toList()
        }
        val stack = active?.let(::layoutStack).orEmpty()
        fading.removeAll { f ->
            if (f.scrollOut > 0f) frameNanos - f.fadeStartNanos >= SCROLL_NANOS
            else active != null || frameNanos - f.fadeStartNanos >= 400_000_000L
        }
        val useFlash = textFlash.get() && !ktvMode.get() && count == 1
        val gfx = DangoClient.gfx
        val view = if (worldVisible() && !useFlash && (active != null || fading.isNotEmpty())) {
            gfx.world.cameraAngles().takeIf { it.yaw.isFinite() && it.pitch.isFinite() }
        } else null
        if (view != null) {
            keepOnScreen(stack, view)
            avoidBlocks(stack)
        }


        val deepGlow = gfx.glow
        val floor = e.playerEyeY?.minus(GROUND_MARGIN)?.takeIf { aboveGround.get() }
        gfx.guarded {
            val songNow = songPos.toLong()
            val beat = if (s != null) playbackBeatPulse(playbackPositionMs()) else 0f
            if (!worldVisible()) {
                // 3D lyrics 关掉或只用侧边列表 = 不画 3D 歌词
            } else if (useFlash) {
                flash.draw(if (s != null && !paused) lyrics else null, songNow, beat, flashSize.value, 1)
            } else {
                // 先把歌词画到发光画布上做出光晕，再正常画一遍清晰的字
                val glowAmount = glowStrength.value * nightGlow()
                if (glow.get() && glowAmount > 0.01f && deepGlow.available && (active != null || fading.isNotEmpty())) {
                    try {
                        if (deepGlow.begin()) {
                            glowPass = true
                            try {
                                drawWorld(songNow, stack, frameNanos, floor, view, count > 1)
                            } finally {
                                glowPass = false
                            }
                            // 光晕是字本身的颜色扩散出去，不另外染色
                            deepGlow.end(1f, 1f, 1f, glowAmount * 4f, glowRadius.value)
                        }
                    } catch (t: Throwable) {
                        // 发光出任何错都不影响歌词本身：关掉发光，下面照常画字
                        glowPass = false
                        deepGlow.disable(t)
                    }
                    // 发光画完，把文字需要的渲染状态重新设好
                    gfx.world.resync()
                }
                // 近似色反色：字先画进离屏画布，合成时和后面的景色比较；做不了就照常直接画
                val contrast = autoContrast.get() && (active != null || fading.isNotEmpty()) && gfx.contrast.begin()
                // 离屏画布上 alpha 要叠加：重新设一次文字的渲染状态（混合方式）
                if (contrast) gfx.world.resync()
                try {
                    drawWorld(songNow, stack, frameNanos, floor, view, count > 1)
                } finally {
                    if (contrast) {
                        gfx.contrast.end()
                        gfx.world.resync()
                    }
                }
            }
        }
    }

    /** 屏幕上的东西（原版暗角之后画，不会被压暗）：驾驶舱歌词和对拍提示 */
    @EventHandler
    fun renderHud(e: HudRenderEvent) {
        if (!enabled) return
        val s = current
        DangoClient.gfx.guarded {
            // 不用驾驶舱样式时也调一下：刚切走时让列表淡出，而不是一下子消失（完全淡出后什么都不画）
            if (sideVisible() || !side.idle) drawSide(e, s)
            Dance.tapStatus()?.let { drawTapStatus(it) }
        }
    }

    /** 侧边歌词列表：当前歌词、进度和设置交给 [SideLyrics] 画（玻璃字、透视倾斜、发光、逐字点亮） */
    private fun drawSide(e: HudRenderEvent, s: NowPlaying?) {
        val platform = DangoClient.platform
        // 驾驶舱字体：每帧对一下版本标记，字体文件被替换了就重新加载（热重载）
        val (sf, sk) = LyricFonts.load(sideFont.value)
        DangoClient.gfx.text.font(CjkStyle.SIDE, sf, sk)
        updatePreview()
        val frame = s?.let {
            val lyrics = currentKey?.let { key -> lyricsFor(key) }
            val pos = songPos.toLong()
            val shown = cover.shownKey == it.key
            SideLyrics.Frame(
                key = it.key,
                title = it.title,
                artist = it.artist,
                lines = lyrics?.lines.orEmpty(),
                index = lyrics?.indexAt(pos) ?: -1,
                positionMs = pos,
                accent = if (shown) cover.accent else CoverArt.DEFAULT_ACCENT,
                palette = if (shown) cover.palette else emptyList(),
                beat = playbackBeatPulse(playbackPositionMs()),
                visible = sideVisible() && !platform.chatOpen,
                previewRow = previewRow,
            )
        }
        side.draw(e, frame, SideLyrics.Settings(
            position = sidePosition.value,
            offsetX = sideOffsetX.value,
            offsetY = sideOffsetY.value,
            width = sideWidth.value,
            lines = sideLines.value,
            textSize = sideTextSize.value,
            glass = sideGlass.get(),
            opacity = sideOpacity.value,
            glow = sideGlow.get(),
            glowStrength = sideGlowStrength.value,
            halo = sideTextGlow.get(),
            translation = translation.get(),
            tilt = sideTilt.value,
            pitch = sidePitch.value,
            italic = sideItalic.value,
            refraction = sideRefraction.value,
            guide = sideGuide.get(),
            colors = sideColors.value,
            color = sideColor.value,
            tint = sideTint.value,
            brightness = sideBrightness.value,
            otherLines = sideOtherLines.value,
            contrast = autoContrast.get(),
            fontKey = sk,
            pixel = !sideSmoothFont.get(),
        ))
    }

    // ---------------------------------------------------------------- 驾驶舱歌词预览（按住预览键 + 滚轮）

    private fun previewHeld(): Boolean = try {
        DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).lyricPreviewHeld()
    } catch (_: Exception) {
        false
    }

    /** 不操作 3 秒自动回到正在唱的那一行 */
    private fun updatePreview() {
        if (previewRow >= 0 && System.currentTimeMillis() - previewInputAt > PREVIEW_RETURN_MS) previewRow = -1
    }

    /**
     * 游戏里的鼠标：按住预览键时，滚轮翻看驾驶舱歌词（不切换快捷栏），左键跳到中间那一行（不挖方块 / 攻击）。
     * 没按预览键、没在用驾驶舱歌词时什么都不做，鼠标照常交给游戏
     */
    @EventHandler
    fun mouse(e: GameMouseEvent) {
        if (!enabled || !sideVisible() || !DangoClient.platform.inGame || !previewHeld()) return
        val s = current ?: return
        val lyrics = currentKey?.let { lyricsFor(it) } ?: return
        val rows = lyrics.lines.size
        if (rows == 0) return
        val currentRow = ((lyrics.indexAt(songPos.toLong())) + 1).coerceIn(0, rows)
        if (e.wheel != 0) {
            // 滚轮往上 = 看前面的歌词
            // 记的是固定的行号：唱到下一句时，正在看的那一行不会跟着挪
            previewRow = ((if (previewRow >= 0) previewRow else currentRow) - Integer.signum(e.wheel)).coerceIn(0, rows)
            previewInputAt = System.currentTimeMillis()
            e.consumed = true
        } else if (e.button == 0 && e.pressed) {
            e.consumed = true
            val row = (if (previewRow >= 0) previewRow else currentRow).coerceIn(0, rows)
            val target = if (row == 0) 0L else lyrics.lines[row - 1].timeMs
            PlaybackView.seek(target.coerceAtLeast(0L))
            previewRow = -1
            DangoLogger.info("Cockpit lyrics: jump to line {} ({} ms) of {}", row, target, s.key)
        }
    }

    /** 对拍时准星下面显示点到第几下 / 点出来的 BPM */
    private fun drawTapStatus(text: String) {
        val platform = DangoClient.platform
        val scale = platform.guiScale.coerceAtLeast(1)
        val w = DangoClient.gfx.text.pixelWidth(text)
        val hud = DangoClient.gfx.hud
        hud.begin()
        hud.text(text, platform.windowWidth / scale / 2f - w / 2f, platform.windowHeight / scale / 2f + 10f, 0xFFFFFFFF.toInt(), true)
        hud.end()
    }

    private fun startFade(f: Floating) {
        f.fadeStartNanos = System.nanoTime()
        fading.add(f)
    }

    /** 拆成"词"：英文按空格；中日韩每两个字一组。返回每个词（去掉空白） */
    private fun splitWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var cjk = 0
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
            cjk = 0
        }
        for (c in text) {
            when {
                c.isWhitespace() -> flush()
                c.code >= 0x2E80 -> {
                    if (sb.isNotEmpty() && cjk == 0) flush()   // 英文后面跟中文：分开
                    sb.append(c)
                    cjk++
                    if (cjk >= 2) flush()
                }
                else -> {
                    if (cjk > 0) flush()
                    sb.append(c)
                }
            }
        }
        flush()
        return out
    }

    /** 每个非空白字符的出现时间：有逐字歌词用真实时间，没有就把一句平均分给每个词 */
    private fun charTimes(line: LyricLine, nextMs: Long): LongArray {
        val times = ArrayList<Long>()
        val words = line.words
        if (words != null) {
            for (w in words) {
                val at = (w.startMs - line.timeMs).coerceAtLeast(0L)
                for (c in w.text) if (!c.isWhitespace()) times += at
            }
        } else {
            val toks = splitWords(line.text)
            val dur = (nextMs - line.timeMs).coerceAtLeast(300L)
            val span = min(dur * 0.75, toks.size * 330.0)
            toks.forEachIndexed { i, t ->
                val at = (span * i / max(1, toks.size)).toLong()
                repeat(t.length) { times += at }
            }
        }
        return times.toLongArray()
    }

    /**
     * "Above ground"：歌词中心最低只到眼睛下面 [GROUND_MARGIN] 格（站在平地上时离地还有一格多），
     * 换算成距离 [d] 处最多能往下偏多少度（原版 pitch 向下为正）
     */
    private fun maxPitch(d: Double): Float =
        Math.toDegrees(asin((GROUND_MARGIN / d).coerceIn(0.0, 1.0))).toFloat()

    private fun spawn(line: LyricLine, nextMs: Long): Floating {
        val text = line.text
        val startMs = line.timeMs
        val animationEnd = if (nextMs == Long.MAX_VALUE) {
            val boundary = playbackDurationMs().takeIf { it > startMs } ?: Long.MAX_VALUE
            line.ktvEndMs(boundary).takeUnless { it == Long.MAX_VALUE } ?: (startMs + 3500L)
        } else nextMs
        val ct = charTimes(line, animationEnd)
        val lines = wrap(text, 200)
        val chars = lines.sumOf { it.length }
        val lineDur = (animationEnd - startMs).coerceAtLeast(400L)
        val reveal = min((chars * 55L).coerceAtLeast(250L), (lineDur * 0.6).toLong())

        val phonetic = line.romanization?.takeIf { it.isNotBlank() }?.let { wrap(it, TRANSLATION_WRAP) }.orEmpty()
        val tr = line.translation?.takeIf { it.isNotBlank() }?.let { wrap(it, TRANSLATION_WRAP) }.orEmpty()
        val player = DangoClient.platform.player
        if (player == null) return Floating(lines, startMs, reveal, 0.0, 0.0, 0.0, 0f, 0f, ct, line, nextMs).also { it.translation = tr; it.phonetic = phonetic }
        val yaw = player.yaw + (random.nextFloat() * 2f - 1f) * spread.value
        val d = distance.value.toDouble()
        var pitch = (player.pitch - 3f - random.nextFloat() * 10f).coerceIn(-60f, 30f)
        if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val dx = -sin(yr) * cos(pr)
        val dy = -sin(pr)
        val dz = cos(yr) * cos(pr)
        val ex = player.eyeX
        val ey = player.eyeY
        val ez = player.eyeZ
        return Floating(lines, startMs, reveal, ex + dx * d, ey + dy * d, ez + dz * d, yaw, pitch, ct, line, nextMs).also { it.translation = tr; it.phonetic = phonetic }
    }


    /** 按像素宽度换行：英文在空格处断，中日韩可以在任意字之间断 */
    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var lastSpace = -1
        for (c in text) {
            sb.append(c)
            if (c == ' ') lastSpace = sb.length - 1
            if (strW(sb.toString()) > maxWidth && sb.length > 1) {
                if (lastSpace > 0) {
                    out += sb.substring(0, lastSpace).trim()
                    val rest = sb.substring(lastSpace + 1)
                    sb.setLength(0)
                    sb.append(rest)
                } else {
                    out += sb.substring(0, sb.length - 1).trim()
                    sb.setLength(0)
                    sb.append(c)
                }
                lastSpace = -1
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    // 默认暖白偏粉；翻译默认比原文稍灰一点（设置里可以改）
    /** 封面的代表色（选了封面主色 / 封面渐变、并且这首歌的封面已经出来时才有） */
    private fun coverColors(mode: Int = lyricColors.value): List<Int>? {
        if (mode != COLORS_COVER && mode != COLORS_GRADIENT) return null
        if (currentKey == null || cover.shownKey != currentKey) return null
        return cover.palette.takeIf { it.isNotEmpty() }
    }

    /** 彩虹的相位：每帧算一次，发光层和本体用同一个值，颜色才对得上 */
    private var rainbowPhase = 0f

    /** 整句一个颜色时用的颜色（封面主色或自定义颜色） */
    private val textRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: textColor.value
    private val translationRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: translationColor.value

    /**
     * 一个字的颜色：[center] 是这个字的中心离行首多远，[width] 是整行宽。
     * 彩虹按位置铺开色相再随时间流动；封面渐变在几种封面颜色之间从左到右过渡；其他情况整句一个颜色 [base]。
     */
    private fun charRgb(center: Float, width: Float, base: Int, mode: Int = lyricColors.value): Int {
        val u = if (width > 0f) (center / width).coerceIn(0f, 1f) else 0.5f
        if (mode == COLORS_RAINBOW) {
            val hue = ((u * RAINBOW_SPAN + rainbowPhase) % 1f + 1f) % 1f
            return java.awt.Color.HSBtoRGB(hue, 0.55f, 1f) and 0xFFFFFF
        }
        val colors = if (mode == COLORS_GRADIENT) coverColors(mode) else null
        if (colors == null || colors.size < 2) return base
        val at = u * (colors.size - 1)
        val i = at.toInt().coerceAtMost(colors.size - 2)
        return mixRgb(colors[i], colors[i + 1], at - i)
    }

    internal fun ktvHighlight(center: Float, width: Float): Int {
        val mode = ktvLyricColors.value
        val base = coverColors(mode)?.firstOrNull() ?: ktvSungColor.value
        return charRgb(center, width, base, mode)
    }

    /** 两个颜色按 [t]（0..1）混合，分通道线性插值 */
    private fun mixRgb(from: Int, to: Int, t: Float): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    // ---------------------------------------------------------------- 歌词文字（平滑字体 / MC 字体 + 发光）

    /** 平滑字体的字号：和 MC 字体的大写字高（7 像素）差不多 */
    private val lyricSize = 10f

    private fun strW(s: String): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(s, lyricSize, CjkStyle.LYRIC) else DangoClient.gfx.text.pixelWidth(s)

    private fun charW(c: Char): Float =
        if (smoothFont.get()) DangoClient.gfx.text.smoothWidth(c.toString(), lyricSize, CjkStyle.LYRIC) else DangoClient.gfx.text.pixelCharWidth(c)

    /** 只在晚上发光时：天黑（含下雨天变暗）慢慢亮起来，天亮慢慢灭掉。返回 0..1 */
    private var nightLevel = -1f
    private var nightNanos = 0L

    private fun nightGlow(): Float {
        val target = if (!glowNightOnly.get()) 1f else {
            val dark = DangoClient.platform.skyDarken
            if (dark == null || dark >= NIGHT_SKY_DARKEN) 1f else 0f
        }
        val now = System.nanoTime()
        if (nightLevel < 0f) nightLevel = target
        val dt = if (nightNanos == 0L) 0f else ((now - nightNanos) / 1e9f).coerceIn(0f, 0.1f)
        nightNanos = now
        nightLevel += (target - nightLevel) * (1f - kotlin.math.exp(-dt / 0.8f))
        return nightLevel
    }

    /** 一个要画的字：[rgb] 在排版时就按它在句子里的位置定好了，抖动和动画不会让同一个字变色 */
    private class Glyph(val c: Char, val x: Float, val y: Float, val a: Float, val k: Int, val rgb: Int, val pop: Float = 1f)

    /** 由 (字序号, 第几帧, 种类) 得到一个固定的 -1..1 随机数：同一帧里不管画几遍（发光层、本体）结果都一样 */
    private fun hashRand(k: Int, frame: Long, salt: Int, seed: Int): Float {
        var h = k * 73856093 xor (frame * 19349663L).toInt() xor (salt * 83492791) xor seed
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    /**
     * 低帧率 wiggle：每个字每 1/fps 秒换一个随机的小角度、小错位、小缩放，中间保持不动，
     * 就是 AE 里 posterizeTime(8) + wiggle 的一顿一顿手绘感。
     */
    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, draw: () -> Unit) = withWiggle(g, seed, frame, wiggle.get(), draw)

    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, on: Boolean, draw: () -> Unit) {
        val amt = wiggleAmount.value
        val wig = on && amt > 0.001f
        if (!wig && g.pop == 1f) {
            draw()
            return
        }
        val rot = if (wig) hashRand(g.k, frame, 1, seed) * 9f * amt else 0f
        val dx = if (wig) hashRand(g.k, frame, 2, seed) * 0.8f * amt else 0f
        val dy = if (wig) hashRand(g.k, frame, 3, seed) * 1.6f * amt else 0f
        val sc = (if (wig) 1f + hashRand(g.k, frame, 4, seed) * 0.12f * amt else 1f) * g.pop
        val cx = g.x + charW(g.c) / 2f
        val cy = g.y + 3.5f
        val pen = DangoClient.gfx.text
        pen.push()
        pen.translate(cx + dx, cy + dy)
        pen.rotate(rot)
        pen.scale(sc, sc)
        pen.translate(-cx, -cy)
        draw()
        pen.pop()
    }

    /** 画一个字：y 是字的顶部（和 MC 字体一样） */
    private fun drawGlyph(c: Char, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoClient.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(c.toString(), x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(c.toString(), x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return                          // FontRenderer 会把 alpha<4 当成不透明
            pen.pixelText(c.toString(), x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    /**
     * 逐字出现：每个字按顺序在 revealMs 内依次淡入并从下往上浮起一点；
     * 发光由 [DeepGlow] 另外做（先把字画到发光画布上），这里只画字本身。
     */
    private fun drawKtv(f: Floating, positionMs: Long, alpha: Float, preview: Boolean, wiggleFrame: Long?) {
        val boundary = if (f.nextMs == Long.MAX_VALUE) {
            playbackDurationMs().takeIf { it > f.startMs } ?: Long.MAX_VALUE
        } else f.nextMs
        val sung = if (preview) 0f else f.lyric.sungCharacters(positionMs, boundary)
        drawKtvLines(f.lines, sung, alpha, -halfMain(f), wiggleFrame = wiggleFrame)
        val phonetic = phoneticRows(f)
        val translated = translationRows(f)
        if (phonetic.isNotEmpty() || translated.isNotEmpty()) {
            val pen = DangoClient.gfx.text
            val progress = if (preview) 0f else f.lyric.sungFraction(positionMs, boundary)
            pen.push()
            try {
                pen.translate(0f, halfMain(f) + ANNOTATION_GAP)
                pen.scale(TRANSLATION_SCALE, TRANSLATION_SCALE)
                val phoneticCount = phonetic.sumOf { line -> line.count { !it.isWhitespace() } }
                val translatedCount = translated.sumOf { line -> line.count { !it.isWhitespace() } }
                val annotationFrame = wiggleFrame?.takeIf { translationWiggle.get() }
                drawKtvLines(phonetic, progress * phoneticCount, alpha * 0.85f, 0f, wiggleFrame = annotationFrame)
                drawKtvLines(translated, progress * translatedCount, alpha * 0.85f, phonetic.size * 11f, wiggleFrame = annotationFrame)
            } finally {
                pen.pop()
            }
        }
    }

    private fun drawKtvLines(
        lines: List<String>, sung: Float, alpha: Float, top: Float,
        useKtvColors: Boolean = true, wiggleFrame: Long? = null,
    ) {
        val seed = if (wiggleFrame != null) lines.hashCode() else 0
        val smooth = smoothFont.get()
        var index = 0
        var y = top
        if (smooth) DangoClient.gfx.text.beginSmooth()
        for (line in lines) {
            val width = strW(line)
            var x = -width / 2f
            for (c in line) {
                val w = charW(c)
                if (!c.isWhitespace()) {
                    val progress = (sung - index++).coerceIn(0f, 1f)
                    val center = x + width / 2f + w / 2f
                    val base = if (useKtvColors) ktvBaseColor.value else charRgb(center, width, textRgb)
                    val draw = {
                        if (progress < 1f) {
                            val shadow = !glowPass && (!useKtvColors || ktvBaseShadow.get())
                            drawGlyph(c, x, y, base, alpha, smooth, shadow)
                        }
                        if (progress > 0f) drawGlyph(c, x, y, ktvHighlight(center, width), alpha * progress, smooth, !glowPass && ktvSungShadow.get())
                    }
                    if (wiggleFrame == null) draw()
                    else withWiggle(Glyph(c, x, y, alpha, index, base), seed, wiggleFrame, draw)
                }
                x += w
            }
            y += 11f
        }
        if (smooth) DangoClient.gfx.text.endSmooth()
    }

    private fun drawAnimated(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, charTimes: LongArray?, wiggleFrame: Long?) {
        val smooth = smoothFont.get()
        val flashMode = charTimes != null && charTimes.isNotEmpty()
        var n = 0   // 非空白字符序号
        val total = lines.sumOf { it.length }.coerceAtLeast(1)
        val lineH = 11f
        var y = -(lines.size * lineH) / 2f
        var k = 0
        val glyphs = ArrayList<Glyph>()
        for (line in lines) {
            val lineW = strW(line)
            var x = -lineW / 2f
            for (c in line) {
                val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, textRgb)
                if (flashMode && !c.isWhitespace()) {
                    // 按词闪出：到了这个词的时间，整个词一下子出现，先放大再弹回原大小
                    val at = charTimes!![min(n, charTimes.size - 1)]
                    n++
                    val t = (elapsedMs - at) / 160f
                    if (t >= 0f) {
                        val a = (t * 3f).coerceIn(0f, 1f) * alphaMul
                        val back = (1f - t).coerceIn(0f, 1f)
                        val pop = 1f + 0.55f * back * back
                        if (a > 0.02f) glyphs += Glyph(c, x, y, a, k, rgb, pop)
                    }
                } else if (!flashMode) {
                    val appearAt = k.toFloat() / total * revealMs
                    var a = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                    a = a * a * (3f - 2f * a)                 // smoothstep
                    val alpha = a * alphaMul
                    if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + (1f - a) * 5f, alpha, k, rgb)
                }
                x += charW(c)
                k++
            }
            y += lineH
        }
        if (glyphs.isEmpty()) return

        if (smooth) DangoClient.gfx.text.beginSmooth()

        val seed = lines.hashCode()
        for (g in glyphs) withWiggle(g, seed, wiggleFrame ?: 0L, wiggleFrame != null && wiggle.get()) {
            drawGlyph(g.c, g.x, g.y, g.rgb, g.a, smooth, !glowPass)
        }

        if (smooth) DangoClient.gfx.text.endSmooth()
    }

    /**
     * 翻译：画在原文下面，字小一点、淡一点；原文开始出现后整行淡入（不逐字）。
     * 和原文用同一个位置和朝向，发光层也会画到。
     */
    private fun drawTranslation(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, top: Float, scale: Float, settled: Boolean, wiggleFrame: Long?) {
        if (lines.isEmpty()) return
        val smooth = smoothFont.get()
        val pen = DangoClient.gfx.text
        val lineH = 11f
        val animate = translationAnimation.get()
        val wig = wiggleFrame != null && translationWiggle.get()
        pen.push()
        pen.translate(0f, top)
        pen.scale(scale, scale)
        if (smooth) pen.beginSmooth()
        if (!wig && (settled || !animate)) {
            // 默认：整行一起淡入
            var a = if (settled) 1f else ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
            a = a * a * (3f - 2f * a)
            val alpha = a * alphaMul * 0.85f
            if (alpha > 0.02f) {
                var y = 0f
                for (line in lines) {
                    if (lyricColors.value == COLORS_RAINBOW || (lyricColors.value == COLORS_GRADIENT && coverColors() != null)) {
                        // 每个字颜色不一样时只能一个个画
                        val lineW = strW(line)
                        var x = -lineW / 2f
                        for (c in line) {
                            val w = charW(c)
                            if (!c.isWhitespace()) drawGlyph(c, x, y, charRgb(x + lineW / 2f + w / 2f, lineW, translationRgb), alpha, smooth, !glowPass)
                            x += w
                        }
                    } else {
                        drawLine(line, -strW(line) / 2f, y, translationRgb, alpha, smooth, !glowPass)
                    }
                    y += lineH
                }
            }
        } else {
            // 逐字：出场动画和原文一样（按顺序淡入、从下往上浮起一点），抖动也用同一套参数
            val total = lines.sumOf { it.length }.coerceAtLeast(1)
            val glyphs = ArrayList<Glyph>()
            var k = 0
            var y = 0f
            for (line in lines) {
                val lineW = strW(line)
                var x = -lineW / 2f
                for (c in line) {
                    val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, translationRgb)
                    val a: Float
                    val lift: Float
                    if (settled) {
                        a = 1f
                        lift = 0f
                    } else if (animate) {
                        val appearAt = 120f + k.toFloat() / total * revealMs
                        var t = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = (1f - t) * 5f
                    } else {
                        var t = ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = 0f
                    }
                    val alpha = a * alphaMul * 0.85f
                    if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + lift, alpha, 1000 + k, rgb)
                    x += charW(c)
                    k++
                }
                y += lineH
            }
            val seed = lines.hashCode()
            for (g in glyphs) withWiggle(g, seed, wiggleFrame ?: 0L, wig) { drawGlyph(g.c, g.x, g.y, g.rgb, g.a, smooth, !glowPass) }
        }
        if (smooth) pen.endSmooth()
        pen.pop()
    }

    /** 整行画（翻译用，不需要逐字动画） */
    private fun drawLine(text: String, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoClient.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(text, x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(text, x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return
            pen.pixelText(text, x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    // ---------------------------------------------------------------- 不跑出屏幕

    private var keepNanos = 0L
    /** 上一帧镜头的位置（Stay on screen 跟着走用），NaN = 还没有 */
    private var lastCamX = Double.NaN
    private var lastCamY = 0.0
    private var lastCamZ = 0.0

    private fun wrapDegrees(d: Double): Double {
        var v = d % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /**
     * "Stay on screen"：歌词（连同字的宽度）超出视野时，沿着离视线最近的方向滑回屏幕边上。
     * 用视线方向的偏角（左右 / 上下）判断，视野大小按设置里的 FOV 和窗口宽高比算；离镜头的距离不变。
     * 每帧在画之前做一次，发光层和字本身用的是同一个位置。
     *
     * 往回拉用的是"临界阻尼弹簧"：有速度、会慢慢加速和减速，不会一碰到边就突然开始动、一回来就突然停，
     * 离地高度在绘制时根据插值后的玩家眼睛高度单独计算。
     */
    private fun keepOnScreen(stack: List<StackRow>, view: ViewAngles) {
        val now = System.nanoTime()
        val dt = if (keepNanos == 0L) 0.016 else ((now - keepNanos) / 1e9).coerceIn(0.0, 0.1)
        keepNanos = now
        if (!stayOnScreen.get()) {
            lastCamX = Double.NaN
            return
        }
        val platform = DangoClient.platform
        if (platform.player == null) return
        val world = DangoClient.gfx.world

        // 移动得快（鞘翅、骑马、矿车）时歌词跟着一起走：只靠转角度的弹簧追不上，会被甩出屏幕。
        // 走路和疾跑（每秒 8 格以内）照旧固定在世界里；每秒 8 到 14 格之间逐渐变成完全跟着走。
        val camX = world.cameraX
        val camY = world.cameraY
        val camZ = world.cameraZ
        var carryX = 0.0
        var carryY = 0.0
        var carryZ = 0.0
        if (!lastCamX.isNaN() && dt > 0.0) {
            val mx = camX - lastCamX
            val my = camY - lastCamY
            val mz = camZ - lastCamZ
            val moved = sqrt(mx * mx + my * my + mz * mz)
            // 一帧走了 50 格以上是传送 / 换维度：不跟，下面的距离限制会把歌词拉回来
            if (moved < 50.0) {
                val carry = ((moved / dt - 8.0) / 6.0).coerceIn(0.0, 1.0)
                carryX = mx * carry
                carryY = my * carry
                carryZ = mz * carry
            }
        }
        lastCamX = camX
        lastCamY = camY
        lastCamZ = camZ
        val maxDist = distance.value * 2.0

        val vHalf = platform.fov / 2.0
        val aspect = platform.windowWidth.toDouble() / platform.windowHeight.coerceAtLeast(1)
        val hHalf = Math.toDegrees(atan(tan(Math.toRadians(vHalf)) * aspect))
        val s = 0.03 * scale.value * (distance.value / 6.5)

        fun follow(f: Floating) {
            f.x += carryX
            f.y += carryY
            f.z += carryZ
            var rx = f.x - world.cameraX
            var ry = f.y - world.cameraY
            var rz = f.z - world.cameraZ
            var len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) return
            // 离得太远（被甩下、传送）：沿原方向拉回设定距离的两倍以内
            if (len > maxDist) {
                val k = maxDist / len
                rx *= k; ry *= k; rz *= k
                len = maxDist
                f.x = world.cameraX + rx
                f.y = world.cameraY + ry
                f.z = world.cameraZ + rz
            }

            // 字本身占的角度：留出一半宽 / 高，再多留 2 度边距
            // 翻译在原文下面多占一截：按整体高度算（偏保守，上下都留出来）
            val visible = if (f === active) stack.map { it.lyric } else listOf(f)
            val width = visible.maxOfOrNull(::rowWidth) ?: 0f
            val halfW = Math.toDegrees(atan2(width * s / 2.0, len))
            val extra = if (f === active) stack.lastOrNull()?.let { it.center + rowBottom(it.lyric) - rowBottom(f) } ?: 0f else f.previousStackLift
            val halfH = Math.toDegrees(atan2(max(rowBottom(f), extra + halfMain(f)) * s, len))
            val hLim = max(0.0, hHalf - halfW - 2.0)
            val vLim = max(0.0, vHalf - halfH - 2.0)

            val curYaw = Math.toDegrees(atan2(-rx, rz))
            val curPitch = Math.toDegrees(asin((-ry / len).coerceIn(-1.0, 1.0)))
            val dYaw = wrapDegrees(curYaw - view.yaw)
            val dPitch = curPitch - view.pitch

            val tYaw = dYaw.coerceIn(-hLim, hLim)
            val tPitch = dPitch.coerceIn(-vLim, vLim)

            // 偏出去多少（在边界以内就是 0）
            val eYaw = dYaw - tYaw
            val ePitch = dPitch - tPitch
            if (eYaw == 0.0 && ePitch == 0.0 && abs(f.vYaw) < 0.05 && abs(f.vPitch) < 0.05) {
                f.vYaw = 0.0
                f.vPitch = 0.0
                return
            }

            // 临界阻尼弹簧，分成小步算（帧率低时也稳定）
            var moveYaw = 0.0
            var movePitch = 0.0
            var left = dt
            while (left > 1e-6) {
                val h = min(left, 1.0 / 240.0)
                left -= h
                f.vYaw += (-SPRING * SPRING * (eYaw - moveYaw) - 2.0 * SPRING * f.vYaw) * h
                f.vPitch += (-SPRING * SPRING * (ePitch - movePitch) - 2.0 * SPRING * f.vPitch) * h
                moveYaw += f.vYaw * h
                movePitch += f.vPitch * h
            }

            val newYaw = curYaw + moveYaw
            val newPitch = (curPitch + movePitch).coerceIn(-89.0, 89.0)
            val yr = Math.toRadians(newYaw)
            val pr = Math.toRadians(newPitch)
            f.x = world.cameraX - sin(yr) * cos(pr) * len
            f.y = world.cameraY - sin(pr) * len
            f.z = world.cameraZ + cos(yr) * cos(pr) * len
            // 不朝向镜头时，字的朝向跟着一起转，免得斜着看
            f.yaw = (f.yaw + wrapDegrees(newYaw - curYaw)).toFloat()
            f.pitch = (f.pitch + (newPitch - curPitch)).toFloat().coerceIn(-90f, 90f)
        }

        active?.let { follow(it) }
        fading.forEach { if (it.scrollOut == 0f) follow(it) }
    }

    private var avoidNanos = 0L

    /**
     * Avoid blocks：从镜头往歌词的中心、左右两端、顶部、底部各打一条射线；被方块挡住，
     * 就算出整组歌词要沿视线拉近多少才能停在方块前面（拉近时按比例缩小，屏幕上看起来一样大）。
     * 往近处拉得快，往回退得慢，免得在方块边上来回跳
     */
    private fun avoidBlocks(stack: List<StackRow>) {
        val now = System.nanoTime()
        val dt = if (avoidNanos == 0L) 0.016 else ((now - avoidNanos) / 1e9).coerceIn(0.0, 0.1)
        avoidNanos = now
        val targets = buildList {
            active?.let(::add)
            fading.forEach { if (it.scrollOut == 0f) add(it) }
        }
        if (!avoidBlocks.get()) {
            targets.forEach { it.reach = 1.0 }
            return
        }
        if (DangoClient.platform.player == null) return
        val platform = DangoClient.platform
        val world = DangoClient.gfx.world
        val cx = world.cameraX
        val cy = world.cameraY
        val cz = world.cameraZ
        val s = 0.03 * scale.value * (distance.value / 6.5)
        val minD = distance.value * 0.6
        for (f in targets) {
            var rx = f.x - cx
            var ry = f.y - cy
            var rz = f.z - cz
            val len = sqrt(rx * rx + ry * ry + rz * rz)
            if (len < 1e-4) continue
            // 和 drawWorld 里一样先做 Keep away，射线打到真正要画的位置
            if (keepAway.get() && len < minD) {
                val k = minD / len
                rx *= k; ry *= k; rz *= k
            }
            val visible = if (f === active) stack.map { it.lyric } else listOf(f)
            val halfW = (visible.maxOfOrNull(::rowWidth) ?: 0f) / 2.0 * s
            val extra = if (f === active) stack.lastOrNull()?.let { it.center + rowBottom(it.lyric) - rowBottom(f) } ?: 0f else 0f
            val top = (extra + halfMain(f)) * s
            val bottom = -rowBottom(f) * s
            val mid = (top + bottom) / 2.0
            val h = sqrt(rx * rx + rz * rz)
            val px = if (h > 1e-4) -rz / h else 1.0
            val pz = if (h > 1e-4) rx / h else 0.0
            var t = 1.0
            fun probe(ox: Double, oy: Double, oz: Double) {
                val tx = rx + ox
                val ty = ry + oy
                val tz = rz + oz
                val pl = sqrt(tx * tx + ty * ty + tz * tz)
                if (pl < 1e-4) return
                val hit = platform.clearFraction(cx, cy, cz, cx + tx, cy + ty, cz + tz)
                if (hit < 1.0) t = min(t, (hit * pl - BLOCK_MARGIN) / pl)
            }
            // 中心 + 四个角：歌词很宽，只探中线会漏掉两头插进树叶 / 墙角的情况
            probe(0.0, mid, 0.0)
            probe(px * halfW, top, pz * halfW)
            probe(-px * halfW, top, -pz * halfW)
            probe(px * halfW, bottom, pz * halfW)
            probe(-px * halfW, bottom, -pz * halfW)
            val target = t.coerceIn(MIN_REACH, 1.0)
            val cur = f.reach
            f.reach = if (cur.isNaN()) target else {
                val rate = if (target < cur) 18.0 else 3.0
                cur + (target - cur) * (1.0 - exp(-rate * dt))
            }
        }
    }

    private fun rowWidth(f: Floating): Float = max(
        f.lines.maxOfOrNull(::strW) ?: 0f,
        (phoneticRows(f) + translationRows(f)).maxOfOrNull { strW(it) * TRANSLATION_SCALE } ?: 0f,
    )

    private fun drawWorld(
        songNow: Long, stack: List<StackRow>, frameNanos: Long, floor: Double?, view: ViewAngles?, multiline: Boolean,
    ) {
        val world = DangoClient.gfx.world
        world.begin()

        val pen = DangoClient.gfx.text

        val frame = frameNanos / 1_000_000L * wiggleFps.value.coerceAtLeast(1) / 1000L

        fun drawRow(f: Floating, alpha: Float, preview: Boolean, settled: Boolean) {
            if (f === active) f.lastWiggleFrame = frame
            // Freeze the last glyph positions when a row leaves, so it slides without snapping.
            val wiggleFrame = if (preview) null else f.lastWiggleFrame
            if (f.instrumental) {
                drawKtvLines(f.lines, 0f, alpha, -halfMain(f), wiggleFrame = wiggleFrame)
            } else if (ktvMode.get()) {
                drawKtv(f, songNow, alpha, preview, wiggleFrame)
            } else {
                val elapsed = songNow - f.startMs
                if (settled) drawKtvLines(f.lines, 0f, alpha, -halfMain(f), useKtvColors = false, wiggleFrame = wiggleFrame)
                else drawAnimated(f.lines, elapsed, f.revealMs, alpha, if (wordPop.get()) f.charTimes else null, wiggleFrame)
                drawTranslation(phoneticRows(f) + translationRows(f), elapsed, f.revealMs, alpha,
                    halfMain(f) + ANNOTATION_GAP, TRANSLATION_SCALE, settled, wiggleFrame)
            }
        }

        fun draw(f: Floating, alphaMul: Float, slide: Float, stackLift: Float, tailAlpha: Float = 1f) {
            var rx = f.x - world.cameraX
            var ry = f.y - world.cameraY
            var rz = f.z - world.cameraZ
            val len = sqrt(rx * rx + ry * ry + rz * rz)

            // 离得太近（走过去 / 疾跑）就沿视线方向把歌词往后推，保持最小距离
            val minD = distance.value * 0.6
            if (keepAway.get() && len in 1e-4..minD) {
                val k = minD / len
                rx *= k; ry *= k; rz *= k
            }

            // Avoid blocks：整组歌词沿视线拉近、同时等比缩小，屏幕上大小不变，只是停在方块前面
            val reach = f.reach.takeUnless { it.isNaN() } ?: 1.0
            rx *= reach; ry *= reach; rz *= reach
            val s = 0.03f * scale.value * (distance.value / 6.5f) * reach.toFloat()
            val bottom = if (f === active) stack.last().let { it.center + rowBottom(it.lyric) } else rowBottom(f)
            val lift = worldStackLift(stackLift, bottom, slide, s, world.cameraY + ry, floor)
            val horizontal = sqrt(rx * rx + rz * rz)
            val yaw = if (facePlayer.get() && horizontal > 1e-4) {
                Math.toDegrees(atan2(-rx, rz)).toFloat()
            } else f.yaw
            // Face the settled anchor so the lyric slide itself cannot tilt the stack.
            val facingLift = if (f === active && f.scrollIn > 0f) {
                worldStackLift(bottom - rowBottom(f), bottom, 0f, s, world.cameraY + ry, floor)
            } else lift
            val pitch = if (facePlayer.get() && len > 1e-4) {
                Math.toDegrees(atan2(-(ry + facingLift), horizontal)).toFloat()
            } else f.pitch
            val safe = if (multiline && view != null) protectSteepView(view, rx, ry + lift, rz, yaw, pitch, reach) else null
            if (f === active) {
                // 共享给别人的仍是避让前的位置和大小（避让是按本机镜头算的，换到别人视角没有意义）
                val inv = 1.0 / reach
                sharedPose = LyricsAnchor(
                    world.cameraX + (safe?.x ?: rx) * inv,
                    world.cameraY + (safe?.y ?: (ry + lift)) * inv,
                    world.cameraZ + (safe?.z ?: rz) * inv,
                    safe?.yaw ?: yaw,
                    safe?.pitch ?: pitch,
                    (s / reach).toFloat(),
                    f.lastWiggleFrame ?: -1L,
                )
            }
            world.push(safe?.x ?: rx, safe?.y ?: (ry + lift), safe?.z ?: rz, safe?.yaw ?: yaw, safe?.pitch ?: pitch, s)
            try {
                if (f === active) {
                    val outgoing = fading.lastOrNull()?.takeIf {
                        it.scrollOut > 0f && it.fadeStartNanos == f.scrollStartNanos
                    }
                    if (outgoing != null) {
                        val progress = scrollProgress(frameNanos - outgoing.fadeStartNanos)
                        pen.push()
                        try {
                            pen.translate(0f, outgoingRowCenter(slide, outgoing.scrollOut, progress))
                            drawRow(outgoing, alphaMul * (1f - progress), false, true)
                        } finally {
                            pen.pop()
                        }
                    }
                    for ((index, row) in stack.withIndex()) {
                        pen.push()
                        try {
                            pen.translate(0f, slide + row.center)
                            val preview = index > 0
                            val alpha = if (preview) alphaMul * 0.78f * (if (index == stack.lastIndex) tailAlpha else 1f) else alphaMul
                            drawRow(row.lyric, alpha, preview, preview || f.scrollIn > 0f)
                        } finally {
                            pen.pop()
                        }
                    }
                } else {
                    pen.push()
                    try {
                        pen.translate(0f, slide)
                        drawRow(f, alphaMul, false, f.scrollOut > 0f)
                    } finally {
                        pen.pop()
                    }
                }
            } finally {
                world.pop()
            }
        }

        if (active == null) fading.lastOrNull { it.scrollOut == 0f }?.let { f ->
            val age = (frameNanos - f.fadeStartNanos).coerceAtLeast(0L)
            val progress = (age / 400_000_000f).coerceIn(0f, 1f)
            draw(f, 1f - progress, -progress * 4f, 0f)
        }
        active?.let { f ->
            val remaining = if (f.scrollIn > 0f) 1f - scrollProgress(frameNanos - f.scrollStartNanos) else 0f
            val extra = stack.last().let { it.center + rowBottom(it.lyric) - rowBottom(f) }
            val lift = extra + (f.previousStackLift - extra) * remaining
            val tailAlpha = if (stack.size > 1 && f.previousTail !== stack.last().lyric.lyric) 1f - remaining else 1f
            draw(f, appearOf(f), f.scrollIn * remaining, lift, tailAlpha)
        }
    }

    private data class RenderPose(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    private fun protectSteepView(view: ViewAngles, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, reach: Double): RenderPose? {
        val t = ((abs(view.pitch) - 60f) / 20f).coerceIn(0f, 1f)
        if (t == 0f) return null
        val align = t * t * (3f - 2f * t)
        val yr = Math.toRadians(view.yaw.toDouble())
        val pr = Math.toRadians(view.pitch.toDouble())
        val dx = -sin(yr) * cos(pr)
        val dy = -sin(pr)
        val dz = cos(yr) * cos(pr)
        // Raising a tall stack can put it right against the camera when looking down.
        // Check the depth after applying that lift, and blend to a readable camera-facing plane at the poles.
        val depth = x * dx + y * dy + z * dz
        // 被方块拉近过的歌词不再往远处推，否则低头看地时又会推回地里
        val push = (distance.value * 0.8 * reach - depth).coerceAtLeast(0.0) * align
        return RenderPose(
            x + dx * push, y + dy * push, z + dz * push,
            (yaw + wrapDegrees((view.yaw - yaw).toDouble()) * align).toFloat(),
            pitch + (view.pitch - pitch) * align,
        )
    }

    // Keep a full row of separation even when the ground constraint limits the stack's upward travel.
    private fun outgoingRowCenter(slide: Float, stride: Float, progress: Float): Float =
        slide - stride * (1f + progress * 0.5f)

    private fun worldStackLift(extra: Float, bottom: Float, slide: Float, scale: Float, anchorY: Double, floor: Double?): Double {
        val lift = extra.toDouble() * scale
        if (floor == null) return lift
        // Reserve a little room for the animated glyphs.
        return max(lift, floor - anchorY + (bottom + slide.coerceAtLeast(0f) + 6f) * scale)
    }

    private fun scrollProgress(age: Long): Float {
        val t = (age.toFloat() / SCROLL_NANOS).coerceIn(0f, 1f)
        // Start moving immediately at the lyric boundary and slow down at the end.
        return t * (2f - t)
    }

    /**
     * 左上角：
     *   [封面]  ♪ 歌名
     *           歌手
     *           [时钟来源 进度]  歌词来源
     */
    /** 歌曲卡片 HUD 正在显示内置播放器的歌时，就不再画左上角的信息，免得重复 */

}

/** 歌词配色：自定义颜色 / 封面主色 / 封面渐变 / 彩虹 */
// Keep the adaptive layout available without overriding the manual line count.
internal val adaptiveLyricLineLimit = false

private const val COLORS_CUSTOM = 0
private const val COLORS_COVER = 1
private const val COLORS_GRADIENT = 2
private const val COLORS_RAINBOW = 3
/** 一句歌词从头到尾跨过多少色相（1 = 整个色环） */
private const val RAINBOW_SPAN = 0.6f

/** 歌词样式：3D 世界歌词 / 侧边列表 / 两者都显示 */
private const val STYLE_WORLD = 0
private const val STYLE_SIDE = 1
private const val STYLE_BOTH = 2
private const val STYLE_OFF = 3

/** 驾驶舱歌词的配色 */
internal const val SIDE_COVER = 0
internal const val SIDE_CUSTOM = 1
internal const val SIDE_GRADIENT = 2
internal const val SIDE_RAINBOW = 3
internal const val SIDE_AURORA = 4

private const val PREVIEW_RETURN_MS = 3000L
